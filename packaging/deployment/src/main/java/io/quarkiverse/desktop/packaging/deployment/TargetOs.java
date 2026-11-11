package io.quarkiverse.desktop.packaging.deployment;

import io.smallrye.common.os.OS;

/**
 * The operating system of the packaged application : the one of the build, or Linux for the native executable of a
 * container build.
 */
public enum TargetOs {

    WINDOWS,
    MACOS,
    LINUX;

    /**
     * The operating system of the build.
     */
    public static TargetOs host() {
        return of(OS.current());
    }

    static TargetOs of(OS os) {
        return switch (os) {
            case WINDOWS -> WINDOWS;
            case MAC -> MACOS;
            default -> LINUX;
        };
    }

    /**
     * The value of {@code smallrye-common-os}, as the build items of the SPI name the operating systems.
     */
    OS os() {
        return switch (this) {
            case WINDOWS -> OS.WINDOWS;
            case MACOS -> OS.MAC;
            case LINUX -> OS.LINUX;
        };
    }
}
