package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void theDefaultsComeFromTheProject() {
        AppInfo app = TestConfig.app(TestConfig.defaults());
        assertEquals("Acme Editor", app.name());
        assertEquals("acme-editor", app.packageName());
        assertEquals("org.acme.AcmeEditor", app.identifier());
        assertEquals("1.2.3-SNAPSHOT", app.version());
        assertEquals("Unknown", app.vendor());
        assertEquals("Acme Editor", app.description());
        assertEquals("Acme-Editor", app.fileName());
    }

    @Test
    void theConfigurationOverridesTheProject() {
        AppInfo app = TestConfig.app(TestConfig.of(Map.of("name", "Editor", "version", "2.0", "identifier",
                "com.example.editor", "linux.package-name", "example-editor", "vendor", "Example")));
        assertEquals("Editor", app.name());
        assertEquals("example-editor", app.packageName());
        assertEquals("com.example.editor", app.identifier());
        assertEquals("2.0", app.version());
        assertEquals("Example", app.vendor());
    }

    @Test
    void anApplicationWithoutNameAndVersion() {
        AppInfo app = AppInfo.of(TestConfig.defaults(), AppInfo.UNSET, AppInfo.UNSET, "", Path.of("."));
        assertEquals("application", app.name());
        assertEquals("1.0.0", app.version());
        assertEquals("local.application.application", app.identifier());
    }

    @Test
    void packageNames() {
        assertEquals("acme-editor", AppInfo.packageName("Acme Editor"));
        assertEquals("acme-editor-2", AppInfo.packageName("  Acme_Editor (2)  "));
        assertEquals("application", AppInfo.packageName("É"));
    }

    @Test
    void identifiers() {
        assertEquals("org.acme.AcmeEditor", AppInfo.identifier("org.acme", "Acme Editor"));
        assertEquals("org.acme._3DViewer", AppInfo.identifier("org.acme", "3D Viewer"));
        assertEquals("local.application.Editor", AppInfo.identifier(null, "Editor"));
    }

    @Test
    void invalidNamesAreRejected() {
        assertThrows(PackagingException.class,
                () -> TestConfig.app(TestConfig.of(Map.of("linux.package-name", "Acme Editor"))));
        assertThrows(PackagingException.class, () -> TestConfig.app(TestConfig.of(Map.of("identifier", "editor"))));
        assertThrows(PackagingException.class, () -> TestConfig.app(TestConfig.of(Map.of("license-file", "missing.txt"))));
    }

    @Test
    void numericVersions() {
        assertEquals("1.2.3", app("1.2.3-SNAPSHOT").numericVersion(3));
        assertEquals("1.2.3.0", app("1.2.3-SNAPSHOT").numericVersion(4));
        assertEquals("1.2.0", app("1.2").numericVersion(3));
        assertEquals("1.2", app("1.2.3.4").numericVersion(2));
        assertEquals("999.0.0", app("999-SNAPSHOT").numericVersion(3));
        assertEquals("1.0.0", app("main").numericVersion(3));
        assertEquals("0.2.1", app("0.2.1").numericVersion(3));
        assertEquals("1.7.0", app("01.007").numericVersion(3));
    }

    @Test
    void linuxVersions() {
        assertEquals("1.2.3~SNAPSHOT", app("1.2.3-SNAPSHOT").linuxVersion());
        assertEquals("1.2.3", app("1.2.3").linuxVersion());
        assertEquals("0.main", app("main").linuxVersion());
        assertEquals("1.0.rc.1", app("1.0 rc/1").linuxVersion());
    }

    @Test
    void theGuidsAreStable() {
        AppInfo app = TestConfig.app();
        assertEquals(app.guid("upgrade"), TestConfig.app().guid("upgrade"));
        assertTrue(app.guid("upgrade").matches("[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}"), app.guid("upgrade"));
        assertTrue(!app.guid("upgrade").equals(app.guid("other")));
    }

    @Test
    void theFormatsOfTheOtherOperatingSystemsAreSkipped() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("formats", "directory,msi,dmg,deb,appimage,msi"));
        assertEquals(List.of(PackageFormat.DIRECTORY, PackageFormat.MSI), Packaging.formats(config, TargetOs.WINDOWS));
        assertEquals(List.of(PackageFormat.DIRECTORY, PackageFormat.DMG), Packaging.formats(config, TargetOs.MACOS));
        assertEquals(List.of(PackageFormat.DIRECTORY, PackageFormat.DEB, PackageFormat.APPIMAGE),
                Packaging.formats(config, TargetOs.LINUX));
        assertEquals(List.of(), Packaging.formats(TestConfig.defaults(), TargetOs.LINUX));
    }

    private static AppInfo app(String version) {
        return new AppInfo("Acme Editor", "acme-editor", "org.acme.AcmeEditor", version, "Acme", "Edits documents",
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
