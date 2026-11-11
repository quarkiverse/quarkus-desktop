package io.quarkiverse.desktop.packaging.deployment.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * The files that the macOS packagers generate : the information property list of the bundle, the entitlements and the
 * distribution file of the installer package. And the attempts of {@code hdiutil}, and its staging folder.
 */
class MacPackagesTest {

    /**
     * The entries of a property list : the keys and the text of the strings, or the name of the other elements.
     */
    static Map<String, String> entries(String plist) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // the document type of a property list is on the site of Apple
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(plist.getBytes(
                StandardCharsets.UTF_8)));
        NodeList nodes = document.getElementsByTagName("dict").item(0).getChildNodes();
        Map<String, String> entries = new LinkedHashMap<>();
        String key = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if (element.getTagName().equals("key")) {
                key = element.getTextContent();
            } else {
                entries.put(key, element.getTagName().equals("string") ? element.getTextContent() : element.getTagName());
            }
        }
        return entries;
    }

    @Test
    void theInformationPropertyListOfTheBundle() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("copyright", "Copyright (c) Acme & Sons",
                "macos.minimum-system-version", "12.0", "macos.info-plist.NSCameraUsageDescription", "Scans <documents>",
                "macos.category", "public.app-category.productivity"));
        Map<String, String> entries = entries(MacBundle.infoPlist(TestConfig.app(config), config.macos(), "Acme Editor",
                "Acme-Editor.icns"));
        assertEquals("Acme Editor", entries.get("CFBundleExecutable"));
        assertEquals("Acme-Editor.icns", entries.get("CFBundleIconFile"));
        assertEquals("org.acme.AcmeEditor", entries.get("CFBundleIdentifier"));
        assertEquals("Acme Editor", entries.get("CFBundleName"));
        assertEquals("APPL", entries.get("CFBundlePackageType"));
        assertEquals("1.2.3", entries.get("CFBundleShortVersionString"));
        assertEquals("1.2.3", entries.get("CFBundleVersion"));
        assertEquals("public.app-category.productivity", entries.get("LSApplicationCategoryType"));
        assertEquals("12.0", entries.get("LSMinimumSystemVersion"));
        assertEquals("Copyright (c) Acme & Sons", entries.get("NSHumanReadableCopyright"));
        assertEquals("Scans <documents>", entries.get("NSCameraUsageDescription"));
        assertEquals("true", entries.get("NSHighResolutionCapable"));

        DesktopPackagingConfig defaults = TestConfig.defaults();
        Map<String, String> other = entries(MacBundle.infoPlist(TestConfig.app(defaults), defaults.macos(), "Acme Editor",
                "Acme-Editor.icns"));
        assertFalse(other.containsKey("LSMinimumSystemVersion"));
        assertFalse(other.containsKey("NSHumanReadableCopyright"));
        assertEquals("public.app-category.utilities", other.get("LSApplicationCategoryType"));

        // the last part of the identifier of the category, which jpackage takes
        DesktopPackagingConfig category = TestConfig.of(Map.of("macos.category", "developer-tools"));
        assertEquals("public.app-category.developer-tools", entries(MacBundle.infoPlist(TestConfig.app(category),
                category.macos(), "Acme Editor", "Acme-Editor.icns")).get("LSApplicationCategoryType"));
    }

    /**
     * An entry of the configuration replaces the one of the bundle of the same key : once, {@code true} and
     * {@code false} as Booleans.
     */
    @Test
    void theEntriesOfTheConfigurationReplaceTheOnesOfTheBundle() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("macos.info-plist.NSSupportsAutomaticGraphicsSwitching",
                "false", "macos.info-plist.LSUIElement", "true", "macos.info-plist.NSCameraUsageDescription", "Scans",
                "macos.info-plist.CFBundleName", "Acme"));
        String plist = MacBundle.infoPlist(TestConfig.app(config), config.macos(), "Acme Editor", "Acme-Editor.icns");
        // a key once : the entries keep the last value of a key
        for (String key : List.of("NSSupportsAutomaticGraphicsSwitching", "NSHighResolutionCapable", "CFBundleName")) {
            assertEquals(2, plist.split("<key>" + key + "</key>", -1).length, plist);
        }
        assertTrue(plist.contains("<key>NSSupportsAutomaticGraphicsSwitching</key>\n    <false/>\n"), plist);
        assertTrue(plist.contains("<key>NSHighResolutionCapable</key>\n    <true/>\n"), plist);
        assertTrue(plist.contains("<key>LSUIElement</key>\n    <true/>\n"), plist);
        Map<String, String> entries = entries(plist);
        assertEquals("Scans", entries.get("NSCameraUsageDescription"));
        assertEquals("Acme", entries.get("CFBundleName"));
        assertEquals("Acme Editor", entries.get("CFBundleDisplayName"));

        // the values of the extension stay strings
        DesktopPackagingConfig named = TestConfig.of(Map.of("name", "true"));
        assertTrue(MacBundle.infoPlist(TestConfig.app(named), named.macos(), "true", "true.icns")
                .contains("<key>CFBundleName</key>\n    <string>true</string>\n"));
    }

    /**
     * The bundle identifier has letters, digits, dashes and dots only, which macOS and {@code jpackage} require : the
     * identifier of the application may have underscores, which Flatpak takes.
     */
    @Test
    void theBundleIdentifierHasNoUnderscore() throws Exception {
        assertEquals("org.acme.AcmeEditor", MacBundle.identifier(TestConfig.app(TestConfig.defaults())));
        Map<String, String> identifiers = Map.of("org.acme.my_app", "org.acme.my-app", "org._7zip.Editor",
                "org.7zip.Editor", "org.__acme_.editor", "org.acme-.editor", "org._.editor", "org.-.editor",
                "com.acme-corp.Editor", "com.acme-corp.Editor");
        for (Map.Entry<String, String> identifier : identifiers.entrySet()) {
            DesktopPackagingConfig config = TestConfig.of(Map.of("identifier", identifier.getKey()));
            assertEquals(identifier.getValue(), MacBundle.identifier(TestConfig.app(config)), identifier.getKey());
        }
        // the default identifier keeps the underscores of the name
        DesktopPackagingConfig named = TestConfig.of(Map.of("name", "my_app"));
        assertEquals("org.acme.my_app", TestConfig.app(named).identifier());
        assertEquals("org.acme.my-app", entries(MacBundle.infoPlist(TestConfig.app(named), named.macos(), "my_app",
                "my_app.icns")).get("CFBundleIdentifier"));
    }

    /**
     * The information property list that {@code jpackage} writes in the bundle of a JVM application, with the entries
     * of the configuration.
     */
    @Test
    void theInformationPropertyListOfAJvmBundle() throws Exception {
        // as the jpackage of JDK 21 to 25 writes it
        String plist = String.join("\n", "<?xml version=\"1.0\" ?>",
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
                "<plist version=\"1.0\">", " <dict>", "  <key>LSMinimumSystemVersion</key>", "  <string>10.11</string>",
                "  <key>CFBundleAllowMixedLocalizations</key>", "  <true/>", "  <key>CFBundleName</key>",
                "  <string>Acme Editor</string>",
                "  <!-- See https://developer.apple.com/app-store/categories/ for list of AppStore categories -->",
                "  <key>LSApplicationCategoryType</key>", "  <string>Unknown</string>", "  <key>NSHighResolutionCapable</key>",
                "  <string>true</string>", "  <key>NSMicrophoneUsageDescription</key>",
                "  <string>The application Acme Editor is requesting access to the microphone.</string>", " </dict>",
                "</plist>", "");
        DesktopPackagingConfig config = TestConfig.of(Map.of("macos.minimum-system-version", "13.0",
                "macos.info-plist.NSCameraUsageDescription", "Scans <documents>",
                "macos.info-plist.NSMicrophoneUsageDescription", "Records notes",
                "macos.info-plist.NSHighResolutionCapable", "false", "macos.info-plist.LSUIElement", "true"));
        String withEntries = MacBundle.jvmInfoPlist(plist, config.macos());
        Map<String, String> entries = entries(withEntries);
        assertEquals("13.0", entries.get("LSMinimumSystemVersion"));
        assertEquals("Scans <documents>", entries.get("NSCameraUsageDescription"));
        assertEquals("Records notes", entries.get("NSMicrophoneUsageDescription"));
        assertEquals("false", entries.get("NSHighResolutionCapable"));
        assertEquals("true", entries.get("LSUIElement"));
        assertEquals("true", entries.get("CFBundleAllowMixedLocalizations"));
        assertEquals("Acme Editor", entries.get("CFBundleName"));
        assertEquals("Unknown", entries.get("LSApplicationCategoryType"));
        // a key once, its value in place ; the new entries at the end of the dictionary, as indented as the others
        for (String key : List.of("LSMinimumSystemVersion", "NSHighResolutionCapable", "NSMicrophoneUsageDescription",
                "NSCameraUsageDescription")) {
            assertEquals(2, withEntries.split("<key>" + key + "</key>", -1).length, withEntries);
        }
        assertTrue(withEntries.contains("  <key>LSMinimumSystemVersion</key>\n  <string>13.0</string>\n"
                + "  <key>CFBundleAllowMixedLocalizations</key>"), withEntries);
        assertTrue(withEntries.contains("  <key>NSHighResolutionCapable</key>\n  <false/>\n"
                + "  <key>NSMicrophoneUsageDescription</key>\n  <string>Records notes</string>\n  <key>"), withEntries);
        assertTrue(withEntries.contains("\n  <key>NSCameraUsageDescription</key>\n  <string>Scans &lt;documents&gt;"
                + "</string>\n"), withEntries);
        assertTrue(withEntries.contains("\n  <key>LSUIElement</key>\n  <true/>\n"), withEntries);
        assertTrue(withEntries.endsWith(">\n </dict>\n</plist>\n"), withEntries);

        // an entry of info-plist replaces the minimum version of macOS
        DesktopPackagingConfig minimum = TestConfig.of(Map.of("macos.minimum-system-version", "13.0",
                "macos.info-plist.LSMinimumSystemVersion", "14.0"));
        assertEquals("14.0", entries(MacBundle.jvmInfoPlist(plist, minimum.macos())).get("LSMinimumSystemVersion"));
        // without entries, the property list of jpackage as it is
        assertSame(plist, MacBundle.jvmInfoPlist(plist, TestConfig.defaults().macos()));
        // an array or a dictionary cannot be replaced
        DesktopPackagingConfig types = TestConfig.of(Map.of("macos.info-plist.CFBundleDocumentTypes", "none"));
        String withArray = plist.replace(" </dict>", "  <key>CFBundleDocumentTypes</key>\n  <array>\n  </array>\n </dict>");
        PackagingException array = assertThrows(PackagingException.class,
                () -> MacBundle.jvmInfoPlist(withArray, types.macos()));
        assertTrue(array.getMessage().contains("quarkus.desktop.packaging.macos.info-plist.CFBundleDocumentTypes"),
                array.getMessage());
        assertThrows(PackagingException.class, () -> MacBundle.jvmInfoPlist("<plist version=\"1.0\"/>", config.macos()));
    }

    /**
     * hdiutil runs again when a disk image is busy, up to its last attempt, and not when it fails otherwise.
     */
    @Test
    void hdiutilRunsAgainWhenADiskImageIsBusy() {
        assertTrue(DmgPackager.resourceBusy("hdiutil: create failed - Resource busy"));
        assertFalse(DmgPackager.resourceBusy("hdiutil: create failed - No space left on device"));
        assertFalse(DmgPackager.resourceBusy(""));

        List<String> command = List.of("hdiutil", "create", "Acme.dmg");
        Tools.Result busy = new Tools.Result(1, "hdiutil: create failed - Resource busy\n");
        Tools.Result created = new Tools.Result(0, "created: Acme.dmg\n");
        List<Tools.Result> results = new ArrayList<>(List.of(busy, busy, created));
        assertSame(created, DmgPackager.exec(command, run -> results.remove(0), 0));
        assertTrue(results.isEmpty());

        List<List<String>> runs = new ArrayList<>();
        PackagingException full = assertThrows(PackagingException.class, () -> DmgPackager.exec(command, run -> {
            runs.add(run);
            return new Tools.Result(1, "hdiutil: create failed - No space left on device");
        }, 0));
        assertEquals(List.of(command), runs);
        assertEquals("hdiutil failed with exit code 1 (hdiutil create Acme.dmg) :\n"
                + "hdiutil: create failed - No space left on device", full.getMessage());
        runs.clear();
        PackagingException stillBusy = assertThrows(PackagingException.class, () -> DmgPackager.exec(command, run -> {
            runs.add(run);
            return busy;
        }, 0));
        assertEquals(DmgPackager.ATTEMPTS, runs.size());
        assertTrue(stillBusy.getMessage().endsWith(":\nhdiutil: create failed - Resource busy"), stillBusy.getMessage());
    }

    /**
     * The staging folder of the disk image is deleted once hdiutil has run, whether it built the image or not : its link
     * to the Applications folder is deleted, not followed.
     */
    @Test
    void theStagingFolderOfTheDiskImageIsDeleted(@TempDir Path directory) throws Exception {
        Path applications = Files.createDirectories(directory.resolve("Applications"));
        Path installed = Files.writeString(applications.resolve("Installed.txt"), "installed");
        Path work = directory.resolve("work/dmg");
        List<String> command = List.of("hdiutil", "create", "Acme.dmg");
        for (Tools.Result result : List.of(new Tools.Result(0, "created: Acme.dmg\n"),
                new Tools.Result(1, "hdiutil: create failed - No space left on device"))) {
            Path volume = work.resolve("Acme-Editor");
            Files.writeString(Files.createDirectories(volume.resolve("Acme Editor.app/Contents/MacOS"))
                    .resolve("Acme Editor"), "executable");
            try {
                Files.createSymbolicLink(volume.resolve("Applications"), applications);
            } catch (IOException | UnsupportedOperationException e) {
                // Windows, without the privilege of creating symbolic links : the folder is deleted all the same
            }
            if (result.succeeded()) {
                DmgPackager.create(volume, command, run -> result, 0);
            } else {
                assertThrows(PackagingException.class, () -> DmgPackager.create(volume, command, run -> result, 0));
            }
            assertFalse(Files.exists(volume, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isDirectory(work));
            assertEquals("installed", Files.readString(installed));
        }
    }

    @Test
    void theEntitlementsOfAJvm() throws Exception {
        Map<String, String> entries = entries(MacBundle.entitlements(MacBundle.JVM_ENTITLEMENTS));
        assertEquals(4, entries.size());
        assertEquals("true", entries.get("com.apple.security.cs.allow-jit"));
        assertEquals("true", entries.get("com.apple.security.cs.disable-library-validation"));
        assertEquals(MacBundle.JVM_ENTITLEMENTS, MacBundle.defaultEntitlements(false, true));
        assertEquals(MacBundle.JVM_ENTITLEMENTS, MacBundle.defaultEntitlements(false, false));
    }

    /**
     * Signed with a certificate, a native executable loads the libraries that are not in its bundle, such as the ones
     * that SWT extracts, which the hardened runtime refuses otherwise. An ad hoc signature has no hardened runtime.
     */
    @Test
    void theEntitlementsOfANativeExecutable() throws Exception {
        assertEquals(Map.of("com.apple.security.cs.disable-library-validation", "true"),
                entries(MacBundle.entitlements(MacBundle.defaultEntitlements(true, true))));
        assertEquals(List.of(), MacBundle.defaultEntitlements(true, false));
    }

    @Test
    void theDistributionOfTheInstallerPackage() {
        String synthesized = """
                <?xml version="1.0" encoding="utf-8"?>
                <installer-gui-script minSpecVersion="1">
                    <pkg-ref id="org.acme.AcmeEditor"/>
                </installer-gui-script>
                """;
        String distribution = PkgPackager.distribution(synthesized, "Acme & Editor", "license.txt");
        assertTrue(distribution.contains("<installer-gui-script minSpecVersion=\"1\">\n    <title>Acme &amp; Editor</title>\n"
                + "    <license file=\"license.txt\"/>\n    <pkg-ref id=\"org.acme.AcmeEditor\"/>"), distribution);
        assertFalse(PkgPackager.distribution(synthesized, "Acme Editor", null).contains("<license"));
        assertThrows(PackagingException.class, () -> PkgPackager.distribution("", "Acme Editor", null));
    }
}
