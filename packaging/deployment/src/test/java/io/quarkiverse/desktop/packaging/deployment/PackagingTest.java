package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The checks of the packages, before the application image and the first package are built : the tools of the
 * formats, what they derive from the configuration, and what signs the application image.
 */
class PackagingTest {

    /**
     * The tools of the formats and of the signatures.
     */
    static final List<String> TOOLS = List.of("wix", "iscc", "makeappx", "signtool", "hdiutil", "pkgbuild",
            "productbuild", "PlistBuddy", "ditto", "codesign", "xcrun", "dpkg-deb", "rpmbuild", "appimagetool", "flatpak",
            "snapcraft");

    /**
     * The context of a project whose tools are a file of the project, with the given properties.
     */
    static PackagingContext context(Path project, TargetOs os, Map<String, String> properties) throws IOException {
        Files.writeString(project.resolve("tool"), "tool");
        Map<String, String> configuration = new HashMap<>();
        TOOLS.forEach(tool -> configuration.put("tools." + tool, "tool"));
        configuration.putAll(properties);
        return ApplicationImageTest.context(project, os, configuration);
    }

    /**
     * Checks that the check of a format fails with a configuration, with a message that names a property.
     */
    static void assertCheckFails(Path project, TargetOs os, PackageFormat format, Map<String, String> properties,
            String property) throws IOException {
        PackagingContext context = context(project, os, properties);
        PackagingException e = assertThrows(PackagingException.class, () -> Packaging.check(context, List.of(format)),
                format + " " + properties);
        assertTrue(e.getMessage().contains(property), e.getMessage());
    }

    /**
     * Checks that a production build failed in a build step of the extension, on the tool of the format of the
     * operating system ({@code msi}, {@code dmg} or {@code deb}), whose configured path is missing.
     *
     * @param step the method of the build step
     */
    static void assertBuildFailed(Throwable e, String step) {
        StringBuilder messages = new StringBuilder();
        Throwable check = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t).append('\n');
            // an exception of the class loader of the build
            if (t.getClass().getName().equals(PackagingException.class.getName())) {
                check = t;
            }
        }
        assertTrue(messages.indexOf(DesktopPackagingProcessor.class.getName() + "#" + step + " threw") >= 0,
                messages.toString());
        assertNotNull(check, messages.toString());
        String tool = switch (TargetOs.host()) {
            case WINDOWS -> "wix";
            case MACOS -> "hdiutil";
            case LINUX -> "dpkg-deb";
        };
        assertTrue(check.getMessage().startsWith("quarkus.desktop.packaging.tools." + tool + " : "), check.getMessage());
    }

    /**
     * Every format is checked before the first package is built : the application ID of Flatpak fails the build
     * before the Debian package, and nothing is built.
     */
    @Test
    void theFormatsAreCheckedBeforeTheFirstPackage(@TempDir Path project) throws IOException {
        PackagingContext context = context(project, TargetOs.LINUX, Map.of("identifier", "org.my-acme.Editor"));
        PackagingException e = assertThrows(PackagingException.class,
                () -> Packaging.check(context, List.of(PackageFormat.DEB, PackageFormat.FLATPAK)));
        assertTrue(e.getMessage().contains("is not a Flatpak application ID"), e.getMessage());
        assertFalse(Files.exists(context.outputDirectory()));
        // the application image has nothing to check
        Packaging.check(context, List.of(PackageFormat.DIRECTORY));
    }

    /**
     * A tool that is missing fails the check of its format.
     */
    @Test
    void aMissingToolFailsTheCheck(@TempDir Path project) throws IOException {
        PackagingContext context = context(project, TargetOs.LINUX, Map.of("tools.rpmbuild", "missing/rpmbuild"));
        Packaging.check(context, List.of(PackageFormat.DEB));
        PackagingException e = assertThrows(PackagingException.class,
                () -> Packaging.check(context, List.of(PackageFormat.DEB, PackageFormat.RPM)));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.tools.rpmbuild : "), e.getMessage());
    }

    /**
     * What each format derives from the configuration, and refuses : its names, its version, its upgrade code, its
     * installation directory, its Start menu group, its files, its notarization.
     */
    @Test
    void whatTheFormatsDeriveFromTheConfiguration(@TempDir Path project) throws IOException {
        // a configuration that every format builds
        Packaging.check(context(project, TargetOs.WINDOWS, Map.of()),
                List.of(PackageFormat.MSI, PackageFormat.EXE, PackageFormat.MSIX));
        Packaging.check(context(project, TargetOs.MACOS, Map.of()), List.of(PackageFormat.DMG, PackageFormat.PKG));
        Packaging.check(context(project, TargetOs.LINUX, Map.of()), List.of(PackageFormat.DEB, PackageFormat.RPM,
                PackageFormat.APPIMAGE, PackageFormat.FLATPAK, PackageFormat.SNAP));

        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.MSI, Map.of("version", "2025.1.0"),
                "quarkus.desktop.packaging.version=2025.1.0 :");
        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.MSI, Map.of("windows.install-directory", "/"),
                "quarkus.desktop.packaging.windows.install-directory=/ :");
        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.MSI, Map.of("windows.start-menu-group", "/"),
                "quarkus.desktop.packaging.windows.start-menu-group=/ :");
        // the Start menu group of a package without a shortcut in the Start menu
        Packaging.check(context(project, TargetOs.WINDOWS, Map.of("windows.start-menu", "false",
                "windows.start-menu-group", "/")), List.of(PackageFormat.MSI));
        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.EXE, Map.of("windows.upgrade-code", "acme"),
                "quarkus.desktop.packaging.windows.upgrade-code=acme :");
        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.MSIX, Map.of("windows.msix.identity-name", "a b"),
                "quarkus.desktop.packaging.windows.msix.identity-name=a b :");
        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.MSIX, Map.of("windows.msix.min-version", "10.0"),
                "quarkus.desktop.packaging.windows.msix.min-version=10.0 :");
        assertCheckFails(project, TargetOs.WINDOWS, PackageFormat.MSIX, Map.of("windows.msix.capabilities", "runFulTrust"),
                "quarkus.desktop.packaging.windows.msix.capabilities :");
        for (PackageFormat format : List.of(PackageFormat.DMG, PackageFormat.PKG)) {
            assertCheckFails(project, TargetOs.MACOS, format, Map.of("macos.notarize.keychain-profile", "acme"),
                    "quarkus.desktop.packaging.macos.notarize.keychain-profile is set");
        }
        for (PackageFormat format : List.of(PackageFormat.DEB, PackageFormat.RPM)) {
            assertCheckFails(project, TargetOs.LINUX, format, Map.of("linux.install-directory", "/opt/Acme Corp"),
                    "quarkus.desktop.packaging.linux.install-directory=/opt/Acme Corp :");
        }
        assertCheckFails(project, TargetOs.LINUX, PackageFormat.APPIMAGE,
                Map.of("linux.appimage.runtime-file", "runtime-x86_64"),
                "quarkus.desktop.packaging.linux.appimage.runtime-file :");
        assertCheckFails(project, TargetOs.LINUX, PackageFormat.SNAP, Map.of("linux.package-name", "acme.editor"),
                "quarkus.desktop.packaging.linux.package-name=acme.editor is not the name of a snap");
        assertCheckFails(project, TargetOs.LINUX, PackageFormat.SNAP, Map.of("linux.snap.confinement", "classic"),
                "quarkus.desktop.packaging.linux.snap.confinement=classic :");
    }

    /**
     * The tools of the macOS packages that are part of macOS are checked too : {@code ditto} copies the bundle into
     * both, {@code PlistBuddy} makes the component of the installer package not relocatable, and {@code xcrun}
     * notarizes them, when the configuration has the keychain profile of the notary service.
     */
    @Test
    void theToolsOfTheMacPackages(@TempDir Path project) throws IOException {
        List<PackageFormat> formats = List.of(PackageFormat.DMG, PackageFormat.PKG);
        for (PackageFormat format : formats) {
            assertCheckFails(project, TargetOs.MACOS, format, Map.of("tools.ditto", "missing/ditto"),
                    "quarkus.desktop.packaging.tools.ditto : ");
        }
        Map<String, String> missingPlistBuddy = Map.of("tools.PlistBuddy", "missing/PlistBuddy");
        assertCheckFails(project, TargetOs.MACOS, PackageFormat.PKG, missingPlistBuddy,
                "quarkus.desktop.packaging.tools.PlistBuddy : ");
        Packaging.check(context(project, TargetOs.MACOS, missingPlistBuddy), List.of(PackageFormat.DMG));

        // xcrun, for the packages that are notarized only
        Packaging.check(context(project, TargetOs.MACOS, Map.of("tools.xcrun", "missing/xcrun")), formats);
        Map<String, String> notarized = new HashMap<>(Map.of("macos.sign.identity", "Developer ID Application: Acme",
                "macos.sign.installer-identity", "Developer ID Installer: Acme", "macos.notarize.keychain-profile",
                "acme"));
        Packaging.check(context(project, TargetOs.MACOS, notarized), formats);
        notarized.put("tools.xcrun", "missing/xcrun");
        for (PackageFormat format : formats) {
            assertCheckFails(project, TargetOs.MACOS, format, notarized, "quarkus.desktop.packaging.tools.xcrun : ");
        }
    }

    /**
     * What signs the application image is checked whatever the formats, when it signs : {@code signtool} on Windows,
     * {@code codesign} and the entitlements file on macOS.
     */
    @Test
    void whatSignsTheApplicationImage(@TempDir Path project) throws IOException {
        List<PackageFormat> directory = List.of(PackageFormat.DIRECTORY);
        Map<String, String> missingSigntool = Map.of("tools.signtool", "missing/signtool.exe");
        Packaging.check(context(project, TargetOs.WINDOWS, missingSigntool), directory);
        Map<String, String> signed = new HashMap<>(missingSigntool);
        signed.put("windows.sign.certificate-file", "acme.pfx");
        PackagingException signtool = assertThrows(PackagingException.class,
                () -> Packaging.check(context(project, TargetOs.WINDOWS, signed), directory));
        assertTrue(signtool.getMessage().startsWith("quarkus.desktop.packaging.tools.signtool : "), signtool.getMessage());

        PackagingException entitlements = assertThrows(PackagingException.class, () -> Packaging.check(
                context(project, TargetOs.MACOS, Map.of("macos.sign.entitlements", "missing.plist")), directory));
        assertTrue(entitlements.getMessage().startsWith("quarkus.desktop.packaging.macos.sign.entitlements : "),
                entitlements.getMessage());
        Files.writeString(project.resolve("entitlements.plist"), "<plist/>");
        Packaging.check(context(project, TargetOs.MACOS, Map.of("macos.sign.entitlements", "entitlements.plist")),
                directory);
        // a bundle that is not signed
        Packaging.check(context(project, TargetOs.MACOS, Map.of("macos.sign.enabled", "false", "tools.codesign",
                "missing/codesign", "macos.sign.entitlements", "missing.plist")), directory);
    }
}
