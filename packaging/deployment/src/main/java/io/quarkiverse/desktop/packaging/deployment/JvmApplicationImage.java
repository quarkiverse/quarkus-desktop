package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * @param extensionOptions the options of the JVM that the extensions of the application ask for
     */
    static ApplicationImage build(PackagingContext context, Path jar, boolean uberJar, List<String> extensionOptions)
            throws IOException {
        AppInfo app = context.app();
        DesktopPackagingConfig.Jvm config = context.config().jvm();
        Path jpackage = jpackage(context);
        Path work = context.workDirectory("jvm");

        Path input;
        if (uberJar) {
            input = Files.createDirectories(work.resolve("input"));
            FileTrees.copy(jar, input.resolve(jar.getFileName().toString()));
        } else {
            // the directory of a fast-jar : the runner jar with its lib, app and quarkus directories
            input = jar.toAbsolutePath().getParent();
        }

        String name = context.os() == TargetOs.LINUX ? app.packageName() : app.name();
        Path images = Files.createDirectories(context.outputDirectory().resolve("image"));
        Path root = images.resolve(context.os() == TargetOs.MACOS ? name + ".app" : name);
        FileTrees.delete(root);

        List<String> command = new ArrayList<>(List.of(jpackage.toString(), "--type", "app-image", "--dest",
                images.toString(), "--name", name, "--input", input.toString(), "--main-jar", jar.getFileName().toString(),
                "--vendor", app.vendor(), "--description", app.description()));
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
            // the name of the menu bar : jpackage rejects more than 16 characters
            command.add("--mac-package-name");
            command.add(name.length() > 16 ? name.substring(0, 16).trim() : name);
            command.add("--mac-app-category");
            command.add(context.config().macos().category().replaceFirst("^public\\.app-category\\.", ""));
        }
        config.modules().filter(modules -> !modules.isEmpty()).ifPresent(modules -> {
            command.add("--add-modules");
            command.add(String.join(",", modules(modules)));
        });
        config.jlinkOptions().filter(options -> !options.isEmpty()).ifPresent(options -> {
            command.add("--jlink-options");
            command.add(String.join(" ", options));
        });
        config.runtimeImage().ifPresent(runtime -> {
            command.add("--runtime-image");
            command.add(context.projectDirectory().resolve(runtime).toString());
        });
        List<String> options = new ArrayList<>(extensionOptions);
        config.options().ifPresent(options::addAll);
        for (String option : options) {
            command.add("--java-options");
            command.add(option);
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
            bundleVersion(context, root);
            MacBundle.sign(context, image);
        }
        return image;
    }

    /**
     * The configuration of the launcher of a fast-jar, with the runner jar as its whole class path, as
     * {@code java -jar quarkus-run.jar} has it. {@code jpackage} puts every jar of the input directory on the class
     * path : the jars of the application would then be loaded by the class loader of the JVM, before the class loader
     * of Quarkus, which has the classes that the build transformed.
     */
    static String runnerJarOnly(String launcherConfiguration) {
        StringBuilder configuration = new StringBuilder();
        for (String line : launcherConfiguration.split("\\R")) {
            if (!line.startsWith("app.classpath=")) {
                configuration.append(line).append(System.lineSeparator());
            }
        }
        return configuration.toString();
    }

    /**
     * The {@code jpackage} of the configured JDK, or of the JDK of the build.
     */
    private static Path jpackage(PackagingContext context) {
        Optional<String> configured = context.config().jvm().jdk();
        Path jdk = configured.map(context.projectDirectory()::resolve).orElseGet(() -> Path.of(System.getProperty(
                "java.home")));
        String executable = TargetOs.host() == TargetOs.WINDOWS ? "jpackage.exe" : "jpackage";
        return context.tools().find("jpackage", jdk.resolve("bin").resolve(executable))
                .orElseThrow(() -> new PackagingException("jpackage not found in " + jdk.resolve("bin") + " : packaging"
                        + " a JVM application needs a JDK with the jdk.jpackage module (set"
                        + " quarkus.desktop.packaging.jvm.jdk to its home, or build a native executable)"));
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
     * The version that {@code jpackage} takes : numbers, the first of which is not zero on macOS (the real version is
     * then written in the bundle afterwards).
     */
    private static String version(PackagingContext context) {
        String version = context.app().numericVersion(3);
        return context.os() == TargetOs.MACOS && version.startsWith("0.") ? "1" + version.substring(1) : version;
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
