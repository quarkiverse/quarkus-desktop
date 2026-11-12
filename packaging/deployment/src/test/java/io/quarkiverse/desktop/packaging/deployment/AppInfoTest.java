package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * The white space around a value (a secret of the continuous integration that ends with a line break) is not part
     * of it, and a blank value is the default.
     */
    @Test
    void theValuesAreTrimmed(@TempDir Path project) throws IOException {
        AppInfo app = TestConfig.app(TestConfig.of(Map.of("identifier", "com.example.editor ", "linux.package-name",
                "example-editor\n")));
        assertEquals("com.example.editor", app.identifier());
        assertEquals("example-editor", app.packageName());
        assertEquals("org.acme.AcmeEditor", TestConfig.app(TestConfig.of(Map.of("identifier", "   "))).identifier());
        Files.writeString(project.resolve("LICENSE"), "license");
        AppInfo licensed = AppInfo.of(TestConfig.of(Map.of("license-file", "LICENSE ")), "Acme Editor", "1.0",
                "org.acme", project);
        assertEquals(Optional.of(project.resolve("LICENSE")), licensed.licenseFile());
        // not a path on any operating system
        PackagingException e = assertThrows(PackagingException.class, () -> AppInfo.of(TestConfig.of(Map.of(
                "license-file", "a\u0000b")), "Acme Editor", "1.0", "org.acme", project));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.license-file="), e.getMessage());

        BufferedImage icon = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(icon, "png", project.resolve("icon.png").toFile());
        assertEquals(48, Packaging.context(TestConfig.of(Map.of("icon", "icon.png ")), "Acme Editor", "1.0", "org.acme",
                project, project, TargetOs.LINUX, Arch::host).icons().sourceSize());
    }

    /**
     * The name of the configuration, which the extensions of the user interface toolkits get too : none when it is not
     * set.
     */
    @Test
    void theConfiguredName() {
        assertEquals(Optional.empty(), AppInfo.configuredName(TestConfig.defaults()));
        assertEquals(Optional.empty(), AppInfo.configuredName(TestConfig.of(Map.of("name", "  "))));
        assertEquals(Optional.of("Acme Editor"), AppInfo.configuredName(TestConfig.of(Map.of("name", " Acme Editor "))));
    }

    /**
     * The name that the application gives itself at run time comes from its toolkit, not from the configuration.
     */
    @Test
    void theNameThatTheApplicationGivesItself() {
        AppInfo app = TestConfig.app(TestConfig.defaults());
        assertEquals(Optional.empty(), app.applicationId());
        AppInfo named = app.withApplicationId(Optional.of("acme-editor"));
        assertEquals(Optional.of("acme-editor"), named.applicationId());
        assertEquals(app, named.withApplicationId(Optional.empty()));
        assertEquals(app.name(), named.name());
        assertEquals(app.identifier(), named.identifier());
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
        // in ASCII : the accents removed, the letters that do not decompose spelled in ASCII
        assertEquals("cafe-manager", AppInfo.packageName("Café Manager"));
        assertEquals("muller-tools", AppInfo.packageName("Müller Tools"));
        assertEquals("strasse", AppInfo.packageName("Straße"));
        assertEquals("istanbul-app", AppInfo.packageName("İstanbul App"));
        assertEquals("lodz", AppInfo.packageName("Łódź"));
        assertEquals("aero", AppInfo.packageName("Ærø"));
    }

    @Test
    void namesInAscii() {
        assertEquals("Acme Editeur", AppInfo.ascii("Acme Éditeur"));
        assertEquals("Wide file2", AppInfo.ascii("Ｗｉｄｅ ﬁle²"));
        assertEquals("Strasse STRASSE Thorsdottir", AppInfo.ascii("Straße STRAẞE Þórsdóttir"));
        assertEquals("Aesir Oeuvre Orsted", AppInfo.ascii("Æsir Œuvre Ørsted"));
        assertEquals("محرر", AppInfo.ascii("محرر"));
    }

    @Test
    void identifiers() {
        assertEquals("org.acme.AcmeEditor", AppInfo.identifier("org.acme", "Acme Editor"));
        assertEquals("org.acme.3DViewer", AppInfo.identifier("org.acme", "3D Viewer"));
        assertEquals("com.3m.Editor", AppInfo.identifier("com.3m", "Editor"));
        assertEquals("local.application.Editor", AppInfo.identifier(null, "Editor"));
        assertEquals("org.acme.AcmeEditeur", AppInfo.identifier("org.acme", "Acme Éditeur"));
        assertEquals("org.acme.Lodz", AppInfo.identifier("org.acme", "Łódź"));
        // an element that starts with a digit : macOS and MSIX accept it, Flatpak does not
        assertEquals("com.1password.editor", TestConfig.app(TestConfig.of(Map.of("identifier", "com.1password.editor")))
                .identifier());
    }

    /**
     * The names that the files, the packages and the identifier derive from a name with accents : not misspelled.
     */
    @Test
    void aNameWithAccents() {
        AppInfo app = TestConfig.app(TestConfig.of(Map.of("name", "Acme Éditeur")));
        assertEquals("Acme Éditeur", app.name());
        assertEquals("acme-editeur", app.packageName());
        assertEquals("org.acme.AcmeEditeur", app.identifier());
        assertEquals("Acme-Editeur", app.fileName());
    }

    /**
     * A name without letters or digits in ASCII : the derived names come from {@code quarkus.application.name}, so
     * that the applications of a vendor do not share their identifier, upgrade code and Linux package.
     */
    @Test
    void aNameInAnotherScript() {
        AppInfo editor = AppInfo.of(TestConfig.of(Map.of("name", "محرر النصوص")), "acme-editor", "1.0", "org.acme",
                Path.of("."));
        assertEquals("محرر النصوص", editor.name());
        assertEquals("acme-editor", editor.packageName());
        assertEquals("org.acme.acmeeditor", editor.identifier());
        assertEquals("acme-editor", editor.fileName());
        AppInfo player = AppInfo.of(TestConfig.of(Map.of("name", "مشغل الوسائط")), "acme-player", "1.0", "org.acme",
                Path.of("."));
        assertEquals("acme-player", player.packageName());
        assertEquals("org.acme.acmeplayer", player.identifier());
        assertNotEquals(editor.guid("upgrade"), player.guid("upgrade"));
        for (String name : List.of("编辑器", "Редактор текста")) {
            AppInfo app = AppInfo.of(TestConfig.of(Map.of("name", name)), "acme-editor", "1.0", "org.acme", Path.of("."));
            assertEquals("acme-editor", app.packageName(), name);
            assertEquals("org.acme.acmeeditor", app.identifier(), name);
        }
        // the configuration still wins
        AppInfo configured = AppInfo.of(TestConfig.of(Map.of("name", "محرر النصوص", "identifier", "org.acme.moharrir",
                "linux.package-name", "moharrir")), "acme-editor", "1.0", "org.acme", Path.of("."));
        assertEquals("moharrir", configured.packageName());
        assertEquals("org.acme.moharrir", configured.identifier());
        assertEquals("moharrir", configured.fileName());
        // without quarkus.application.name : the last resort, with a warning
        AppInfo unnamed = AppInfo.of(TestConfig.of(Map.of("name", "编辑器")), AppInfo.UNSET, "1.0", "org.acme",
                Path.of("."));
        assertEquals("application", unnamed.packageName());
        assertEquals("org.acme.application", unnamed.identifier());
        assertEquals("application", unnamed.fileName());
    }

    /**
     * The base name of the packages : no separator at its ends (a leading dash is an option for the command lines),
     * no repeated dashes.
     */
    @Test
    void fileNames() {
        assertEquals("Acme-Editor", fileName("Acme Editor"));
        assertEquals("Acme", fileName("Acme محرر"));
        assertEquals("Acme-Editor", fileName("Acme - Editor"));
        assertEquals("Acme-Editor-2.0", fileName("Acme Editor 2.0"));
        assertEquals("Acme-Corp", fileName("Acme Corp."));
        assertEquals("Acme", fileName("-Acme-"));
        assertEquals("Wide-file2", fileName("Ｗｉｄｅ ﬁle²"));
        // nothing left : the package name
        assertEquals("acme-editor", fileName(".."));
        assertEquals("acme-editor", fileName("محرر النصوص"));
    }

    private static String fileName(String name) {
        return TestConfig.app(TestConfig.of(Map.of("name", name))).fileName();
    }

    @Test
    void invalidNamesAreRejected() {
        assertThrows(PackagingException.class,
                () -> TestConfig.app(TestConfig.of(Map.of("linux.package-name", "Acme Editor"))));
        assertThrows(PackagingException.class, () -> TestConfig.app(TestConfig.of(Map.of("identifier", "editor"))));
        assertThrows(PackagingException.class,
                () -> TestConfig.app(TestConfig.of(Map.of("identifier", "com.-acme.editor"))));
        assertThrows(PackagingException.class, () -> TestConfig.app(TestConfig.of(Map.of("license-file", "missing.txt"))));
    }

    /**
     * The name of the application names its files on Windows and macOS, and {@code jpackage} takes it : the files of
     * the Linux packages are named after the package name.
     */
    @Test
    void theNameIsAFileName() {
        for (String name : List.of("What's Up?", "Acme: Editor", "A*", "Notes/Tasks", "Acme\\Editor", "Acme <Beta>",
                "Pipe|x", "Say \"hi\"", "50% Off", "Tools; Utilities", "Tab\tName", "Wide　Space")) {
            PackagingException e = assertThrows(PackagingException.class,
                    () -> AppInfo.checkName(name, Optional.empty(), TargetOs.WINDOWS), name);
            assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.name=\"" + name + "\" :"), e.getMessage());
        }
        for (String name : List.of("Notes/Tasks", "Say \"hi\"", "50% Off", "Line\nBreak", "Wide　Space")) {
            assertThrows(PackagingException.class, () -> AppInfo.checkName(name, Optional.empty(), TargetOs.MACOS), name);
        }
        for (String name : List.of("What's Up?", "Acme: Editor", "Tools; Utilities")) {
            assertDoesNotThrow(() -> AppInfo.checkName(name, Optional.empty(), TargetOs.MACOS), name);
        }
        assertThrows(PackagingException.class, () -> AppInfo.checkName("Tab\tName", Optional.empty(), TargetOs.LINUX));
        for (String name : List.of("What's Up?", "Notes/Tasks", "50% Off", "Wide　Space")) {
            assertDoesNotThrow(() -> AppInfo.checkName(name, Optional.empty(), TargetOs.LINUX), name);
        }
        for (TargetOs os : TargetOs.values()) {
            for (String name : List.of("Acme Editor", "Acme (Beta) & Co", "Café", "Acme Corp.", "per-cent, apos' #1")) {
                assertDoesNotThrow(() -> AppInfo.checkName(name, Optional.of("Tools & Utilities"), os), name);
            }
        }
        // the launcher of a JVM application does not start on Windows from a directory whose path has a semicolon
        PackagingException e = assertThrows(PackagingException.class,
                () -> AppInfo.checkName("Tools", Optional.of("Tools;Utilities"), TargetOs.WINDOWS));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.windows.install-directory="), e.getMessage());
        assertDoesNotThrow(() -> AppInfo.checkName("Tools", Optional.of("Tools;Utilities"), TargetOs.LINUX));
    }

    /**
     * The name is checked with the packages, before the application image is built (and before the native executable
     * when the build packages it) : a Windows native executable that is not packaged gets it in its resources, whatever
     * it is.
     */
    @Test
    void theNameIsCheckedForTheOperatingSystemOfTheApplication(@TempDir Path project) throws IOException {
        Map<String, String> name = Map.of("name", "What's Up?");
        PackagingContext windows = PackagingTest.context(project, TargetOs.WINDOWS, name);
        assertEquals("What's Up?", windows.app().name());
        PackagingException e = assertThrows(PackagingException.class,
                () -> Packaging.check(windows, List.of(PackageFormat.DIRECTORY)));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.name=\"What's Up?\" :"), e.getMessage());
        Packaging.check(PackagingTest.context(project, TargetOs.MACOS, name), List.of(PackageFormat.DIRECTORY));

        PackagingContext semicolon = PackagingTest.context(project, TargetOs.WINDOWS,
                Map.of("windows.install-directory", "Tools;Utilities"));
        e = assertThrows(PackagingException.class, () -> Packaging.check(semicolon, List.of(PackageFormat.DIRECTORY)));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.windows.install-directory="), e.getMessage());
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
        // the version of a tag
        assertEquals("1.2.0", app("v1.2").numericVersion(3));
        assertEquals("1.2.3.0", app("V1.2.3-SNAPSHOT").numericVersion(4));
        assertEquals("1.0.0", app("version").numericVersion(3));
    }

    @Test
    void linuxVersions() {
        assertEquals("1.2.3~SNAPSHOT", app("1.2.3-SNAPSHOT").linuxVersion());
        assertEquals("1.2.3", app("1.2.3").linuxVersion());
        assertEquals("0.main", app("main").linuxVersion());
        assertEquals("1.0.rc.1", app("1.0 rc/1").linuxVersion());
        // a tilde before a pre-release qualifier : sorted before the release
        assertEquals("1.0.0~rc1", app("1.0.0-rc1").linuxVersion());
        assertEquals("1.0.0~RC.2", app("1.0.0-RC.2").linuxVersion());
        assertEquals("2.0~M1", app("2.0-M1").linuxVersion());
        assertEquals("1.0~alpha.1", app("1.0-alpha-1").linuxVersion());
        assertEquals("1.0.0~Beta3", app("1.0.0.Beta3").linuxVersion());
        assertEquals("1.4.0.2~SNAPSHOT", app("1.4.0-2-SNAPSHOT").linuxVersion());
        // a dot before the other suffixes : sorted after the release
        assertEquals("1.4.0.2", app("1.4.0-2").linuxVersion());
        assertEquals("1.0.0.SP1", app("1.0.0-SP1").linuxVersion());
        assertEquals("2.1.Final", app("2.1-Final").linuxVersion());
        assertEquals("1.0.0.Final", app("1.0.0.Final").linuxVersion());
        assertEquals("33.0.0.jre", app("33.0.0-jre").linuxVersion());
        assertEquals("1.0.main", app("1.0-main").linuxVersion());
        // the version of a tag
        assertEquals("1.2", app("v1.2").linuxVersion());
        assertEquals("1.2.3~rc1", app("v1.2.3-rc1").linuxVersion());
        assertEquals("0.version", app("version").linuxVersion());
    }

    @Test
    void theGuidsAreStable() {
        AppInfo app = TestConfig.app();
        // the upgrade code and the component GUIDs of the installed applications : they never change
        assertEquals("59D1462D-3EFC-3CAD-A8E4-2965BE1A0F56", app.guid("upgrade"));
        assertEquals("05F95CC4-1B96-3EB0-ACCB-61B9F9EEF648", app.guid("other"));
        assertEquals("8C16C0B2-04D5-3DAB-81CD-CEBFFD3AF8F0", app.guid("component:Acme Editor.exe"));
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

    /**
     * A native executable built in a container is a Linux executable, on any operating system : on Windows and macOS,
     * the formats of the operating system of the build and the ones of Linux are skipped, with a warning.
     */
    @Test
    void theFormatsOfAContainerBuild() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("formats", "msi,dmg,deb,dmg"));
        assertEquals(Optional.empty(), Packaging.containerBuildWarning(config, TargetOs.LINUX, TargetOs.LINUX));
        assertEquals(Optional.empty(), Packaging.containerBuildWarning(config, TargetOs.MACOS, TargetOs.MACOS));
        String warning = Packaging.containerBuildWarning(config, TargetOs.LINUX, TargetOs.MACOS).orElseThrow();
        assertTrue(warning.startsWith("The dmg, deb packages are not built : "), warning);
        assertTrue(warning.contains("(quarkus.native.container-build=false) to package it as dmg. Build it on Linux to"
                + " package it as deb"), warning);
        assertFalse(warning.contains("msi"), warning);
        warning = Packaging.containerBuildWarning(config, TargetOs.LINUX, TargetOs.WINDOWS).orElseThrow();
        assertTrue(warning.startsWith("The msi, deb packages are not built : "), warning);
        warning = Packaging.containerBuildWarning(TestConfig.of(Map.of("formats", "deb,rpm")), TargetOs.LINUX,
                TargetOs.WINDOWS).orElseThrow();
        assertTrue(warning.startsWith("The deb, rpm packages are not built : "), warning);
        assertFalse(warning.contains("container-build=false"), warning);
        assertEquals(Optional.empty(), Packaging.containerBuildWarning(TestConfig.of(Map.of("formats", "dmg")),
                TargetOs.LINUX, TargetOs.WINDOWS));
    }

    private static AppInfo app(String version) {
        return new AppInfo("Acme Editor", "acme-editor", "org.acme.AcmeEditor", version, "Acme", "Edits documents",
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
