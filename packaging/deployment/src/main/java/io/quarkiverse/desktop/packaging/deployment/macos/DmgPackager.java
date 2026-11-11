package io.quarkiverse.desktop.packaging.deployment.macos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * A macOS disk image ({@code .dmg}), built with {@code hdiutil} : the application bundle and a link to the
 * {@code Applications} folder, to drag the application to. Signed with the identity of the application bundle, and
 * notarized when the configuration has the credentials of the notary service.
 */
public final class DmgPackager implements Packager {

    private static final Logger LOGGER = Logger.getLogger(DmgPackager.class);

    /**
     * How many times {@code hdiutil} runs when a disk image is busy, as it is now and then on the hosted runners of
     * continuous integration.
     */
    static final int ATTEMPTS = 5;

    /**
     * The pause before the second attempt, in milliseconds : each next one is longer by as much.
     */
    static final long PAUSE = 3000;

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        MacBundle.checkNotarization(context);
        Path hdiutil = context.tools().require("hdiutil", "hdiutil is part of macOS", Path.of("/usr/bin/hdiutil"));
        AppInfo app = context.app();
        Path work = context.workDirectory("dmg");
        Path volume = work.resolve(app.fileName());
        MacBundle.copy(context, image.root(), volume.resolve(image.root().getFileName().toString()));
        Files.createSymbolicLink(volume.resolve("Applications"), Path.of("/Applications"));

        Path dmg = context.output(app.fileName() + "-" + app.version() + "-" + context.arch().macos() + ".dmg");
        // a compressed image, of a file system that every version of macOS mounts
        create(volume, List.of(hdiutil.toString(), "create", "-volname", app.name(), "-srcfolder", volume.toString(),
                "-ov", "-format", "UDZO", "-fs", "HFS+", dmg.toString()), command -> Tools.run(null, Map.of(), command),
                PAUSE);
        if (MacBundle.hasIdentity(context)) {
            MacBundle.codesign(context, dmg, null);
        }
        MacBundle.notarize(context, dmg);
        return dmg;
    }

    /**
     * Builds the disk image with {@code hdiutil create}, then deletes its staging folder, whether the image is built or
     * not : the link of the folder to {@code /Applications} would stay in the output directory otherwise, where a tool
     * that archives the directory and follows the links would take {@code /Applications} with it. The link itself is
     * deleted, not followed.
     *
     * @param volume the staging folder : the content of the disk image
     * @param run runs a command, whatever its exit code
     * @param pause the pause before the second attempt, in milliseconds
     */
    static void create(Path volume, List<String> command, Function<List<String>, Tools.Result> run, long pause)
            throws IOException {
        try {
            exec(command, run, pause);
        } finally {
            FileTrees.delete(volume);
        }
    }

    /**
     * Runs {@code hdiutil}, and again when it fails because a disk image is busy, up to {@link #ATTEMPTS} times.
     *
     * @param run runs a command, whatever its exit code
     * @param pause the pause before the second attempt, in milliseconds : each next one is longer by as much
     * @throws PackagingException when {@code hdiutil} fails for another reason, or at the last attempt
     */
    static Tools.Result exec(List<String> command, Function<List<String>, Tools.Result> run, long pause) {
        for (int attempt = 1;; attempt++) {
            Tools.Result result = run.apply(command);
            if (result.succeeded()) {
                return result;
            }
            if (attempt == ATTEMPTS || !resourceBusy(result.output())) {
                throw Tools.failure(command, result);
            }
            LOGGER.warnf("hdiutil : a disk image is busy, trying again (attempt %d of %d)", attempt + 1, ATTEMPTS);
            try {
                Thread.sleep(pause * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PackagingException("Interrupted while waiting to run hdiutil again", e);
            }
        }
    }

    /**
     * Whether {@code hdiutil} failed because a disk image was busy ({@code create failed - Resource busy}) : another
     * attempt may succeed.
     */
    static boolean resourceBusy(String output) {
        return output.contains("Resource busy");
    }
}
