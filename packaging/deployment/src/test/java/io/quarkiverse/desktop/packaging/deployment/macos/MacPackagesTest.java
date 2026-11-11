package io.quarkiverse.desktop.packaging.deployment.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;

/**
 * The files that the macOS packagers generate : the information property list of the bundle, the entitlements and the
 * distribution file of the installer package.
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
    }

    @Test
    void theEntitlementsOfAJvm() throws Exception {
        Map<String, String> entries = entries(MacBundle.entitlements(MacBundle.JVM_ENTITLEMENTS));
        assertEquals(4, entries.size());
        assertEquals("true", entries.get("com.apple.security.cs.allow-jit"));
        assertEquals("true", entries.get("com.apple.security.cs.disable-library-validation"));
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
