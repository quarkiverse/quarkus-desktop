package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * An RPM package, built with {@code rpmbuild} from a generated spec file : the application image in {@code /opt},
 * its command, its desktop entry and its icons. {@code rpmbuild} finds the shared libraries that the application
 * requires ; the libraries of the application itself are neither required nor provided.
 */
public final class RpmPackager implements Packager {

    /**
     * Checks {@code rpmbuild} and the installation directory.
     */
    @Override
    public void check(PackagingContext context) {
        rpmbuild(context);
        LinuxDesktop.installPath(context);
    }

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path rpmbuild = rpmbuild(context);
        AppInfo app = context.app();
        Path work = context.workDirectory("rpm");
        Path root = work.resolve("root");
        LinuxDesktop.stage(context, image, root);
        if (app.licenseFile().isPresent()) {
            FileTrees.copy(app.licenseFile().get(), root.resolve("usr/share/licenses").resolve(app.packageName())
                    .resolve("LICENSE"));
        }
        FileTrees.normalizePermissions(root);
        String installPath = LinuxDesktop.installPath(context);

        Path spec = FileTrees.write(work.resolve(app.packageName() + ".spec"), spec(app, context.config().linux(), root,
                installPath, libraries(root.resolve(installPath.substring(1))), context.optionalLibraries()));
        Path top = work.resolve("rpmbuild");
        Tools.exec(work, Map.of(), command(rpmbuild, top, context.arch(), spec));

        Path built;
        try (Stream<Path> files = Files.walk(top.resolve("RPMS"))) {
            built = files.filter(file -> file.getFileName().toString().endsWith(".rpm")).findFirst()
                    .orElseThrow(() -> new PackagingException("rpmbuild did not write a package in " + top.resolve("RPMS")));
        }
        Path rpm = context.output(fileName(app, context.config().linux(), context.arch()));
        Files.move(built, rpm, StandardCopyOption.REPLACE_EXISTING);
        // the copy of the tree in the build root, which the %clean stage of rpmbuild no longer deletes
        FileTrees.delete(top);
        return rpm;
    }

    /**
     * The {@code rpmbuild} of the machine.
     */
    private static Path rpmbuild(PackagingContext context) {
        return context.tools().require("rpmbuild", "install the RPM build tools (dnf install rpm-build, or apt install rpm)");
    }

    static String fileName(AppInfo app, DesktopPackagingConfig.Linux config, Arch arch) {
        return app.packageName() + "-" + app.linuxVersion() + "-" + config.release() + "." + arch.gnu() + ".rpm";
    }

    /**
     * The names of the shared libraries of the application, which are not requirements of the package : their file
     * names, and the names of the links to them, such as the soname of a library whose file name has its full version
     * ({@code libfoo.so.1 -> libfoo.so.1.2.3}), which the other files of the application require. A link to a file
     * outside the application, or to nothing, is not a library of the application.
     *
     * @param application the directory of the application in the tree of the package
     */
    static List<String> libraries(Path application) throws IOException {
        Path real = application.toRealPath();
        TreeSet<String> libraries = new TreeSet<>();
        for (Path file : FileTrees.files(application)) {
            String name = file.getFileName().toString();
            Path path = application.resolve(file);
            if (name.contains(".so") && (FileTrees.isElf(path) || Files.isSymbolicLink(path) && linksToLibrary(path, real))) {
                libraries.add(name);
            }
        }
        return new ArrayList<>(libraries);
    }

    /**
     * Whether a symbolic link leads to a shared library of the application.
     *
     * @param application the real path of the directory of the application
     */
    private static boolean linksToLibrary(Path link, Path application) {
        try {
            Path target = link.toRealPath();
            return target.startsWith(application) && FileTrees.isElf(target);
        } catch (IOException e) {
            // a link to nothing
            return false;
        }
    }

    /**
     * The command that builds the package, without its {@code %clean} stage : the default one of {@code rpmbuild}
     * (before 4.20) removes the build root, under {@code target/packaging}, with a shell command that does not quote
     * its path. A {@code &}, {@code ;} or {@code |} in the path would make it remove another directory : the beginning
     * of the path. The build root is quoted in the command of {@code check-files}, which reports the files of the build
     * root that the package does not have : {@code rpmbuild} splits that command at white space, and where the path
     * has a space, {@code check-files} would search the beginning of the path (the project {@code editor}, next to
     * {@code editor (copy)}) and report its files, or search nothing.
     *
     * @param top the directory of the build of {@code rpmbuild} ({@code _topdir})
     */
    static List<String> command(Path rpmbuild, Path top, Arch arch, Path spec) {
        return List.of(rpmbuild.toString(), "-bb", "--noclean", "--define", "_topdir " + top, "--define",
                "__check_files %{_rpmconfigdir}/check-files \"%{buildroot}\"", "--target", arch.gnu(), spec.toString());
    }

    /**
     * The spec file of the package.
     *
     * @param root the tree of the files of the package
     * @param installPath the directory of the application ({@code /opt/acme-editor})
     * @param libraries the names of the shared libraries of the application (see {@link #libraries})
     * @param optionalLibraries the libraries of the application that its executable loads only in some cases (the GTK 4
     *        library of SWT), relative to its directory, with {@code /} separators : the package does not require the
     *        libraries that they are linked with
     */
    static String spec(AppInfo app, DesktopPackagingConfig.Linux config, Path root, String installPath,
            List<String> libraries, Set<String> optionalLibraries) {
        String name = app.packageName();
        StringBuilder spec = new StringBuilder();
        // the files are packaged as they are : not stripped, no debug package, no build ID links
        spec.append("%global __os_install_post %{nil}\n");
        spec.append("%global debug_package %{nil}\n");
        spec.append("%global _build_id_links none\n");
        spec.append("%global __provides_exclude_from ^").append(regex(installPath)).append("/.*$\n");
        if (!libraries.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String library : libraries) {
                names.append(names.length() == 0 ? "" : "|").append(regex(library));
            }
            spec.append("%global __requires_exclude ^(").append(names).append(")([(].*)?$\n");
        }
        if (!optionalLibraries.isEmpty()) {
            StringBuilder files = new StringBuilder();
            for (String library : new TreeSet<>(optionalLibraries)) {
                files.append(files.length() == 0 ? "" : "|").append(regex(library));
            }
            spec.append("%global __requires_exclude_from ^").append(regex(installPath)).append("/(").append(files)
                    .append(")$\n");
        }
        spec.append('\n');
        spec.append("Name: ").append(name).append('\n');
        spec.append("Version: ").append(app.linuxVersion()).append('\n');
        spec.append("Release: ").append(config.release()).append('\n');
        spec.append("Summary: ").append(line(app.description())).append('\n');
        spec.append("License: ").append(line(config.rpm().license())).append('\n');
        spec.append("Vendor: ").append(line(app.vendor())).append('\n');
        config.rpm().group().ifPresent(group -> spec.append("Group: ").append(line(group)).append('\n'));
        app.homepage().ifPresent(homepage -> spec.append("URL: ").append(line(homepage)).append('\n'));
        if (!config.rpm().autoRequires()) {
            spec.append("AutoReq: no\n");
        }
        config.rpm().requires().ifPresent(requires -> requires
                .forEach(requirement -> spec.append("Requires: ").append(line(requirement)).append('\n')));
        spec.append('\n');
        spec.append("%description\n").append(line(app.description())).append('\n');
        spec.append('\n');
        spec.append("%install\n");
        spec.append("mkdir -p \"%{buildroot}\"\n");
        spec.append("cp -a \"").append(root).append("/.\" \"%{buildroot}/\"\n");
        spec.append('\n');
        // a %clean stage that does nothing, also when rpmbuild runs the spec file without --noclean (see command) : an
        // empty one would not replace the default one
        spec.append("%clean\n");
        spec.append(":\n");
        spec.append('\n');
        spec.append("%files\n");
        spec.append("%defattr(-,root,root,-)\n");
        spec.append(installPath).append('\n');
        spec.append("/usr/bin/").append(name).append('\n');
        spec.append("/usr/share/applications/").append(name).append(".desktop\n");
        // the icons that LinuxDesktop.stage wrote, one by one : rpmbuild expands a glob in the build root, under the
        // directory of the project, and splits it where the path of that directory has a space
        for (int size : IconSet.LINUX_SIZES) {
            spec.append("/usr/share/icons/hicolor/").append(size).append('x').append(size).append("/apps/").append(name)
                    .append(".png\n");
        }
        if (app.licenseFile().isPresent()) {
            spec.append("%dir /usr/share/licenses/").append(name).append('\n');
            spec.append("%license /usr/share/licenses/").append(name).append("/LICENSE\n");
        }
        return spec.toString();
    }

    /**
     * A text of a spec file : on one line, and a percent sign starts a macro unless it is doubled.
     */
    private static String line(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replace("%", "%%").trim();
    }

    /**
     * A literal text in a regular expression of a spec file : the special characters in character classes, as the
     * macros of RPM process the backslashes.
     */
    private static String regex(String text) {
        StringBuilder regex = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Pattern.matches("[A-Za-z0-9_/-]", String.valueOf(c))) {
                regex.append(c);
            } else if (c == '%') {
                regex.append("%%");
            } else if (c == '^' || c == ']' || c == '\\') {
                regex.append('.');
            } else {
                regex.append('[').append(c).append(']');
            }
        }
        return regex.toString();
    }
}
