package io.quarkiverse.desktop.packaging.deployment.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.Associations;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
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
        String withEntries = MacBundle.jvmInfoPlist(plist, TestConfig.app(config), config.macos());
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
        assertEquals("14.0",
                entries(MacBundle.jvmInfoPlist(plist, TestConfig.app(minimum), minimum.macos())).get("LSMinimumSystemVersion"));
        // without entries, the property list of jpackage as it is
        assertSame(plist, MacBundle.jvmInfoPlist(plist, TestConfig.app(TestConfig.defaults()), TestConfig.defaults().macos()));
        // an array or a dictionary cannot be replaced
        DesktopPackagingConfig types = TestConfig.of(Map.of("macos.info-plist.CFBundleDocumentTypes", "none"));
        String withArray = plist.replace(" </dict>", "  <key>CFBundleDocumentTypes</key>\n  <array>\n  </array>\n </dict>");
        PackagingException array = assertThrows(PackagingException.class,
                () -> MacBundle.jvmInfoPlist(withArray, TestConfig.app(types), types.macos()));
        assertTrue(array.getMessage().contains("quarkus.desktop.packaging.macos.info-plist.CFBundleDocumentTypes"),
                array.getMessage());
        assertThrows(PackagingException.class,
                () -> MacBundle.jvmInfoPlist("<plist version=\"1.0\"/>", TestConfig.app(config), config.macos()));
    }

    /**
     * The document type definition of the property lists ({@code PropertyList-1.0.dtd}).
     */
    private static final String PLIST_DTD = """
            <!ENTITY % plistObject "(array | data | date | dict | real | integer | string | true | false )" >
            <!ELEMENT plist %plistObject;>
            <!ATTLIST plist version CDATA "1.0" >
            <!ELEMENT array (%plistObject;)*>
            <!ELEMENT dict (key, %plistObject;)*>
            <!ELEMENT key (#PCDATA)>
            <!ELEMENT string (#PCDATA)>
            <!ELEMENT data (#PCDATA)>
            <!ELEMENT date (#PCDATA)>
            <!ELEMENT true EMPTY>
            <!ELEMENT false EMPTY>
            <!ELEMENT real (#PCDATA)>
            <!ELEMENT integer (#PCDATA)>
            """;

    /**
     * A property list, validated with its document type definition : its dictionaries as maps, its arrays as lists, its
     * strings as strings and its Booleans as Booleans.
     *
     * @throws Exception when the property list is not valid
     */
    static Object parse(String plist) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(true);
        factory.setIgnoringElementContentWhitespace(true);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader(PLIST_DTD)));
        List<SAXParseException> errors = new ArrayList<>();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                errors.add(exception);
            }

            @Override
            public void error(SAXParseException exception) {
                errors.add(exception);
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXParseException {
                throw exception;
            }
        });
        Document document = builder.parse(new ByteArrayInputStream(plist.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(), errors.stream().map(SAXParseException::getMessage).toList(), plist);
        return value(children(document.getDocumentElement()).get(0));
    }

    private static List<Element> children(Element element) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    private static Object value(Element element) {
        return switch (element.getTagName()) {
            case "dict" -> {
                Map<String, Object> dict = new LinkedHashMap<>();
                List<Element> children = children(element);
                for (int i = 0; i < children.size(); i += 2) {
                    assertTrue(dict.put(children.get(i).getTextContent(), value(children.get(i + 1))) == null,
                            "a key twice : " + children.get(i).getTextContent());
                }
                yield dict;
            }
            case "array" -> children(element).stream().map(MacPackagesTest::value).toList();
            case "true", "false" -> Boolean.valueOf(element.getTagName());
            default -> element.getTextContent();
        };
    }

    /**
     * An application that opens the files of a type, {@code document}, with an icon or not, and the URLs of schemes.
     */
    static AppInfo withAssociations(AppInfo app, Optional<Path> icon, List<String> schemes) {
        Associations.FileType document = new Associations.FileType("document", List.of("acme", "acmex"),
                "application/x-acme-editor-document", "Acme Editor <document>", icon);
        return app.withAssociations(new Associations(List.of(document), schemes));
    }

    /**
     * The information property list of an application that opens files and URLs : the types of documents, their
     * declarations and the URL schemes, in the bundle of a native executable and in the one of {@code jpackage}.
     */
    @Test
    void theFileTypesAndUrlSchemesOfTheBundle() throws Exception {
        DesktopPackagingConfig config = TestConfig.defaults();
        AppInfo app = withAssociations(TestConfig.app(config), Optional.of(Path.of("document.png")),
                List.of("acme", "acme-editor"));
        Map<?, ?> expectedDocument = Map.of("CFBundleTypeName", "Acme Editor <document>", "CFBundleTypeRole", "Editor",
                "LSHandlerRank", "Owner", "LSItemContentTypes", List.of("org.acme.AcmeEditor.document"),
                "CFBundleTypeIconFile", "org.acme.AcmeEditor.document.icns");
        Map<?, ?> expectedDeclaration = Map.of("UTTypeIdentifier", "org.acme.AcmeEditor.document", "UTTypeDescription",
                "Acme Editor <document>", "UTTypeConformsTo", List.of("public.data"), "UTTypeIconFile",
                "org.acme.AcmeEditor.document.icns", "UTTypeTagSpecification", Map.of("public.filename-extension",
                        List.of("acme", "acmex"), "public.mime-type", "application/x-acme-editor-document"));
        Map<?, ?> expectedUrls = Map.of("CFBundleURLName", "org.acme.AcmeEditor", "CFBundleURLSchemes",
                List.of("acme", "acme-editor"));

        String nativePlist = MacBundle.infoPlist(app, config.macos(), "Acme Editor", "Acme-Editor.icns");
        Map<?, ?> entries = (Map<?, ?>) parse(nativePlist);
        assertEquals(List.of(expectedDocument), entries.get("CFBundleDocumentTypes"));
        assertEquals(List.of(expectedDeclaration), entries.get("UTExportedTypeDeclarations"));
        assertEquals(List.of(expectedUrls), entries.get("CFBundleURLTypes"));
        assertEquals("org.acme.AcmeEditor", entries.get("CFBundleIdentifier"));
        assertTrue(nativePlist.contains("    <key>CFBundleURLTypes</key>\n    <array>\n        <dict>\n"
                + "            <key>CFBundleURLName</key>\n            <string>org.acme.AcmeEditor</string>\n"), nativePlist);
        assertTrue(nativePlist.endsWith("        </dict>\n    </array>\n</dict>\n</plist>\n"), nativePlist);

        String jvmPlist = String.join("\n", "<?xml version=\"1.0\" ?>",
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
                "<plist version=\"1.0\">", " <dict>", "  <key>CFBundleName</key>", "  <string>Acme Editor</string>",
                " </dict>", "</plist>", "");
        String withEntries = MacBundle.jvmInfoPlist(jvmPlist, app, config.macos());
        Map<?, ?> jvmEntries = (Map<?, ?>) parse(withEntries);
        assertEquals(List.of(expectedDocument), jvmEntries.get("CFBundleDocumentTypes"));
        assertEquals(List.of(expectedDeclaration), jvmEntries.get("UTExportedTypeDeclarations"));
        assertEquals(List.of(expectedUrls), jvmEntries.get("CFBundleURLTypes"));
        assertEquals("Acme Editor", jvmEntries.get("CFBundleName"));
        assertTrue(withEntries.contains("  <key>CFBundleURLTypes</key>\n  <array>\n    <dict>\n"
                + "      <key>CFBundleURLName</key>\n"), withEntries);
        assertTrue(withEntries.endsWith("    </dict>\n  </array>\n </dict>\n</plist>\n"), withEntries);

        // without icon, the icon of the application ; without URL schemes, no URL types
        AppInfo withoutIcon = withAssociations(TestConfig.app(config), Optional.empty(), List.of());
        Map<?, ?> other = (Map<?, ?>) parse(MacBundle.infoPlist(withoutIcon, config.macos(), "Acme Editor",
                "Acme-Editor.icns"));
        Map<?, ?> document = (Map<?, ?>) ((List<?>) other.get("CFBundleDocumentTypes")).get(0);
        assertFalse(document.containsKey("CFBundleTypeIconFile"), document.toString());
        assertFalse(((Map<?, ?>) ((List<?>) other.get("UTExportedTypeDeclarations")).get(0))
                .containsKey("UTTypeIconFile"));
        assertFalse(other.containsKey("CFBundleURLTypes"));
        // URL schemes only
        AppInfo schemes = TestConfig.app(config).withAssociations(new Associations(List.of(), List.of("acme")));
        Map<?, ?> schemeEntries = (Map<?, ?>) parse(MacBundle.jvmInfoPlist(jvmPlist, schemes, config.macos()));
        assertEquals(List.of(Map.of("CFBundleURLName", "org.acme.AcmeEditor", "CFBundleURLSchemes", List.of("acme"))),
                schemeEntries.get("CFBundleURLTypes"));
        assertFalse(schemeEntries.containsKey("CFBundleDocumentTypes"));

        // an entry of info-plist replaces them
        DesktopPackagingConfig replaced = TestConfig.of(Map.of("macos.info-plist.CFBundleURLTypes", "none"));
        String replacedPlist = MacBundle.infoPlist(app, replaced.macos(), "Acme Editor", "Acme-Editor.icns");
        assertEquals("none", ((Map<?, ?>) parse(replacedPlist)).get("CFBundleURLTypes"));
        assertEquals("none", ((Map<?, ?>) parse(MacBundle.jvmInfoPlist(jvmPlist, app, replaced.macos())))
                .get("CFBundleURLTypes"));
        // jpackage wrote its own file associations
        String withTypes = jvmPlist.replace(" </dict>", "  <key>CFBundleDocumentTypes</key>\n  <array>\n  </array>\n"
                + " </dict>");
        assertThrows(PackagingException.class, () -> MacBundle.jvmInfoPlist(withTypes, app, config.macos()));
    }

    /**
     * Without file types and URL schemes, the information property list of the bundle of a native executable is as it
     * was before them, byte for byte, and valid.
     */
    @Test
    void theInformationPropertyListWithoutAssociations() throws Exception {
        DesktopPackagingConfig config = TestConfig.defaults();
        String plist = MacBundle.infoPlist(TestConfig.app(config), config.macos(), "Acme Editor", "Acme-Editor.icns");
        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>CFBundleDevelopmentRegion</key>
                    <string>en</string>
                    <key>CFBundleExecutable</key>
                    <string>Acme Editor</string>
                    <key>CFBundleIconFile</key>
                    <string>Acme-Editor.icns</string>
                    <key>CFBundleIdentifier</key>
                    <string>org.acme.AcmeEditor</string>
                    <key>CFBundleInfoDictionaryVersion</key>
                    <string>6.0</string>
                    <key>CFBundleName</key>
                    <string>Acme Editor</string>
                    <key>CFBundleDisplayName</key>
                    <string>Acme Editor</string>
                    <key>CFBundlePackageType</key>
                    <string>APPL</string>
                    <key>CFBundleShortVersionString</key>
                    <string>1.2.3</string>
                    <key>CFBundleVersion</key>
                    <string>1.2.3</string>
                    <key>LSApplicationCategoryType</key>
                    <string>public.app-category.utilities</string>
                    <key>NSHighResolutionCapable</key>
                    <true/>
                    <key>NSSupportsAutomaticGraphicsSwitching</key>
                    <true/>
                </dict>
                </plist>
                """, plist);
        assertEquals("org.acme.AcmeEditor", ((Map<?, ?>) parse(plist)).get("CFBundleIdentifier"));
        // the validation finds a key without value
        assertThrows(AssertionError.class, () -> parse(plist.replace("    <string>en</string>\n", "")));
    }

    /**
     * The icons of the file types that have one, in the resources of the bundle.
     */
    @Test
    void theIconsOfTheFileTypes(@TempDir Path directory) throws IOException {
        Path icon = IconSet.generic().writePng(directory.resolve("document.png"), 64);
        AppInfo app = withAssociations(TestConfig.app(TestConfig.defaults()), Optional.of(icon), List.of());
        Path resources = directory.resolve("Resources");
        MacBundle.writeTypeIcons(app, resources);
        byte[] icns = Files.readAllBytes(resources.resolve("org.acme.AcmeEditor.document.icns"));
        assertEquals("icns", new String(icns, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(List.of(Path.of("org.acme.AcmeEditor.document.icns")), FileTrees.files(resources));
        MacBundle.writeTypeIcons(withAssociations(TestConfig.app(TestConfig.defaults()), Optional.empty(), List.of()),
                directory.resolve("none"));
        assertFalse(Files.exists(directory.resolve("none")));
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
