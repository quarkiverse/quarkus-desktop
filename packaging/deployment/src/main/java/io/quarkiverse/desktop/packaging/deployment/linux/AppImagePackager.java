package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        String arch = context.arch().gnu();
        Path appimagetool = context.tools().find("appimagetool")
                .or(() -> context.tools().find("appimagetool-" + arch + ".AppImage"))
                .orElseThrow(() -> new PackagingException("appimagetool not found : download appimagetool-" + arch
                        + ".AppImage from https://github.com/AppImage/appimagetool/releases, make it executable and"
                        + " put it on the PATH as appimagetool (or set quarkus.desktop.packaging.tools.appimagetool to"
                        + " its path)"));
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
        // appimagetool is an AppImage itself : it extracts itself where FUSE is missing (containers)
        Tools.exec(work, Map.of("ARCH", arch, "APPIMAGE_EXTRACT_AND_RUN", "1"),
                List.of(appimagetool.toString(), "--no-appstream", appDir.toString(), appImage.toString()));
        FileTrees.executable(appImage);
        return appImage;
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
