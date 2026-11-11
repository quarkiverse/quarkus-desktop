package io.quarkiverse.desktop.packaging.deployment;

import java.nio.file.Path;

/**
 * The application image : the directory of the application as it is installed, which every package contains. On
 * macOS, it is the application bundle ({@code Name.app}).
 *
 * @param root the directory of the application
 * @param launcher the path of the executable that starts the application, relative to the root, with {@code /}
 *        separators : a native executable, or the launcher of {@code jpackage} in JVM mode
 * @param nativeExecutable whether the launcher is a native executable, rather than the launcher of a JVM
 */
public record ApplicationImage(Path root, String launcher, boolean nativeExecutable) {

    /**
     * The path of the launcher.
     */
    public Path launcherPath() {
        return root.resolve(launcher);
    }

    /**
     * The file name of the launcher.
     */
    public String launcherName() {
        return launcher.substring(launcher.lastIndexOf('/') + 1);
    }
}
