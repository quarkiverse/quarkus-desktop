package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * An AppImage, built with {@code appimagetool} from an application directory ({@code AppDir}) : the application image,
 * an {@code AppRun} script that starts its launcher, its desktop entry and its icon. One executable file that runs on
 * most distributions, without installation : it has the application image, not the libraries of the system that the
 * application uses.
 */
public final class AppImagePackager implements Packager {

    /**
     * Checks {@code appimagetool} and the runtime file of the configuration.
     */
    @Override
    public void check(PackagingContext context) {
        appimagetool(context);
        runtimeFile(context);
    }

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        String arch = context.arch().gnu();
        Path appimagetool = appimagetool(context);
        Optional<Path> runtimeFile = runtimeFile(context);
        AppInfo app = context.app();
        String name = app.packageName();
        Path work = context.workDirectory("appimage");
        Path appDir = work.resolve(app.fileName() + ".AppDir");
        FileTrees.copy(image.root(), appDir.resolve("usr/lib").resolve(name));
        // the entry point of the AppImage
        Path appRun = FileTrees.write(appDir.resolve("AppRun"), appRun(name, image.launcher()));
        FileTrees.executable(appRun);
        Files.createDirectories(appDir.resolve("usr/bin"));
        Files.createSymbolicLink(appDir.resolve("usr/bin").resolve(name), Path.of("../lib/" + name + "/" + image.launcher()));
        // the desktop entry and the icon of the AppImage are at its root
        String desktopEntry = LinuxDesktop.desktopEntry(app, context.config().linux(), name, name);
        FileTrees.write(appDir.resolve(name + ".desktop"), desktopEntry);
        FileTrees.write(appDir.resolve("usr/share/applications").resolve(name + ".desktop"), desktopEntry);
        context.icons().writePng(appDir.resolve(name + ".png"), 256);
        Files.createSymbolicLink(appDir.resolve(".DirIcon"), Path.of(name + ".png"));
        LinuxDesktop.icons(context.icons(), appDir.resolve("usr/share/icons/hicolor"), name, 256);
        FileTrees.normalizePermissions(appDir);

        Path appImage = context.output(app.fileName() + "-" + app.version() + "-" + arch + ".AppImage");
        Path tmp = Files.createDirectories(work.resolve("tmp"));
        Tools.exec(work, environment(arch, app.version(), tmp), command(appimagetool, runtimeFile, appDir, appImage));
        FileTrees.executable(appImage);
        return appImage;
    }

    /**
     * The {@code appimagetool} of the machine : {@code appimagetool}, or the AppImage of the architecture of the
     * application ({@code appimagetool-x86_64.AppImage}).
     */
    private static Path appimagetool(PackagingContext context) {
        return context.tools().find("appimagetool")
                .or(() -> context.tools().find("appimagetool-" + context.arch().gnu() + ".AppImage"))
                .orElseThrow(() -> new PackagingException("appimagetool not found : download appimagetool-"
                        + context.arch().gnu() + ".AppImage from https://github.com/AppImage/appimagetool/releases, make"
                        + " it executable and put it on the PATH as appimagetool (or set"
                        + " quarkus.desktop.packaging.tools.appimagetool to its path)"));
    }

    /**
     * The runtime of the AppImage of the configuration ({@code quarkus.desktop.packaging.linux.appimage.runtime-file}),
     * if any.
     *
     * @throws PackagingException when the file does not exist
     */
    private static Optional<Path> runtimeFile(PackagingContext context) {
        Optional<Path> runtimeFile = context.config().linux().appimage().runtimeFile()
                .map(context.projectDirectory()::resolve).map(Path::toAbsolutePath);
        if (runtimeFile.isPresent() && !Files.isRegularFile(runtimeFile.get())) {
            throw new PackagingException("quarkus.desktop.packaging.linux.appimage.runtime-file : " + runtimeFile.get()
                    + " not found");
        }
        return runtimeFile;
    }

    /**
     * The environment of {@code appimagetool} : the architecture and the version of the AppImage (rather than a
     * {@code VERSION} variable of the build), and its extraction where FUSE is missing (containers), as it is an
     * AppImage itself. It extracts itself in a temporary directory of the build : in {@code /tmp}, two builds would
     * share the directory of the same {@code appimagetool}, which the first one to end deletes.
     *
     * @param tmp the temporary directory of {@code appimagetool}
     */
    static Map<String, String> environment(String arch, String version, Path tmp) {
        return Map.of("ARCH", arch, "VERSION", version, "APPIMAGE_EXTRACT_AND_RUN", "1", "TMPDIR", tmp.toString());
    }

    /**
     * The command of {@code appimagetool}.
     *
     * @param runtimeFile the runtime of the AppImage, or empty for {@code appimagetool} to download the latest one
     */
    static List<String> command(Path appimagetool, Optional<Path> runtimeFile, Path appDir, Path appImage) {
        List<String> command = new ArrayList<>(List.of(appimagetool.toString(), "--no-appstream"));
        runtimeFile.ifPresent(runtime -> command.addAll(List.of("--runtime-file", runtime.toString())));
        command.add(appDir.toString());
        command.add(appImage.toString());
        return command;
    }

    /**
     * The entry point of the AppImage : starts the launcher of the application image, wherever the AppImage is
     * mounted.
     */
    static String appRun(String name, String launcher) {
        return "#!/bin/sh\n"
                + "HERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\n"
                + "exec \"$HERE/usr/lib/" + name + "/" + launcher + "\" \"$@\"\n";
    }
}
