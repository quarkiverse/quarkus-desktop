package io.quarkiverse.desktop.packaging.deployment.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;

/**
 * The files that the Windows packagers generate : the WiX source, the Inno Setup script, the MSIX manifest and the
 * resources of the executable.
 */
class WindowsPackagesTest {

    static final Path ROOT = Path.of("image", "Acme Editor");

    static final List<Path> FILES = List.of(Path.of("Acme Editor.exe"), Path.of("awt.dll"), Path.of("app", "data.txt"),
            Path.of("app", "lib", "a & b.jar"));

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static String wxs(DesktopPackagingConfig config) {
        return MsiPackager.wxs(TestConfig.app(config), config.windows(), ROOT, "Acme Editor.exe", Path.of("application.ico"),
                FILES);
    }

    @Test
    void theWixSourceInstallsEveryFileForAllUsers() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme & Sons", "homepage", "https://acme.org"));
        Document document = parse(wxs(config));
        Element pkg = (Element) document.getElementsByTagName("Package").item(0);
        assertEquals("Acme Editor", pkg.getAttribute("Name"));
        assertEquals("Acme & Sons", pkg.getAttribute("Manufacturer"));
        assertEquals("1.2.3", pkg.getAttribute("Version"));
        assertEquals("perMachine", pkg.getAttribute("Scope"));
        assertEquals(TestConfig.app(config).guid("upgrade"), pkg.getAttribute("UpgradeCode"));
        assertEquals("ProgramFiles6432Folder", ((Element) document.getElementsByTagName("StandardDirectory").item(0))
                .getAttribute("Id"));

        NodeList files = document.getElementsByTagName("File");
        assertEquals(FILES.size(), files.getLength());
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            sources.add(file.getAttribute("Source"));
            // one file per component, whose GUID WiX generates
            assertEquals("Component", file.getParentNode().getNodeName());
            assertFalse(((Element) file.getParentNode()).hasAttribute("Guid"));
        }
        assertTrue(sources.contains(ROOT.resolve("app").resolve("lib").resolve("a & b.jar").toString()), sources.toString());
        // the launcher is the target of the shortcut
        String xml = wxs(config);
        assertTrue(xml.contains("<File Id=\"LauncherFile\" Source=\"" + ROOT.resolve("Acme Editor.exe") + "\""), xml);
        assertTrue(xml.contains("Target=\"[#LauncherFile]\""), xml);
        // the directories of the image
        assertTrue(xml.contains("<Directory Id=\"INSTALLFOLDER\" Name=\"Acme Editor\">"), xml);
        assertTrue(xml.contains("Name=\"app\""), xml);
        assertTrue(xml.contains("Name=\"lib\""), xml);
        // every component is in the feature
        assertEquals(document.getElementsByTagName("Component").getLength(),
                document.getElementsByTagName("ComponentRef").getLength());
        assertEquals(FILES.size() + 1, document.getElementsByTagName("Component").getLength());
        assertTrue(xml.contains("ARPURLINFOABOUT"), xml);
    }

    @Test
    void theWixSourceOfAPerUserPackage() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("windows.per-user", "true", "windows.desktop-shortcut", "true",
                "windows.start-menu-group", "Acme", "windows.install-directory", "Editor", "windows.upgrade-code",
                "11111111-2222-3333-4444-555555555555"));
        String xml = wxs(config);
        Document document = parse(xml);
        Element pkg = (Element) document.getElementsByTagName("Package").item(0);
        assertEquals("perUser", pkg.getAttribute("Scope"));
        assertEquals("11111111-2222-3333-4444-555555555555", pkg.getAttribute("UpgradeCode"));
        assertTrue(xml.contains("<StandardDirectory Id=\"LocalAppDataFolder\">"), xml);
        assertTrue(xml.contains("<Directory Id=\"INSTALLFOLDER\" Name=\"Editor\">"), xml);
        assertTrue(xml.contains("<Directory Id=\"StartMenuGroup\" Name=\"Acme\">"), xml);
        assertTrue(xml.contains("<StandardDirectory Id=\"DesktopFolder\">"), xml);
        // the components of the files have a registry key path and a GUID of their own
        NodeList files = document.getElementsByTagName("File");
        for (int i = 0; i < files.getLength(); i++) {
            Element component = (Element) files.item(i).getParentNode();
            assertTrue(component.getAttribute("Guid").matches("[0-9A-F-]{36}"), component.getAttribute("Guid"));
            assertEquals(1, component.getElementsByTagName("RegistryValue").getLength());
        }
        // each directory is removed : the installation directory, app and app\lib
        assertEquals(3 + 1, document.getElementsByTagName("RemoveFolder").getLength());
        assertEquals(wxs(config), xml);
    }

    @Test
    void theVersionOfAWindowsInstallerProduct() {
        assertEquals("1.2.3", MsiPackager.version(app("1.2.3-SNAPSHOT")));
        assertEquals("255.255.65535", MsiPackager.version(app("2026.1000.70000")));
    }

    @Test
    void theInnoSetupScript() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme", "homepage", "https://acme.org",
                "windows.desktop-shortcut", "true"));
        AppInfo app = TestConfig.app(config);
        String script = InnoSetupPackager.script(app, config.windows(), Arch.X64, ROOT, "Acme Editor.exe",
                Path.of("application.ico"), Path.of("out"), "Acme-Editor-1.2.3-SNAPSHOT");
        assertTrue(script.contains("AppId={{" + app.guid("upgrade") + "}\n"), script);
        assertTrue(script.contains("AppName=Acme Editor\n"), script);
        assertTrue(script.contains("AppVersion=1.2.3-SNAPSHOT\n"), script);
        assertTrue(script.contains("VersionInfoVersion=1.2.3.0\n"), script);
        assertTrue(script.contains("DefaultDirName={autopf}\\Acme Editor\n"), script);
        assertTrue(script.contains("PrivilegesRequired=admin\n"), script);
        assertTrue(script.contains("ArchitecturesAllowed=x64compatible\n"), script);
        assertTrue(script.contains("OutputBaseFilename=Acme-Editor-1.2.3-SNAPSHOT\n"), script);
        assertTrue(script.contains("Source: \"" + ROOT + "\\*\"; DestDir: \"{app}\""), script);
        assertTrue(script.contains("Name: \"{autoprograms}\\Acme Editor\"; Filename: \"{app}\\Acme Editor.exe\""), script);
        assertTrue(script.contains("Name: \"{autodesktop}\\Acme Editor\""), script);
        assertTrue(script.contains("[Tasks]"), script);

        DesktopPackagingConfig perUser = TestConfig.of(Map.of("windows.per-user", "true", "windows.start-menu", "false"));
        String userScript = InnoSetupPackager.script(TestConfig.app(perUser), perUser.windows(), Arch.ARM64, ROOT,
                "Acme Editor.exe", Path.of("application.ico"), Path.of("out"), "Acme-Editor");
        assertTrue(userScript.contains("PrivilegesRequired=lowest\n"), userScript);
        assertTrue(userScript.contains("ArchitecturesAllowed=arm64\n"), userScript);
        assertFalse(userScript.contains("{autoprograms}"), userScript);
        assertFalse(userScript.contains("[Tasks]"), userScript);
    }

    @Test
    void theMsixManifest() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme, Inc.", "windows.msix.capabilities",
                "runFullTrust,internetClient"));
        AppInfo app = TestConfig.app(config);
        String xml = MsixPackager.manifest(app, config.windows().msix(), Arch.X64, "Acme Editor.exe");
        Document document = parse(xml);
        Element identity = (Element) document.getElementsByTagName("Identity").item(0);
        assertEquals("org.acme.AcmeEditor", identity.getAttribute("Name"));
        assertEquals("CN=Acme  Inc.", identity.getAttribute("Publisher"));
        assertEquals("1.2.3.0", identity.getAttribute("Version"));
        assertEquals("x64", identity.getAttribute("ProcessorArchitecture"));
        Element application = (Element) document.getElementsByTagName("Application").item(0);
        assertEquals("Acme Editor.exe", application.getAttribute("Executable"));
        assertEquals("Windows.FullTrustApplication", application.getAttribute("EntryPoint"));
        // the general capabilities come before the restricted ones
        assertTrue(xml.indexOf("<Capability Name=\"internetClient\"") < xml.indexOf("<rescap:Capability Name=\"runFullTrust\""),
                xml);
        assertEquals("Acme, Inc.", document.getElementsByTagName("PublisherDisplayName").item(0).getTextContent());

        DesktopPackagingConfig invalid = TestConfig.of(Map.of("windows.msix.identity-name", "a b"));
        assertThrows(PackagingException.class,
                () -> MsixPackager.manifest(TestConfig.app(invalid), invalid.windows().msix(), Arch.X64, "a.exe"));
    }

    /**
     * The compiled resource file : the empty resource, the images of the icon, the icon group and the version
     * information.
     */
    @Test
    void theResourcesOfTheExecutable() {
        AppInfo app = TestConfig.app();
        IconSet icons = IconSet.generic();
        byte[] res = WindowsResources.res(icons, app, "Acme Editor.exe");
        ByteBuffer buffer = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> entries = new ArrayList<>();
        int offset = 0;
        while (offset < res.length) {
            int dataSize = buffer.getInt(offset);
            int headerSize = buffer.getInt(offset + 4);
            assertEquals(32, headerSize);
            assertEquals(0xffff, buffer.getShort(offset + 8) & 0xffff);
            entries.add(new int[] { buffer.getShort(offset + 10) & 0xffff, buffer.getShort(offset + 14) & 0xffff, dataSize,
                    offset + headerSize });
            offset += headerSize + ((dataSize + 3) & ~3);
        }
        assertEquals(res.length, offset);
        int images = icons.icoImages().size();
        assertEquals(1 + images + 2, entries.size());
        assertEquals(0, entries.get(0)[0]);
        for (int i = 1; i <= images; i++) {
            assertEquals(3, entries.get(i)[0]);
            assertEquals(i, entries.get(i)[1]);
        }
        int[] group = entries.get(images + 1);
        assertEquals(14, group[0]);
        assertEquals(6 + 14 * images, group[2]);
        assertEquals(images, buffer.getShort(group[3] + 4));
        int[] version = entries.get(images + 2);
        assertEquals(16, version[0]);
        // VS_VERSIONINFO : its length, the length of the fixed information, and the signature of the latter
        assertEquals(version[2], buffer.getShort(version[3]) & 0xffff);
        assertEquals(52, buffer.getShort(version[3] + 2));
        assertEquals(0xfeef04bd, buffer.getInt(version[3] + 40));
        assertEquals(1 << 16 | 2, buffer.getInt(version[3] + 48));
        assertEquals(3 << 16, buffer.getInt(version[3] + 52));
        String strings = new String(res, version[3], version[2], StandardCharsets.UTF_16LE);
        for (String expected : List.of("VS_VERSION_INFO", "StringFileInfo", "040904B0", "CompanyName", "Acme & Sons",
                "FileDescription", "Edits <documents>", "ProductVersion", "1.2.3-SNAPSHOT", "OriginalFilename",
                "Acme Editor.exe", "LegalCopyright", "Copyright (c) Acme", "VarFileInfo", "Translation")) {
            assertTrue(strings.contains(expected), expected);
        }
        assertEquals(List.of(1, 2, 3, 0), List.of(WindowsResources.versionNumbers(app)[0],
                WindowsResources.versionNumbers(app)[1], WindowsResources.versionNumbers(app)[2],
                WindowsResources.versionNumbers(app)[3]));
    }

    private static AppInfo app(String version) {
        return new AppInfo("Acme Editor", "acme-editor", "org.acme.AcmeEditor", version, "Acme", "Edits documents",
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
