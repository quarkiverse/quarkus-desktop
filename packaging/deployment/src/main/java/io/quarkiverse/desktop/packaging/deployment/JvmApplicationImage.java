package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkiverse.desktop.packaging.deployment.macos.MacBundle;

/**
 * The application image of JVM mode, built with {@code jpackage --type app-image} : the jar of the application, a Java
 * runtime built with {@code jlink}, and a launcher with the icon of the application. {@code jpackage} builds the
 * installers of some formats too ; here it only builds the application image, which the packagers of this extension
 * take as they take the one of a native executable.
 */
final class JvmApplicationImage {

    /**
     * The modules that Quarkus itself needs, whatever the extensions of the application : its logging, its
     * {@code InitialContextFactoryBuilder} (which disables JNDI), the {@code Unsafe} class of its thread pools, and the
     * file system of the jars, which the configuration is read with. They are added to the modules of the
     * configuration : an application without them exits when it starts, without a message.
     */
    static final List<String> QUARKUS_MODULES = List.of("java.logging", "java.naming", "jdk.unsupported", "jdk.zipfs");

    /**
     * The oldest JDK whose {@code jpackage} builds the application image : its {@code jlink} builds a Java runtime of
     * its version, which has to run Quarkus, and its launcher configuration has the main jar on a line of its own.
     */
    static final int MINIMUM_JDK = 21;

    /**
     * The feature version at the start of a line of the output of {@code jpackage --version}.
     */
    private static final Pattern FEATURE_VERSION = Pattern.compile("(\\d{1,9})(?!\\d)");

    /**
     * The name of a bundle in its information property list : the string after the {@code CFBundleName} key.
     */
    private static final Pattern BUNDLE_NAME = Pattern.compile("<key>CFBundleName</key>\\s*<string>(.*?)</string>");

    private JvmApplicationImage() {
    }

    /**
     * The modules of the Java runtime : the ones of the configuration, and the ones of Quarkus.
     */
    static List<String> modules(List<String> configured) {
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        for (String module : configured) {
            modules.add(module.trim());
        }
        modules.addAll(QUARKUS_MODULES);
        return new ArrayList<>(modules);
    }

    /**
     * Builds the application image in the {@code image} directory of the output directory.
     *
     * @param jar the runner jar : {@code quarkus-app/quarkus-run.jar}, or an uber-jar
     * @param uberJar whether the jar has the whole application
     * @param archive the AppCDS or AOT archive that the build wrote next to the jar, if any
     * @param extensionOptions the options of the JVM that the extensions of the application ask for
     */
    static ApplicationImage build(PackagingContext context, Path jar, boolean uberJar, Optional<Path> archive,
            List<String> extensionOptions) throws IOException {
        AppInfo app = context.app();
        DesktopPackagingConfig.Jvm config = context.config().jvm();
        Path jpackage = jpackage(context);
        Path work = context.workDirectory("jvm");

        Path input = input(work, jar, uberJar, archive);

        String name = context.os() == TargetOs.LINUX ? app.packageName() : app.name();
        // the name of the menu bar on macOS : jpackage asks for at most 16 characters, the bundle gets the whole name
        // afterwards
        String menuBarName = name.length() > 16 ? name.substring(0, 16).trim() : name;
        Path images = Files.createDirectories(context.outputDirectory().resolve("image"));
        Path root = images.resolve(context.os() == TargetOs.MACOS ? name + ".app" : name);
        FileTrees.delete(root);

        // the description is the file description of the Windows launcher, which Windows shows as the name of the
        // program : the name there
        List<String> command = new ArrayList<>(List.of(jpackage.toString(), "--type", "app-image", "--dest",
                images.toString(), "--name", name, "--input", input.toString(), "--main-jar", jar.getFileName().toString(),
                "--vendor", app.vendor(), "--description",
                context.os() == TargetOs.WINDOWS ? app.name() : app.description()));
        app.copyright().ifPresent(copyright -> {
            command.add("--copyright");
            command.add(copyright);
        });
        command.add("--icon");
        command.add(icon(context, work).toString());
        command.add("--app-version");
        command.add(version(context));
        if (context.os() == TargetOs.MACOS) {
            command.add("--mac-package-identifier");
            command.add(app.identifier());
            command.add("--mac-package-name");
            command.add(menuBarName);
            command.add("--mac-app-category");
            command.add(context.config().macos().category().replaceFirst("^public\\.app-category\\.", ""));
        }
        command.addAll(runtimeArguments(config.modules(), config.jlinkOptions(), config.runtimeImage(),
                context.projectDirectory()));
        List<String> options = new ArrayList<>();
        // before the options of the configuration, which may set the version too
        versionOption(context.os(), MacBundle.version(app)).ifPresent(options::add);
        options.addAll(extensionOptions);
        config.options().ifPresent(options::addAll);
        for (String option : options) {
            command.add("--java-options");
            command.add(option);
        }
        // jlink runs the objcopy of the PATH, whatever the configuration of the tools
        if (TargetOs.host() == TargetOs.LINUX
                && stripsNativeSymbols(config.jlinkOptions(), config.runtimeImage().isPresent())
                && new Tools(Map.of()).find("objcopy").isEmpty()) {
            throw new PackagingException("objcopy not found : jlink strips the debug symbols of the native libraries of the"
                    + " Java runtime with the objcopy of the PATH. Install binutils (apt install binutils, dnf install"
                    + " binutils), or set quarkus.desktop.packaging.jvm.jlink-options without --strip-debug, such as"
                    + " --strip-native-commands,--strip-java-debug-attributes,--no-man-pages,--no-header-files");
        }
        Tools.exec(work, Map.of(), command);

        ApplicationImage image = switch (context.os()) {
            case WINDOWS -> new ApplicationImage(root, name + ".exe", false);
            case MACOS -> new ApplicationImage(root, "Contents/MacOS/" + name, false);
            case LINUX -> new ApplicationImage(root, "bin/" + name, false);
        };
        if (!Files.isRegularFile(image.launcherPath())) {
            throw new PackagingException("jpackage did not write the launcher " + image.launcherPath());
        }
        if (!uberJar) {
            Path application = switch (context.os()) {
                case WINDOWS -> root.resolve("app");
                case MACOS -> root.resolve("Contents/app");
                case LINUX -> root.resolve("lib/app");
            };
            Path configuration = application.resolve(name + ".cfg");
            String launcherConfiguration = Files.readString(configuration);
            if (!configuration.toFile().canWrite()) {
                configuration.toFile().setWritable(true);
            }
            Files.writeString(configuration, runnerJarOnly(launcherConfiguration));
        }
        NativeApplicationImage.copyContent(context,
                context.os() == TargetOs.MACOS ? root.resolve("Contents/Resources") : root);
        if (context.os() == TargetOs.MACOS) {
            if (!menuBarName.equals(name)) {
                bundleName(root, name);
            }
            bundleVersion(context, root);
            MacBundle.sign(context, image);
        }
        return image;
    }

    /**
     * The options of {@code jpackage} for the Java runtime of the application : its modules and the options of the
     * {@code jlink} that builds it, or a given runtime, which {@code jpackage} takes as it is.
     *
     * @param modules the modules of the configuration ({@code quarkus.desktop.packaging.jvm.modules})
     * @param jlinkOptions the options of {@code jlink} ({@code quarkus.desktop.packaging.jvm.jlink-options})
     * @param runtimeImage the given runtime ({@code quarkus.desktop.packaging.jvm.runtime-image})
     * @param projectDirectory the directory of the relative path of the runtime
     * @throws PackagingException when a runtime is given with modules or options of {@code jlink}
     */
    static List<String> runtimeArguments(Optional<List<String>> modules, Optional<List<String>> jlinkOptions,
            Optional<String> runtimeImage, Path projectDirectory) {
        List<String> arguments = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        modules.filter(list -> !list.isEmpty()).ifPresent(list -> {
            arguments.add("--add-modules");
            arguments.add(String.join(",", modules(list)));
            properties.add("quarkus.desktop.packaging.jvm.modules");
        });
        jlinkOptions.filter(list -> !list.isEmpty()).ifPresent(list -> {
            arguments.add("--jlink-options");
            arguments.add(String.join(" ", list));
            properties.add("quarkus.desktop.packaging.jvm.jlink-options");
        });
        if (runtimeImage.isPresent()) {
            if (!properties.isEmpty()) {
                String names = String.join(" and ", properties);
                throw new PackagingException("quarkus.desktop.packaging.jvm.runtime-image : jpackage takes the given Java"
                        + " runtime as it is, without jlink, so it cannot be set with " + names + " : remove runtime-image"
                        + " to have jlink build the runtime, or remove " + names);
            }
            arguments.add("--runtime-image");
            arguments.add(projectDirectory.resolve(runtimeImage.get()).toString());
        }
        return arguments;
    }

    /**
     * Whether {@code jlink} strips the debug symbols of the native libraries of the Java runtime, which it does on
     * Linux with the {@code objcopy} of the {@code PATH} : with the default options of {@code jpackage}, which have
     * {@code --strip-debug}, or with options that strip them without the path of {@code objcopy}.
     *
     * @param jlinkOptions the options of {@code jlink} ({@code quarkus.desktop.packaging.jvm.jlink-options})
     * @param runtimeImage whether the Java runtime is given rather than built by {@code jlink}
     */
    static boolean stripsNativeSymbols(Optional<List<String>> jlinkOptions, boolean runtimeImage) {
        if (runtimeImage) {
            return false;
        }
        if (jlinkOptions.isEmpty() || jlinkOptions.get().isEmpty()) {
            return true;
        }
        boolean strips = false;
        // jpackage splits the options on white space
        for (String option : String.join(" ", jlinkOptions.get()).trim().split("\\s+")) {
            if (option.contains("objcopy=")) {
                return false;
            }
            strips |= option.equals("--strip-debug") || option.equals("-G")
                    || option.startsWith("--strip-native-debug-symbols");
        }
        return strips;
    }

    /**
     * The input directory of {@code jpackage}, which it copies into the image : the directory of a fast-jar, or a
     * directory with the uber-jar alone. The AppCDS or AOT archive that the build wrote in the directory of a fast-jar
     * ({@code quarkus.package.jar.aot.enabled}) is left out : it was built for the JDK of the build and the path of the
     * jar, not for the runtime of the image, and the launcher does not use it.
     *
     * @param work the work directory of the image
     * @param jar the runner jar : {@code quarkus-app/quarkus-run.jar}, or an uber-jar
     * @param uberJar whether the jar has the whole application
     * @param archive the archive (a file, or a directory), if any
     */
    static Path input(Path work, Path jar, boolean uberJar, Optional<Path> archive) throws IOException {
        if (uberJar) {
            Path input = Files.createDirectories(work.resolve("input"));
            FileTrees.copy(jar, input.resolve(jar.getFileName().toString()));
            return input;
        }
        // the directory of a fast-jar : the runner jar with its lib, app and quarkus directories
        Path directory = jar.toAbsolutePath().getParent();
        if (archive.isEmpty() || !directory.normalize().equals(archive.get().toAbsolutePath().normalize().getParent())) {
            return directory;
        }
        Path input = FileTrees.emptyDirectory(work.resolve("input"));
        FileTrees.copy(directory, input);
        FileTrees.delete(input.resolve(archive.get().getFileName().toString()));
        return input;
    }

    /**
     * The configuration of the launcher of a fast-jar, with the runner jar as its whole class path, as
     * {@code java -jar quarkus-run.jar} has it. {@code jpackage} puts every jar of the input directory on the class
     * path : the jars of the application would then be loaded by the class loader of the JVM, before the class loader
     * of Quarkus, which has the classes that the build transformed.
     *
     * @throws PackagingException when the main jar is not on a line of its own ({@code app.mainjar}), as the
     *         {@code jpackage} of JDK 21 to 25 writes it
     */
    static String runnerJarOnly(String launcherConfiguration) {
        StringBuilder configuration = new StringBuilder();
        boolean mainJar = false;
        for (String line : launcherConfiguration.split("\\R")) {
            mainJar |= line.startsWith("app.mainjar=");
            if (!line.startsWith("app.classpath=")) {
                configuration.append(line).append(System.lineSeparator());
            }
        }
        if (!mainJar) {
            throw new PackagingException("The launcher configuration that jpackage wrote has no app.mainjar line, which"
                    + " the class path of a fast-jar needs : build the image with the jpackage of another JDK"
                    + " (quarkus.desktop.packaging.jvm.jdk), or from an uber-jar (quarkus.package.jar.type=uber-jar)");
        }
        return configuration.toString();
    }

    /**
     * The {@code jpackage} of the configuration, of JDK 21 or later.
     */
    private static Path jpackage(PackagingContext context) {
        Tools tools = context.tools();
        String executable = TargetOs.host() == TargetOs.WINDOWS ? "jpackage.exe" : "jpackage";
        Path jpackage = jpackage(tools.configured("jpackage"),
                context.config().jvm().jdk().map(context.projectDirectory()::resolve),
                Path.of(System.getProperty("java.home")), executable, () -> tools.find("jpackage"));
        checkVersion(jpackage, Tools.exec(List.of(jpackage.toString(), "--version")).output());
        return jpackage;
    }

    /**
     * The {@code jpackage} that builds the application image : the one of
     * {@code quarkus.desktop.packaging.tools.jpackage}, else the one of the configured JDK, else the one of the JDK of
     * the build, else the one of the {@code PATH}, which is the last resort when the JDK of the build has none. The
     * {@code jpackage} of another JDK never replaces the one of the configured JDK : the build fails without it.
     *
     * @param tool the path of {@code quarkus.desktop.packaging.tools.jpackage}
     * @param jdk the home of the configured JDK ({@code quarkus.desktop.packaging.jvm.jdk})
     * @param javaHome the home of the JDK of the build
     * @param executable the file name of {@code jpackage} in the {@code bin} directory of a JDK
     * @param onPath finds {@code jpackage} on the {@code PATH}
     */
    static Path jpackage(Optional<Path> tool, Optional<Path> jdk, Path javaHome, String executable,
            Supplier<Optional<Path>> onPath) {
        if (tool.isPresent()) {
            return tool.get();
        }
        Path bin = jdk.orElse(javaHome).resolve("bin");
        Path jpackage = bin.resolve(executable);
        if (Files.isRegularFile(jpackage)) {
            return jpackage;
        }
        if (jdk.isPresent()) {
            throw new PackagingException("quarkus.desktop.packaging.jvm.jdk : " + jpackage + " not found : set it to the"
                    + " home of a JDK with the jdk.jpackage module");
        }
        return onPath.get().orElseThrow(() -> new PackagingException("jpackage not found in " + bin + " : packaging"
                + " a JVM application needs a JDK with the jdk.jpackage module (set"
                + " quarkus.desktop.packaging.jvm.jdk to its home, or build a native executable)"));
    }

    /**
     * Checks the version of a {@code jpackage}.
     *
     * @param output the output of {@code jpackage --version}
     * @throws PackagingException when it is the {@code jpackage} of a JDK older than {@link #MINIMUM_JDK}
     */
    static void checkVersion(Path jpackage, String output) {
        int version = featureVersion(output);
        if (version >= 0 && version < MINIMUM_JDK) {
            throw new PackagingException(jpackage + " is the jpackage of JDK " + version + " : packaging a JVM"
                    + " application needs the one of JDK " + MINIMUM_JDK + " or later (set"
                    + " quarkus.desktop.packaging.jvm.jdk to the home of such a JDK, or"
                    + " quarkus.desktop.packaging.tools.jpackage to its jpackage)");
        }
    }

    /**
     * The feature version of a {@code jpackage}, from the output of {@code jpackage --version} : {@code 21} for
     * {@code 21.0.2}, after the lines that the JVM may write before ({@code Picked up JAVA_TOOL_OPTIONS}), or
     * {@code -1} when the output has no version.
     */
    static int featureVersion(String output) {
        for (String line : output.split("\\R")) {
            Matcher matcher = FEATURE_VERSION.matcher(line.trim());
            if (matcher.lookingAt()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }

    /**
     * The icon of the launcher, in the format of the operating system.
     */
    private static Path icon(PackagingContext context, Path work) throws IOException {
        return switch (context.os()) {
            case WINDOWS -> FileTrees.write(work.resolve("application.ico"), context.icons().ico());
            case MACOS -> FileTrees.write(work.resolve("application.icns"), context.icons().icns());
            case LINUX -> context.icons().writePng(work.resolve("application.png"), 256);
        };
    }

    /**
     * The version that {@code jpackage} takes, for the version of the application.
     */
    private static String version(PackagingContext context) {
        return jpackageVersion(context.os(), context.app().numericVersion(3));
    }

    /**
     * The version that {@code jpackage} takes : numbers, the first of which is not zero on macOS (the real version is
     * then written in the bundle afterwards, and given to the application by {@link #versionOption}).
     *
     * @param version the version of the application, three numbers
     */
    static String jpackageVersion(TargetOs os, String version) {
        return os == TargetOs.MACOS && version.startsWith("0.") ? "1" + version.substring(1) : version;
    }

    /**
     * The option of the JVM that gives the application its version ({@code jpackage.app-version}), when
     * {@code jpackage} could not take it : the launcher configuration has the version that {@code jpackage} took
     * before the options of the JVM, and the last value of a system property wins.
     *
     * @param version the version of the application, three numbers
     */
    static Optional<String> versionOption(TargetOs os, String version) {
        return jpackageVersion(os, version).equals(version) ? Optional.empty()
                : Optional.of("-Djpackage.app-version=" + version);
    }

    /**
     * Writes the whole name of the application in the bundle, when {@code jpackage} took a shorter one.
     */
    private static void bundleName(Path bundle, String name) throws IOException {
        Path plist = bundle.resolve("Contents/Info.plist");
        String infoPlist = Files.readString(plist);
        if (!plist.toFile().canWrite()) {
            plist.toFile().setWritable(true);
        }
        Files.writeString(plist, withBundleName(infoPlist, name));
    }

    /**
     * An information property list with another name of the bundle ({@code CFBundleName}, the name of the menu bar),
     * or as it is when it has none.
     */
    static String withBundleName(String infoPlist, String name) {
        Matcher matcher = BUNDLE_NAME.matcher(infoPlist);
        if (!matcher.find()) {
            return infoPlist;
        }
        return new StringBuilder(infoPlist).replace(matcher.start(1), matcher.end(1), Xml.escape(name)).toString();
    }

    /**
     * Writes the version of the application in the bundle, when {@code jpackage} could not take it.
     */
    private static void bundleVersion(PackagingContext context, Path bundle) {
        String version = MacBundle.version(context.app());
        if (version.equals(version(context))) {
            return;
        }
        Path plistBuddy = context.tools().require("PlistBuddy", "PlistBuddy is part of macOS",
                Path.of("/usr/libexec/PlistBuddy"));
        Path plist = bundle.resolve("Contents/Info.plist");
        for (String key : List.of("CFBundleShortVersionString", "CFBundleVersion")) {
            Tools.exec(List.of(plistBuddy.toString(), "-c", "Set :" + key + " " + version, plist.toString()));
        }
    }
}
