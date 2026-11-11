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
     *
     * @throws PackagingException for an architecture other than x64 and arm64
     */
    public static Arch host() {
        return of(System.getProperty("os.arch", ""));
    }

    /**
     * The architecture of a value of {@code os.arch}.
     *
     * @throws PackagingException for an architecture other than x64 and arm64
     */
    static Arch of(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> X64;
            case "aarch64", "arm64" -> ARM64;
            default -> throw new PackagingException("The packages are built for x64 and arm64, not for " + osArch
                    + " : build them on an x64 or arm64 machine, or keep only the directory format in"
                    + " quarkus.desktop.packaging.formats");
        };
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
