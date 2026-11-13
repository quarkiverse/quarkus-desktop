package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.macos.MacBundle;
import io.quarkiverse.desktop.packaging.deployment.windows.PeImports;
import io.quarkiverse.desktop.packaging.spi.NativeExecutableFileBuildItem;

/**
 * The application image of a native executable : the executable under the name of the application, and the shared
 * libraries that it loads from its directory. A directory on Windows and Linux, an application bundle on macOS.
 */
final class NativeApplicationImage {

    private static final Logger LOGGER = Logger.getLogger(NativeApplicationImage.class);

    /**
     * The Visual C++ runtime libraries of the {@code bin} directory of a JDK for Windows : native-image links every
     * executable with {@code vcruntime140.dll}, and the libraries of the JDK need the others. They are not part of
     * Windows : they come with the Visual C++ Redistributable.
     */
    static final List<String> VC_RUNTIME = List.of("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll");

    private NativeApplicationImage() {
    }

    /**
     * Builds the application image in the {@code image} directory of the output directory.
     *
     * @param executable the native executable, in the build directory
     * @param files the files that the extensions of the application put next to the executable
     * @param jdkHomes the JDKs that the Visual C++ runtime of a Windows executable is copied from, in this order
     */
    static ApplicationImage build(PackagingContext context, Path executable, List<NativeExecutableFileBuildItem> files,
            List<Path> jdkHomes) throws IOException {
        AppInfo app = context.app();
        Path images = context.outputDirectory().resolve("image");
        Path root;
        Path directory;
        String launcher;
        Path content;
        switch (context.os()) {
            case WINDOWS -> {
                root = FileTrees.emptyDirectory(images.resolve(app.name()));
                directory = root;
                launcher = app.name() + ".exe";
                content = root;
            }
            case MACOS -> {
                root = FileTrees.emptyDirectory(images.resolve(app.name() + ".app"));
                directory = Files.createDirectories(root.resolve("Contents/MacOS"));
                launcher = "Contents/MacOS/" + app.name();
                content = root.resolve("Contents/Resources");
            }
            default -> {
                root = FileTrees.emptyDirectory(images.resolve(app.packageName()));
                directory = root;
                launcher = app.packageName();
                content = root;
            }
        }
        Path target = root.resolve(launcher);
        FileTrees.copy(executable, target);
        FileTrees.executable(target);

        // the libraries that native-image and the extensions wrote next to the executable
        Path source = executable.toAbsolutePath().getParent();
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(source)) {
            for (Path sibling : siblings) {
                if (Files.isRegularFile(sibling) && isSharedLibrary(context.os(), sibling.getFileName().toString())) {
                    LOGGER.debugf("Shared library of the application image : %s", sibling);
                    FileTrees.copy(sibling, directory.resolve(sibling.getFileName().toString()));
                }
            }
        }
        for (NativeExecutableFileBuildItem file : files) {
            if (!Files.exists(file.getFile())) {
                throw new PackagingException(file.getFile() + ", a file of the native executable, does not exist");
            }
            String name = file.getFile().getFileName().toString();
            Path fileDirectory = file.getDirectory().isEmpty() ? directory : directory.resolve(file.getDirectory());
            if (context.os() == TargetOs.MACOS && fileDirectory.normalize().startsWith(directory)
                    && Files.isRegularFile(file.getFile()) && !FileTrees.isMachO(file.getFile().toRealPath())) {
                // codesign takes every file of Contents/MacOS for code, which has to be signed : a file that is not
                // (or a link to one, copied as its target) goes to Contents/Resources, and the executable finds it
                // through a link
                FileTrees.copy(file.getFile(), content.resolve(file.getDirectory()).resolve(name));
                Path link = Files.createDirectories(fileDirectory).resolve(name);
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, Path.of(macosResourceLink(file.getDirectory(), name)));
            } else {
                FileTrees.copy(file.getFile(), fileDirectory.resolve(name));
            }
        }
        if (context.os() == TargetOs.WINDOWS) {
            launcher = renameWindowsExecutable(directory, executable.getFileName().toString(), launcher);
        }
        copyContent(context, content);
        if (context.os() == TargetOs.WINDOWS) {
            // after the content : a library of the content is copied as it is, and may need the runtime too
            copyVcRuntime(directory, jdkHomes);
        }

        ApplicationImage image = new ApplicationImage(root, launcher, true);
        if (context.os() == TargetOs.MACOS) {
            String icon = app.fileName() + ".icns";
            FileTrees.write(root.resolve("Contents/Resources").resolve(icon), context.icons().icns());
            MacBundle.writeTypeIcons(app, root.resolve("Contents/Resources"));
            FileTrees.write(root.resolve("Contents/Info.plist"),
                    MacBundle.infoPlist(app, context.config().macos(), app.name(), icon));
            FileTrees.write(root.resolve("Contents/PkgInfo"), "APPL????");
            MacBundle.sign(context, image);
        }
        return image;
    }

    /**
     * Makes the libraries of the application image work with the Windows executable under the name of the
     * application. The libraries of the JDK call the JVM through libraries that native-image links with the
     * executable by its file name ({@code jvm.dll}, {@code java.dll}) : the name that they import is changed to the
     * new one. When it cannot be (a name that is not ASCII, typically), the executable keeps the name that the build
     * gave it.
     *
     * @param directory the directory of the executable and of its libraries, in the application image
     * @param built the file name of the executable in the build directory
     * @param launcher the file name of the executable in the application image
     * @return the file name of the executable in the application image, which is renamed back when needed
     */
    static String renameWindowsExecutable(Path directory, String built, String launcher) throws IOException {
        if (launcher.equalsIgnoreCase(built)) {
            return launcher;
        }
        Map<Path, byte[]> renamed = new LinkedHashMap<>();
        try (DirectoryStream<Path> libraries = Files.newDirectoryStream(directory, "*.dll")) {
            for (Path library : libraries) {
                byte[] bytes = Files.readAllBytes(library);
                if (!PeImports.imports(bytes, built) && !PeImports.importsUnknownExecutable(bytes, built)) {
                    continue;
                }
                Optional<byte[]> changed = PeImports.rename(bytes, built, launcher);
                if (changed.isEmpty()) {
                    LOGGER.warn(keptNameWarning(built, launcher, library.getFileName().toString()));
                    Files.move(directory.resolve(launcher), directory.resolve(built));
                    return built;
                }
                renamed.put(library, changed.get());
            }
        }
        for (Map.Entry<Path, byte[]> library : renamed.entrySet()) {
            LOGGER.debugf("%s now imports %s", library.getKey(), launcher);
            Files.write(library.getKey(), library.getValue());
        }
        return launcher;
    }

    /**
     * The warning of a Windows executable that keeps the name of the build, as a library cannot import its new name :
     * with two ASCII names, the new one is longer and no section of the library has room for it ; otherwise a name is
     * not ASCII, typically.
     *
     * @param built the file name of the executable in the build directory
     * @param launcher the file name of the executable under the name of the application
     * @param library the file name of the library
     */
    static String keptNameWarning(String built, String launcher, String library) {
        String warning = "The executable of the application image is named " + built + " rather than " + launcher + " : "
                + library + " is linked with the executable by its file name, which cannot be changed to that one. ";
        if ((built + launcher).chars().allMatch(c -> c < 0x80)) {
            return warning + "No section of " + library + " has room for the longer name : name the executable after"
                    + " the application in the build (quarkus.package.output-name="
                    + launcher.replaceFirst("(?i)\\.exe$", "") + ", with quarkus.package.jar.add-runner-suffix=false)";
        }
        return warning + "Name the executable in the build, with an ASCII name (quarkus.package.output-name, with"
                + " quarkus.package.jar.add-runner-suffix=false) : Windows loads the libraries of an executable whose"
                + " name is not ASCII only where the ANSI code page is the one of the build";
    }

    /**
     * Copies the Visual C++ runtime libraries that the executables and the libraries of the directory import, and that
     * it does not have (in any case : the ones that Desktop AWT copies stay), from the first JDK that has them, until
     * the copies need no other ({@code msvcp140.dll} imports {@code vcruntime140.dll} and {@code vcruntime140_1.dll}).
     * The universal C runtime ({@code ucrtbase.dll}, {@code api-ms-win-crt-*}) is part of Windows.
     *
     * @param directory the directory of the executable and of its libraries, in the application image
     * @param jdkHomes the JDKs that the libraries are copied from, in this order
     */
    static void copyVcRuntime(Path directory, List<Path> jdkHomes) throws IOException {
        Set<String> done = new HashSet<>();
        boolean copied;
        do {
            copied = false;
            for (String library : missingVcRuntime(directory)) {
                if (!done.add(library)) {
                    continue;
                }
                Optional<Path> source = jdkHomes.stream().map(home -> home.resolve("bin").resolve(library))
                        .filter(Files::isRegularFile).findFirst();
                if (source.isEmpty()) {
                    LOGGER.warnf("The application image has no %s, which its executable or its libraries import, and no"
                            + " JDK of the build has it : the application only starts where the Visual C++"
                            + " Redistributable is installed. Add %s to src/main/packaging/content (or to the"
                            + " quarkus.desktop.packaging.content-directory)", library, library);
                    continue;
                }
                LOGGER.debugf("Visual C++ runtime of the application image : %s", source.get());
                FileTrees.copy(source.get(), directory.resolve(library));
                copied = true;
            }
        } while (copied);
    }

    /**
     * The Visual C++ runtime libraries that the executables and the libraries of a directory import, and that it does
     * not have : in lower case, sorted.
     */
    static List<String> missingVcRuntime(Path directory) throws IOException {
        Set<String> files = new HashSet<>();
        Set<String> imported = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                files.add(name);
                if ((name.endsWith(".exe") || name.endsWith(".dll")) && Files.isRegularFile(entry)) {
                    for (String library : PeImports.names(Files.readAllBytes(entry))) {
                        imported.add(library.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        imported.retainAll(VC_RUNTIME);
        imported.removeAll(files);
        return List.copyOf(imported);
    }

    /**
     * The target of the link that a file of the native executable that is not code has in the {@code Contents/MacOS}
     * directory of a bundle, to its copy in {@code Contents/Resources} : relative to the directory of the link, with
     * {@code /} separators.
     *
     * @param directory the directory of the file, relative to {@code Contents/MacOS}, with {@code /} separators :
     *        empty for {@code Contents/MacOS}
     * @param fileName the file name of the file
     */
    static String macosResourceLink(String directory, String fileName) {
        Path link = Path.of("Contents", "MacOS").resolve(directory).resolve(fileName).normalize();
        Path resource = Path.of("Contents", "Resources").resolve(directory).resolve(fileName).normalize();
        return link.getParent().relativize(resource).toString().replace('\\', '/');
    }

    /**
     * The libraries of the application image that its executable loads only in some cases : the files of the native
     * executable that are optional ({@link NativeExecutableFileBuildItem#isOptional}), relative to the root of the
     * image, with {@code /} separators.
     *
     * @param files the files that the extensions of the application put next to the executable
     */
    static Set<String> optionalLibraries(TargetOs os, List<NativeExecutableFileBuildItem> files) {
        Set<String> libraries = new TreeSet<>();
        for (NativeExecutableFileBuildItem file : files) {
            if (file.isOptional()) {
                // the directory of the executable : Contents/MacOS in a bundle, the root of the image elsewhere
                Path path = Path.of(os == TargetOs.MACOS ? "Contents/MacOS" : "", file.getDirectory(),
                        file.getFile().getFileName().toString()).normalize();
                libraries.add(path.toString().replace('\\', '/'));
            }
        }
        return libraries;
    }

    /**
     * Whether a file next to the executable is a shared library of the operating system of the application.
     */
    static boolean isSharedLibrary(TargetOs os, String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return switch (os) {
            case WINDOWS -> name.endsWith(".dll");
            case MACOS -> name.endsWith(".dylib") || name.endsWith(".jnilib");
            case LINUX -> name.endsWith(".so") || name.matches(".*\\.so\\.[0-9.]+");
        };
    }

    /**
     * Copies the content directory of the project into the application image
     * ({@code quarkus.desktop.packaging.content-directory}).
     */
    static void copyContent(PackagingContext context, Path target) throws IOException {
        Path content;
        if (context.config().contentDirectory().isPresent()) {
            content = context.projectDirectory().resolve(context.config().contentDirectory().get());
            if (!Files.isDirectory(content)) {
                throw new PackagingException("quarkus.desktop.packaging.content-directory : " + content
                        + " is not a directory");
            }
        } else {
            content = context.projectDirectory().resolve("src/main/packaging/content");
            if (!Files.isDirectory(content)) {
                return;
            }
        }
        FileTrees.copy(content, target);
    }
}
