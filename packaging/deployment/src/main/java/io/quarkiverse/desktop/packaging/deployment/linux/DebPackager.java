package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * A package name of the 64-bit {@code time_t} transition : the former name with {@code t64} after its version
     * ({@code libasound2t64}), before the suffix of a variant ({@code libcurl3t64-gnutls}).
     */
    private static final Pattern TIME64 = Pattern.compile("([a-z0-9][a-z0-9.+-]*[0-9])t64(-[a-z0-9.+-]+)?");

    /**
     * Checks {@code dpkg-deb} and the installation directory.
     */
    @Override
    public void check(PackagingContext context) {
        dpkgDeb(context);
        LinuxDesktop.installPath(context);
    }

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path dpkgDeb = dpkgDeb(context);
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
        Tools.exec(buildCommand(dpkgDeb, root, deb));
        return deb;
    }

    /**
     * The {@code dpkg-deb} of the machine.
     */
    private static Path dpkgDeb(PackagingContext context) {
        return context.tools().require("dpkg-deb", "install dpkg (it is part of Debian and Ubuntu)");
    }

    /**
     * The command that builds the package : compressed with xz, which every {@code dpkg} reads (the default of
     * {@code dpkg-deb} is zstd on Ubuntu, which the {@code dpkg} of Debian 11 and older cannot read), with files that
     * belong to root, whoever builds the package.
     *
     * @param root the tree of the files of the package, with its {@code DEBIAN} directory
     */
    static List<String> buildCommand(Path dpkgDeb, Path root, Path deb) {
        return List.of(dpkgDeb.toString(), "-Zxz", "--root-owner-group", "--build", root.toString(), deb.toString());
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
     * the application itself are not dependencies, nor the libraries that the machine of the build does not have, nor
     * the ones of the optional libraries of the application ({@link PackagingContext#optionalLibraries}).
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
        List<Path> elfFiles = new ArrayList<>();
        Set<String> directories = new LinkedHashSet<>();
        for (Path file : FileTrees.files(application)) {
            Path path = application.resolve(file);
            if (FileTrees.isElf(path)) {
                elfFiles.add(file);
                directories.add(path.getParent().toString());
            }
        }
        List<String> binaries = binaries(application, elfFiles, context.optionalLibraries());
        if (binaries.isEmpty()) {
            return List.of();
        }
        // dpkg-shlibdeps runs in the source tree of a package : a debian/control file is all it reads there
        Path source = work.resolve("shlibdeps");
        FileTrees.write(source.resolve("debian/control"), "Source: " + context.app().packageName() + "\n\nPackage: "
                + context.app().packageName() + "\nArchitecture: any\n");
        List<String> options = new ArrayList<>(List.of(shlibdeps.get().toString(), "-O", "--ignore-missing-info"));
        for (String directory : directories) {
            options.add("-l" + directory);
        }
        // the output of the first run that failed : the run for all the binaries, which lists the missing libraries
        List<String> failure = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> depends = shlibDepends(binaries, files -> {
            List<String> command = new ArrayList<>(options);
            command.addAll(files);
            Tools.Result result = Tools.run(source, Map.of(), command);
            if (!result.succeeded()) {
                if (failure.isEmpty()) {
                    failure.add(result.output().strip());
                }
                return Optional.empty();
            }
            return Optional.of(parseShlibDepends(result.output()));
        }, missing);
        if (!missing.isEmpty()) {
            LOGGER.warnf("dpkg-shlibdeps did not find the libraries of %s on the machine of the build : the Debian package"
                    + " does not depend on their packages. Install them on the machine of the build, or list their"
                    + " packages in quarkus.desktop.packaging.linux.deb.depends :%n%s",
                    String.join(", ", missing.stream().map(file -> application.relativize(Path.of(file)).toString())
                            .toList()),
                    failure.get(0));
        }
        return depends;
    }

    /**
     * The binaries of the application whose libraries are dependencies of the package : its ELF files, but its optional
     * libraries, which its executable loads only in some cases (the GTK 4 library of SWT, which needs GTK 4).
     *
     * @param application the directory of the application in the tree of the package
     * @param elfFiles the ELF files of the application, relative to its directory
     * @param optionalLibraries the optional libraries of the application, relative to its directory, with {@code /}
     *        separators
     * @return the paths of the binaries
     */
    static List<String> binaries(Path application, List<Path> elfFiles, Set<String> optionalLibraries) {
        List<String> binaries = new ArrayList<>();
        for (Path file : elfFiles) {
            if (!optionalLibraries.contains(file.toString().replace('\\', '/'))) {
                binaries.add(application.resolve(file).toString());
            }
        }
        return binaries;
    }

    /**
     * The dependencies of the binaries of the application : one run of {@code dpkg-shlibdeps} for all of them. It
     * fails when a binary is linked with a library that the machine of the build does not have : then one run for each
     * binary, and one for the binaries whose run succeeded, which merges the versions of their dependencies. The other
     * binaries add no dependency.
     *
     * @param depends runs {@code dpkg-shlibdeps} on binaries : their dependencies, or empty when it fails
     * @param missing receives the binaries whose run failed
     */
    static List<String> shlibDepends(List<String> binaries, Function<List<String>, Optional<List<String>>> depends,
            List<String> missing) {
        Optional<List<String>> all = depends.apply(binaries);
        if (all.isPresent()) {
            return all.get();
        }
        if (binaries.size() == 1) {
            missing.addAll(binaries);
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Set<String> union = new LinkedHashSet<>();
        for (String binary : binaries) {
            Optional<List<String>> one = depends.apply(List.of(binary));
            if (one.isPresent()) {
                found.add(binary);
                union.addAll(one.get());
            } else {
                missing.add(binary);
            }
        }
        if (found.size() < 2) {
            return new ArrayList<>(union);
        }
        return depends.apply(found).orElseGet(() -> new ArrayList<>(union));
    }

    /**
     * The dependencies in the output of {@code dpkg-shlibdeps -O} : the {@code shlibs:Depends} variable, among the
     * warnings, with the former names of the packages of the 64-bit {@code time_t} transition (see
     * {@link #withFormerNames}).
     *
     * @return the dependencies, none when the output has no such variable : {@code dpkg-shlibdeps} writes none when the
     *         files are linked with no shared library (statically linked executables)
     */
    static List<String> parseShlibDepends(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("shlibs:Depends=")) {
                List<String> depends = new ArrayList<>();
                for (String dependency : line.substring("shlibs:Depends=".length()).split(",")) {
                    if (!dependency.isBlank()) {
                        depends.add(withFormerNames(dependency.trim()));
                    }
                }
                return depends;
            }
        }
        return List.of();
    }

    /**
     * A dependency with the former names of the packages that the transition to a 64-bit {@code time_t} of Ubuntu
     * 24.04 and Debian 13 renamed, as alternatives of the same version : {@code libasound2t64 (>= 1.0.16)} is
     * {@code libasound2t64 (>= 1.0.16) | libasound2 (>= 1.0.16)}, so that a package built there installs on the
     * releases before them, which have the former names. The transition only renamed the libraries of the 64-bit
     * architectures, the ones of the packages.
     */
    static String withFormerNames(String dependency) {
        String[] alternatives = dependency.split("\\|");
        Set<String> names = new HashSet<>();
        for (String alternative : alternatives) {
            names.add(packageName(alternative));
        }
        List<String> result = new ArrayList<>();
        for (String alternative : alternatives) {
            String name = packageName(alternative);
            result.add(alternative.trim());
            Matcher matcher = TIME64.matcher(name);
            if (matcher.matches()) {
                String former = matcher.group(1) + (matcher.group(2) == null ? "" : matcher.group(2));
                if (names.add(former)) {
                    result.add(former + alternative.trim().substring(name.length()));
                }
            }
        }
        return String.join(" | ", result);
    }

    /**
     * The package name of an alternative of a dependency, without its version.
     */
    private static String packageName(String alternative) {
        return alternative.trim().split("[\\s(]", 2)[0];
    }
}
