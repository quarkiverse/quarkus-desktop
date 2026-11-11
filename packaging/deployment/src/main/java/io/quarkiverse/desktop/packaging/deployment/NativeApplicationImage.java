package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    private NativeApplicationImage() {
    }

    /**
     * Builds the application image in the {@code image} directory of the output directory.
     *
     * @param executable the native executable, in the build directory
     * @param files the files that the extensions of the application put next to the executable
     */
    static ApplicationImage build(PackagingContext context, Path executable, List<NativeExecutableFileBuildItem> files)
            throws IOException {
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
            Path fileDirectory = file.getDirectory().isEmpty() ? directory : directory.resolve(file.getDirectory());
            FileTrees.copy(file.getFile(), fileDirectory.resolve(file.getFile().getFileName().toString()));
        }
        if (context.os() == TargetOs.WINDOWS) {
            launcher = renameWindowsExecutable(directory, executable.getFileName().toString(), launcher);
        }
        copyContent(context, content);

        ApplicationImage image = new ApplicationImage(root, launcher, true);
        if (context.os() == TargetOs.MACOS) {
            String icon = app.fileName() + ".icns";
            FileTrees.write(root.resolve("Contents/Resources").resolve(icon), context.icons().icns());
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
                if (!PeImports.imports(bytes, built)) {
                    continue;
                }
                Optional<byte[]> changed = PeImports.rename(bytes, built, launcher);
                if (changed.isEmpty()) {
                    LOGGER.warnf("The executable of the application image is named %s rather than %s : %s is linked with"
                            + " the executable by its file name, which cannot be changed to that one. Name the"
                            + " executable in the build (quarkus.package.output-name, with"
                            + " quarkus.package.jar.add-runner-suffix=false)", built, launcher, library.getFileName());
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
