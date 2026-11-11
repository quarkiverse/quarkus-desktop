package io.quarkiverse.desktop.packaging.deployment.macos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * A macOS disk image ({@code .dmg}), built with {@code hdiutil} : the application bundle and a link to the
 * {@code Applications} folder, to drag the application to. Signed with the identity of the application bundle, and
 * notarized when the configuration has the credentials of the notary service.
 */
public final class DmgPackager implements Packager {

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path hdiutil = context.tools().require("hdiutil", "hdiutil is part of macOS", Path.of("/usr/bin/hdiutil"));
        AppInfo app = context.app();
        Path work = context.workDirectory("dmg");
        Path volume = work.resolve(app.fileName());
        MacBundle.copy(context, image.root(), volume.resolve(image.root().getFileName().toString()));
        Files.createSymbolicLink(volume.resolve("Applications"), Path.of("/Applications"));

        Path dmg = context.output(app.fileName() + "-" + app.version() + ".dmg");
        // a compressed image, of a file system that every version of macOS mounts
        Tools.exec(List.of(hdiutil.toString(), "create", "-volname", app.name(), "-srcfolder", volume.toString(), "-ov",
                "-format", "UDZO", "-fs", "HFS+", dmg.toString()));
        if (MacBundle.hasIdentity(context)) {
            MacBundle.codesign(context, dmg, null);
        }
        MacBundle.notarize(context, dmg);
        return dmg;
    }
}
