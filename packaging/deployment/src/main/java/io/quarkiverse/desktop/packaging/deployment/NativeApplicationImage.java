package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.macos.MacBundle;
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
