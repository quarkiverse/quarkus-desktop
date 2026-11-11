package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * A snap, built with {@code snapcraft} from a generated project : a part that dumps the application image in the snap,
 * and an application with the {@code gnome} extension by default, which gives it the libraries and the environment of
 * a desktop application.
 */
public final class SnapPackager implements Packager {

    private static final Logger LOGGER = Logger.getLogger(SnapPackager.class);

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path snapcraft = context.tools().require("snapcraft", "install Snapcraft : sudo snap install snapcraft --classic");
        AppInfo app = context.app();
        String name = snapName(app);
        Path work = context.workDirectory("snap");
        Path project = work.resolve("project");
        writeProject(context, image, project, name);

        // snapcraft writes the snap in the directory of the project, also when it builds in a container
        List<String> command = new ArrayList<>(List.of(snapcraft.toString(), "pack"));
        context.config().linux().snap().buildArgs().ifPresent(command::addAll);
        try {
            Tools.exec(project, Map.of(), command);
            Path built;
            try (Stream<Path> files = Files.list(project)) {
                built = files.filter(file -> file.getFileName().toString().endsWith(".snap")).findFirst()
                        .orElseThrow(() -> new PackagingException("snapcraft did not write a snap in " + project));
            }
            Path snap = context.output(name + "_" + app.linuxVersion() + "_" + context.arch().debian() + ".snap");
            Files.move(built, snap, StandardCopyOption.REPLACE_EXISTING);
            return snap;
        } finally {
            if (managed(context.config().linux().snap().buildArgs())) {
                clean(snapcraft, project);
            }
        }
    }

    /**
     * Writes the project of Snapcraft : the part of the snap (the application image, in {@code payload/lib/<name>}),
     * {@code snap/snapcraft.yaml}, and the desktop entry and the icon of the snap in {@code snap/gui}, which Snapcraft
     * copies to {@code meta/gui} with their permissions. {@code snap pack} refuses the files of {@code meta} that are
     * not readable by everyone : the files of the project get the permissions of the files of a package, whatever the
     * umask of the build.
     *
     * @param project the directory of the project
     * @param name the name of the snap
     */
    static void writeProject(PackagingContext context, ApplicationImage image, Path project, String name)
            throws IOException {
        AppInfo app = context.app();
        String yaml = snapcraftYaml(app, context.config().linux().snap(), context.arch(), name, image.launcher());
        FileTrees.copy(image.root(), project.resolve("payload/lib").resolve(name));
        FileTrees.write(project.resolve("snap/snapcraft.yaml"), yaml);
        FileTrees.write(project.resolve("snap/gui").resolve(name + ".desktop"), LinuxDesktop.desktopEntry(app,
                context.config().linux(), name, "${SNAP}/meta/gui/" + name + ".png"));
        context.icons().writePng(project.resolve("snap/gui").resolve(name + ".png"), 256);
        FileTrees.normalizePermissions(project);
    }

    /**
     * Whether Snapcraft builds in an LXD container (its managed mode), rather than on the host
     * ({@code --destructive-mode}).
     */
    static boolean managed(Optional<List<String>> buildArgs) {
        return !buildArgs.orElse(List.of()).contains("--destructive-mode");
    }

    /**
     * Deletes the LXD container of the build. Snapcraft keeps it for the next build of the same project directory,
     * which it names after the inode of the directory : the project directory is created again at each build, and the
     * containers of the previous builds would remain, stopped, filling the storage of LXD.
     */
    private static void clean(Path snapcraft, Path project) {
        String failure;
        try {
            Tools.Result result = Tools.run(project, Map.of(), List.of(snapcraft.toString(), "clean"));
            if (result.succeeded()) {
                return;
            }
            failure = result.output().strip();
        } catch (PackagingException e) {
            failure = e.getMessage();
        }
        LOGGER.warnf("snapcraft clean failed : the LXD container of the build of the snap may remain (lxc list --project"
                + " snapcraft) :%n%s", failure);
    }

    /**
     * The name of the snap : the package name, which has to be made of lowercase letters, digits and dashes, with a
     * letter, in at most 40 characters.
     */
    static String snapName(AppInfo app) {
        String name = app.packageName();
        if (!name.matches("[a-z0-9-]{2,40}") || !name.matches(".*[a-z].*") || name.startsWith("-") || name.endsWith("-")
                || name.contains("--")) {
            throw new PackagingException("quarkus.desktop.packaging.linux.package-name=" + name + " is not the name of a"
                    + " snap : at most 40 lowercase letters, digits and single dashes, with a letter");
        }
        return name;
    }

    /**
     * The {@code snapcraft.yaml} file of the project.
     *
     * @param launcher the path of the executable in the application image, with {@code /} separators
     * @throws PackagingException for a {@code classic} snap with the {@code gnome} extension, which Snapcraft refuses
     */
    static String snapcraftYaml(AppInfo app, DesktopPackagingConfig.Snap config, Arch arch, String name, String launcher) {
        List<String> extensions = config.extensions().orElse(List.of());
        if ("classic".equals(config.confinement()) && extensions.contains("gnome")) {
            throw new PackagingException("quarkus.desktop.packaging.linux.snap.confinement=classic : the gnome extension"
                    + " of Snapcraft (quarkus.desktop.packaging.linux.snap.extensions) does not support the classic"
                    + " confinement : set quarkus.desktop.packaging.linux.snap.extensions to an empty value, or use the"
                    + " strict confinement");
        }
        // a title has 2 to 40 characters, a summary at most 78, a version at most 32
        String title = cut(app.name().replace('\n', ' ').trim(), 40);
        String summary = cut(app.description().replace('\n', ' ').trim(), 78);
        // a version ends with a letter, a digit, + or ~
        String version = cut(app.linuxVersion(), 32).replaceAll("[^A-Za-z0-9+~]+$", "");
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Generated by the Quarkus Desktop Packaging extension\n");
        // quoted, as the other texts : a name such as on, no or null is not a string for the YAML 1.1 of Snapcraft
        yaml.append("name: ").append(quoted(name)).append('\n');
        if (title.codePointCount(0, title.length()) >= 2) {
            yaml.append("title: ").append(quoted(title)).append('\n');
        }
        yaml.append("version: ").append(quoted(version)).append('\n');
        yaml.append("summary: ").append(quoted(summary)).append('\n');
        yaml.append("description: ").append(quoted(app.description())).append('\n');
        app.homepage().ifPresent(homepage -> yaml.append("website: ").append(quoted(homepage)).append('\n'));
        yaml.append("icon: snap/gui/").append(name).append(".png\n");
        yaml.append("base: ").append(config.base()).append('\n');
        yaml.append("grade: ").append(config.grade()).append('\n');
        yaml.append("confinement: ").append(config.confinement()).append('\n');
        yaml.append("platforms:\n");
        yaml.append("  ").append(arch.debian()).append(":\n");
        yaml.append('\n');
        yaml.append("apps:\n");
        yaml.append("  ").append(quoted(name)).append(":\n");
        yaml.append("    command: lib/").append(name).append('/').append(launcher).append('\n');
        if (!extensions.isEmpty()) {
            yaml.append("    extensions: [").append(String.join(", ", extensions)).append("]\n");
        }
        if (!config.plugs().isEmpty()) {
            yaml.append("    plugs:\n");
            for (String plug : config.plugs()) {
                yaml.append("      - ").append(plug).append('\n');
            }
        }
        yaml.append('\n');
        yaml.append("parts:\n");
        yaml.append("  application:\n");
        yaml.append("    plugin: dump\n");
        yaml.append("    source: payload\n");
        if (config.stagePackages().isPresent() && !config.stagePackages().get().isEmpty()) {
            yaml.append("    stage-packages:\n");
            for (String stagePackage : config.stagePackages().get()) {
                yaml.append("      - ").append(stagePackage).append('\n');
            }
        }
        return yaml.toString();
    }

    /**
     * The beginning of a text, of at most a number of characters (code points, as Snapcraft counts them), without
     * white space at its end.
     */
    private static String cut(String text, int length) {
        return text.codePointCount(0, text.length()) <= length ? text
                : text.substring(0, text.offsetByCodePoints(0, length)).stripTrailing();
    }

    /**
     * A text as a single quoted YAML scalar.
     */
    private static String quoted(String text) {
        return "'" + text.replace('\n', ' ').replace('\r', ' ').replace("'", "''") + "'";
    }
}
