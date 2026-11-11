package io.quarkiverse.desktop.packaging.deployment;

import java.util.Locale;

/**
 * The architecture of the packaged application : the one of the build (no cross compilation), with the name that each
 * packaging tool gives it.
 */
public enum Arch {

    X64("x64", "amd64", "x86_64"),
    ARM64("arm64", "arm64", "aarch64");

    private final String windows;

    private final String debian;

    private final String gnu;

    Arch(String windows, String debian, String gnu) {
        this.windows = windows;
        this.debian = debian;
        this.gnu = gnu;
    }

    /**
     * The architecture of the JVM of the build.
     */
    public static Arch host() {
        return of(System.getProperty("os.arch", ""));
    }

    static Arch of(String osArch) {
        String arch = osArch.toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64") ? ARM64 : X64;
    }

    /**
     * {@code x64} or {@code arm64} : WiX, MSIX, Inno Setup.
     */
    public String windows() {
        return windows;
    }

    /**
     * {@code amd64} or {@code arm64} : Debian packages, snaps.
     */
    public String debian() {
        return debian;
    }

    /**
     * {@code x86_64} or {@code aarch64} : RPM packages, AppImage, Flatpak.
     */
    public String gnu() {
        return gnu;
    }
}
