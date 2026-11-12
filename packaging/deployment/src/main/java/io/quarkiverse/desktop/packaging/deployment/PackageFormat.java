package io.quarkiverse.desktop.packaging.deployment;

/**
 * The formats of the packages of an application ({@code quarkus.desktop.packaging.formats}).
 */
public enum PackageFormat {

    /**
     * The application image : the directory of the application (an application bundle on macOS), which the other
     * formats install. Built on every operating system.
     */
    DIRECTORY(null, "directory"),

    /**
     * A Windows Installer package, built with the WiX Toolset.
     */
    MSI(TargetOs.WINDOWS, "msi"),

    /**
     * A Windows setup program, built with Inno Setup.
     */
    EXE(TargetOs.WINDOWS, "exe"),

    /**
     * An MSIX package, built with {@code makeappx} of the Windows SDK.
     */
    MSIX(TargetOs.WINDOWS, "msix"),

    /**
     * A macOS disk image, built with {@code hdiutil}.
     */
    DMG(TargetOs.MACOS, "dmg"),

    /**
     * A macOS installer package, built with {@code pkgbuild} and {@code productbuild}.
     */
    PKG(TargetOs.MACOS, "pkg"),

    /**
     * A Debian package, built with {@code dpkg-deb}.
     */
    DEB(TargetOs.LINUX, "deb"),

    /**
     * An RPM package, built with {@code rpmbuild}.
     */
    RPM(TargetOs.LINUX, "rpm"),

    /**
     * An AppImage, built with {@code appimagetool}.
     */
    APPIMAGE(TargetOs.LINUX, "AppImage"),

    /**
     * A Flatpak bundle, built with {@code flatpak}.
     */
    FLATPAK(TargetOs.LINUX, "flatpak"),

    /**
     * A snap, built with {@code snapcraft}.
     */
    SNAP(TargetOs.LINUX, "snap");

    private final TargetOs os;

    private final String extension;

    PackageFormat(TargetOs os, String extension) {
        this.os = os;
        this.extension = extension;
    }

    /**
     * Whether the format is one of the given operating system.
     */
    public boolean supports(TargetOs target) {
        return os == null || os == target;
    }

    /**
     * The extension of the files of the format, without the dot.
     */
    public String extension() {
        return extension;
    }
}
