package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.linux.AppImagePackager;
import io.quarkiverse.desktop.packaging.deployment.linux.DebPackager;
import io.quarkiverse.desktop.packaging.deployment.linux.FlatpakPackager;
import io.quarkiverse.desktop.packaging.deployment.linux.RpmPackager;
import io.quarkiverse.desktop.packaging.deployment.linux.SnapPackager;
import io.quarkiverse.desktop.packaging.deployment.macos.DmgPackager;
import io.quarkiverse.desktop.packaging.deployment.macos.MacBundle;
import io.quarkiverse.desktop.packaging.deployment.macos.PkgPackager;
import io.quarkiverse.desktop.packaging.deployment.windows.InnoSetupPackager;
import io.quarkiverse.desktop.packaging.deployment.windows.MsiPackager;
import io.quarkiverse.desktop.packaging.deployment.windows.MsixPackager;
import io.quarkiverse.desktop.packaging.deployment.windows.WindowsSdk;

/**
 * Builds the packages of the configuration from the application image.
 */
final class Packaging {

    private static final Logger LOGGER = Logger.getLogger(Packaging.class);

    private Packaging() {
    }

    /**
     * The formats of the configuration that the operating system of the application has, without duplicates.
     */
    static List<PackageFormat> formats(DesktopPackagingConfig config, TargetOs os) {
        List<PackageFormat> formats = new ArrayList<>();
        for (PackageFormat format : new LinkedHashSet<>(config.formats().orElse(List.of()))) {
            if (format.supports(os)) {
                formats.add(format);
            } else {
                LOGGER.debugf("The %s package is not built : it is not a format of %s", format, os);
            }
        }
        return formats;
    }

    /**
     * The warning for the formats that are not built because the native executable is built in a container, for
     * Linux ({@code quarkus.native.container-build}), on another operating system : the formats of the operating
     * system of the build, and the ones of Linux, which are built on Linux. Empty when there are none.
     *
     * @param os the operating system of the application : Linux for a container build
     * @param host the operating system of the build
     */
    static Optional<String> containerBuildWarning(DesktopPackagingConfig config, TargetOs os, TargetOs host) {
        if (os == host) {
            return Optional.empty();
        }
        List<String> skipped = new ArrayList<>();
        List<String> hostFormats = new ArrayList<>();
        List<String> osFormats = new ArrayList<>();
        for (PackageFormat format : new LinkedHashSet<>(config.formats().orElse(List.of()))) {
            String name = format.name().toLowerCase(Locale.ROOT);
            if (format.supports(host)) {
                hostFormats.add(name);
                skipped.add(name);
            } else if (format.supports(os)) {
                osFormats.add(name);
                skipped.add(name);
            }
        }
        if (skipped.isEmpty()) {
            return Optional.empty();
        }
        List<String> sentences = new ArrayList<>();
        sentences.add("The " + String.join(", ", skipped) + (skipped.size() == 1 ? " package is" : " packages are")
                + " not built : the native executable is built in a container (quarkus.native.container-build=true), for"
                + " Linux, and a package is built on its operating system");
        if (!hostFormats.isEmpty()) {
            sentences.add("Build the native executable on this machine (quarkus.native.container-build=false) to package"
                    + " it as " + String.join(", ", hostFormats));
        }
        if (!osFormats.isEmpty()) {
            sentences.add("Build it on Linux to package it as " + String.join(", ", osFormats));
        }
        return Optional.of(String.join(". ", sentences));
    }

    /**
     * What the packages of a build share.
     *
     * @param applicationName {@code quarkus.application.name}
     * @param applicationVersion {@code quarkus.application.version}
     * @param groupId the group ID of the project
     * @param projectDirectory the directory of the relative paths of the configuration
     * @param buildDirectory the build directory ({@code target})
     * @param arch reads the architecture of the application, when a packager asks for it : the one of its native
     *        executable ({@link Arch#executable}), or of the build in JVM mode ({@link Arch#host})
     */
    static PackagingContext context(DesktopPackagingConfig config, String applicationName, String applicationVersion,
            String groupId, Path projectDirectory, Path buildDirectory, TargetOs os, Supplier<Arch> arch)
            throws IOException {
        AppInfo app = AppInfo.of(config, applicationName, applicationVersion, groupId, projectDirectory);
        return new PackagingContext(config, app, os, arch, new Tools(config.tools(), projectDirectory),
                IconSet.load(config.icon().map(String::trim).filter(value -> !value.isEmpty()), projectDirectory),
                projectDirectory, buildDirectory.resolve(config.outputDirectory()).toAbsolutePath().normalize());
    }

    /**
     * Checks what the packages need, before the application image and the first package are built, so that nothing is
     * built when one of them cannot be : the name of the application, which names the files of the image and of the
     * packages ({@link AppInfo#checkName}), the content directory, what signs the application image (with the
     * certificate file on Windows), and for each format its tools and what it derives from the configuration (see
     * {@link Packager#check}).
     *
     * @throws PackagingException when a package cannot be built
     */
    static void check(PackagingContext context, List<PackageFormat> formats) {
        AppInfo.checkName(context.app().name(), context.config().windows().installDirectory(), context.os());
        contentDirectory(context);
        if (context.os() == TargetOs.WINDOWS) {
            WindowsSdk.checkSignature(context);
            certificateFile(context);
        } else if (context.os() == TargetOs.MACOS) {
            MacBundle.checkSignature(context);
        }
        for (PackageFormat format : formats) {
            if (format != PackageFormat.DIRECTORY) {
                packager(format).check(context);
            }
        }
    }

    /**
     * The content directory of the configuration ({@code quarkus.desktop.packaging.content-directory}), which the
     * application image copies, if any.
     *
     * @throws PackagingException when it is not a valid path, or not a directory
     */
    static Optional<Path> contentDirectory(PackagingContext context) {
        Optional<Path> directory = configuredPath("quarkus.desktop.packaging.content-directory",
                context.config().contentDirectory(), context.projectDirectory());
        if (directory.isPresent() && !Files.isDirectory(directory.get())) {
            throw new PackagingException("quarkus.desktop.packaging.content-directory : " + directory.get()
                    + " is not a directory");
        }
        return directory;
    }

    /**
     * The certificate file of the configuration that signs the Windows executables and packages
     * ({@code quarkus.desktop.packaging.windows.sign.certificate-file}), if any.
     *
     * @throws PackagingException when it is not a valid path, or not a file
     */
    static Optional<Path> certificateFile(PackagingContext context) {
        Optional<Path> file = configuredPath("quarkus.desktop.packaging.windows.sign.certificate-file",
                context.config().windows().sign().certificateFile(), context.projectDirectory());
        if (file.isPresent() && !Files.isRegularFile(file.get())) {
            throw new PackagingException("quarkus.desktop.packaging.windows.sign.certificate-file : " + file.get()
                    + " not found");
        }
        return file;
    }

    /**
     * The absolute path of a path of the configuration, relative to the directory of the project.
     *
     * @param property the property of the path
     * @throws PackagingException when the value is not a valid path
     */
    private static Optional<Path> configuredPath(String property, Optional<String> value, Path projectDirectory) {
        try {
            return value.map(projectDirectory::resolve).map(Path::toAbsolutePath);
        } catch (InvalidPathException e) {
            throw new PackagingException(property + "=\"" + value.get() + "\" : not a valid path (" + e.getReason()
                    + ")");
        }
    }

    /**
     * Builds the packages, one after the other.
     *
     * @return the packages : the application image for the {@code directory} format
     */
    static List<Path> build(PackagingContext context, ApplicationImage image, List<PackageFormat> formats)
            throws IOException {
        List<Path> packages = new ArrayList<>();
        if (context.os() == TargetOs.WINDOWS) {
            WindowsSdk.signImage(context, image);
        }
        for (PackageFormat format : formats) {
            Path built = format == PackageFormat.DIRECTORY ? image.root() : packager(format).build(context, image);
            LOGGER.infof("Desktop package (%s) : %s", format.name().toLowerCase(Locale.ROOT), built);
            packages.add(built);
        }
        return packages;
    }

    private static Packager packager(PackageFormat format) {
        return switch (format) {
            case MSI -> new MsiPackager();
            case EXE -> new InnoSetupPackager();
            case MSIX -> new MsixPackager();
            case DMG -> new DmgPackager();
            case PKG -> new PkgPackager();
            case DEB -> new DebPackager();
            case RPM -> new RpmPackager();
            case APPIMAGE -> new AppImagePackager();
            case FLATPAK -> new FlatpakPackager();
            case SNAP -> new SnapPackager();
            case DIRECTORY -> throw new IllegalArgumentException("The application image has no packager");
        };
    }
}
