package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * A Flatpak bundle ({@code .flatpak}), built with the {@code flatpak} command : the application image in {@code /app},
 * with its command, its desktop entry, its icons and its AppStream metadata, exported to a local repository and
 * bundled as a single file. The application is already built : the build directory is written here, and
 * {@code flatpak-builder}, the SDK and the runtime are not needed on the machine of the build. The runtime is
 * installed with the bundle, from Flathub.
 */
public final class FlatpakPackager implements Packager {

    private static final String RUNTIME_REPOSITORY = "https://dl.flathub.org/repo/flathub.flatpakrepo";

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path flatpak = context.tools().require("flatpak", "install Flatpak (https://flatpak.org/setup)");
        AppInfo app = context.app();
        DesktopPackagingConfig.Flatpak config = context.config().linux().flatpak();
        String id = applicationId(app);
        String name = app.packageName();
        String arch = context.arch().gnu();
        Path work = context.workDirectory("flatpak");
        Path build = work.resolve("build");
        Path files = build.resolve("files");

        FileTrees.write(build.resolve("metadata"), metadata(id, config, context.arch()));
        Files.createDirectories(build.resolve("var"));
        FileTrees.copy(image.root(), files.resolve("lib").resolve(name));
        Path command = FileTrees.write(files.resolve("bin").resolve(name),
                "#!/bin/sh\nexec /app/lib/" + name + "/" + image.launcher() + " \"$@\"\n");
        FileTrees.executable(command);
        // the exported files are named after the application ID
        FileTrees.write(files.resolve("share/applications").resolve(id + ".desktop"),
                LinuxDesktop.desktopEntry(app, context.config().linux(), name, id));
        LinuxDesktop.icons(context.icons(), files.resolve("share/icons/hicolor"), id, 64, 128, 256, 512);
        FileTrees.write(files.resolve("share/metainfo").resolve(id + ".metainfo.xml"),
                LinuxDesktop.metainfo(app, id, id + ".desktop"));
        FileTrees.normalizePermissions(build);

        List<String> finish = new ArrayList<>(List.of(flatpak.toString(), "build-finish", "--command=" + name));
        finish.addAll(config.finishArgs());
        finish.add(build.toString());
        Tools.exec(work, Map.of(), finish);
        Path repository = work.resolve("repo");
        Tools.exec(work, Map.of(), List.of(flatpak.toString(), "build-export", "--arch=" + arch, repository.toString(),
                build.toString(), config.branch()));
        Path bundle = context.output(app.fileName() + "-" + app.version() + "-" + arch + ".flatpak");
        Tools.exec(work, Map.of(), List.of(flatpak.toString(), "build-bundle", "--arch=" + arch,
                "--runtime-repo=" + RUNTIME_REPOSITORY, repository.toString(), bundle.toString(), id, config.branch()));
        return bundle;
    }

    /**
     * The identifier of the application as a Flatpak application ID : at least three elements, and no dash but in the
     * last one.
     */
    static String applicationId(AppInfo app) {
        String id = app.identifier();
        String[] elements = id.split("\\.");
        boolean valid = elements.length >= 3 && id.length() <= 255;
        for (int i = 0; i < elements.length - 1; i++) {
            valid &= !elements[i].contains("-");
        }
        if (!valid) {
            throw new PackagingException("quarkus.desktop.packaging.identifier=" + id + " is not a Flatpak application"
                    + " ID : a reverse domain name of at least three elements, such as org.acme.Editor, with dashes"
                    + " in the last element only");
        }
        return id;
    }

    /**
     * The metadata of the build directory : the application and its runtime. {@code flatpak build-finish} adds the
     * command and the permissions.
     */
    static String metadata(String id, DesktopPackagingConfig.Flatpak config, Arch arch) {
        String suffix = "/" + arch.gnu() + "/" + config.runtimeVersion();
        return "[Application]\n"
                + "name=" + id + "\n"
                + "runtime=" + config.runtime() + suffix + "\n"
                + "sdk=" + config.sdk() + suffix + "\n";
    }
}
