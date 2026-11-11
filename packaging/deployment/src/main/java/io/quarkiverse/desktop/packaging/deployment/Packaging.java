package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
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
        AppInfo.checkName(app.name(), config.windows().installDirectory(), os);
        return new PackagingContext(config, app, os, arch, new Tools(config.tools(), projectDirectory),
                IconSet.load(config.icon().map(String::trim).filter(value -> !value.isEmpty()), projectDirectory),
                projectDirectory, buildDirectory.resolve(config.outputDirectory()).toAbsolutePath().normalize());
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
