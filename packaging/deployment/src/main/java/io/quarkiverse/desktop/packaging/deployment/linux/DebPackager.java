package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * A Debian package ({@code .deb}), built with {@code dpkg-deb} : the application image in {@code /opt}, its command,
 * its desktop entry and its icons, and the dependencies on the packages of the shared libraries that the application
 * is linked with ({@code dpkg-shlibdeps}).
 */
public final class DebPackager implements Packager {

    private static final Logger LOGGER = Logger.getLogger(DebPackager.class);

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path dpkgDeb = context.tools().require("dpkg-deb", "install dpkg (it is part of Debian and Ubuntu)");
        AppInfo app = context.app();
        DesktopPackagingConfig.Linux config = context.config().linux();
        Path work = context.workDirectory("deb");
        Path root = work.resolve("root");
        LinuxDesktop.stage(context, image, root);
        if (app.licenseFile().isPresent()) {
            FileTrees.copy(app.licenseFile().get(), root.resolve("usr/share/doc").resolve(app.packageName())
                    .resolve("copyright"));
        }
        FileTrees.normalizePermissions(root);
        long installedSize = (FileTrees.size(root) + 1023) / 1024;

        Set<String> depends = new LinkedHashSet<>();
        if (config.deb().autoDepends()) {
            depends.addAll(sharedLibraryDepends(context, work, root.resolve(LinuxDesktop.installPath(context).substring(1))));
        }
        config.deb().depends().ifPresent(depends::addAll);
        FileTrees.write(root.resolve("DEBIAN/control"),
                control(app, config, context.arch(), installedSize, new ArrayList<>(depends)));
        FileTrees.normalizePermissions(root.resolve("DEBIAN"));

        Path deb = context.output(app.packageName() + "_" + app.linuxVersion() + "-" + config.release() + "_"
                + context.arch().debian() + ".deb");
        // the files belong to root, whoever builds the package
        Tools.exec(List.of(dpkgDeb.toString(), "--root-owner-group", "--build", root.toString(), deb.toString()));
        return deb;
    }

    /**
     * The control file of the package.
     *
     * @param installedSize the size of the installed files, in kibibytes
     */
    static String control(AppInfo app, DesktopPackagingConfig.Linux config, Arch arch, long installedSize,
            List<String> depends) {
        StringBuilder control = new StringBuilder();
        control.append("Package: ").append(app.packageName()).append('\n');
        control.append("Version: ").append(app.linuxVersion()).append('-').append(config.release()).append('\n');
        control.append("Section: ").append(config.deb().section()).append('\n');
        control.append("Priority: optional\n");
        control.append("Architecture: ").append(arch.debian()).append('\n');
        control.append("Maintainer: ").append(line(config.deb().maintainer().orElseGet(() -> maintainer(app))))
                .append('\n');
        control.append("Installed-Size: ").append(installedSize).append('\n');
        if (!depends.isEmpty()) {
            control.append("Depends: ").append(String.join(", ", depends)).append('\n');
        }
        app.homepage().ifPresent(homepage -> control.append("Homepage: ").append(line(homepage)).append('\n'));
        control.append("Description: ").append(line(app.description())).append('\n');
        return control.toString();
    }

    /**
     * A maintainer has a name and an email address.
     */
    private static String maintainer(AppInfo app) {
        return app.vendor() + " <" + app.packageName() + "@localhost>";
    }

    private static String line(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * The packages of the shared libraries that the executables and the libraries of the application are linked
     * with, as {@code dpkg-shlibdeps} finds them : with the minimum versions that their symbols need. The libraries of
     * the application itself are not dependencies.
     *
     * @param application the directory of the application in the tree of the package
     */
    private static List<String> sharedLibraryDepends(PackagingContext context, Path work, Path application)
            throws IOException {
        Optional<Path> shlibdeps = context.tools().find("dpkg-shlibdeps");
        if (shlibdeps.isEmpty()) {
            LOGGER.warn("dpkg-shlibdeps not found (the dpkg-dev package) : the Debian package does not depend on the"
                    + " packages of the shared libraries of the application. Install it, or list the dependencies in"
                    + " quarkus.desktop.packaging.linux.deb.depends");
            return List.of();
        }
        List<String> binaries = new ArrayList<>();
        Set<String> directories = new LinkedHashSet<>();
        for (Path file : FileTrees.files(application)) {
            Path path = application.resolve(file);
            if (FileTrees.isElf(path)) {
                binaries.add(path.toString());
                directories.add(path.getParent().toString());
            }
        }
        if (binaries.isEmpty()) {
            return List.of();
        }
        // dpkg-shlibdeps runs in the source tree of a package : a debian/control file is all it reads there
        Path source = work.resolve("shlibdeps");
        FileTrees.write(source.resolve("debian/control"), "Source: " + context.app().packageName() + "\n\nPackage: "
                + context.app().packageName() + "\nArchitecture: any\n");
        List<String> command = new ArrayList<>(List.of(shlibdeps.get().toString(), "-O", "--ignore-missing-info"));
        for (String directory : directories) {
            command.add("-l" + directory);
        }
        command.addAll(binaries);
        Tools.Result result = Tools.run(source, Map.of(), command);
        List<String> depends = result.succeeded() ? parseShlibDepends(result.output()) : null;
        if (depends == null) {
            LOGGER.warnf("dpkg-shlibdeps did not find the dependencies of the Debian package (list them in"
                    + " quarkus.desktop.packaging.linux.deb.depends) :%n%s", result.output().strip());
            return List.of();
        }
        return depends;
    }

    /**
     * The dependencies in the output of {@code dpkg-shlibdeps -O} : the {@code shlibs:Depends} variable, among the
     * warnings.
     *
     * @return {@code null} when the output has no such variable
     */
    static List<String> parseShlibDepends(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("shlibs:Depends=")) {
                List<String> depends = new ArrayList<>();
                for (String dependency : line.substring("shlibs:Depends=".length()).split(",")) {
                    if (!dependency.isBlank()) {
                        depends.add(dependency.trim());
                    }
                }
                return depends;
            }
        }
        return null;
    }
}
