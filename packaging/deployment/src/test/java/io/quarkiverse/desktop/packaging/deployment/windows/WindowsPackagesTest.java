package io.quarkiverse.desktop.packaging.deployment.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
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
        assertEquals("24.10.5", MsiPackager.version(app("24.10.05")));
        assertEquals("255.255.65535", MsiPackager.version(app("255.255.65535")));
        // a larger number is refused : cut down, it would change the order of the versions
        for (String version : List.of("2025.1.0", "256.0.0", "1.256.0", "1.0.65536", "999-SNAPSHOT")) {
            PackagingException e = assertThrows(PackagingException.class, () -> MsiPackager.version(app(version)));
            assertTrue(e.getMessage().contains("quarkus.desktop.packaging.version=" + version), e.getMessage());
        }
    }

    @Test
    void theVersionOfAnMsixPackage() {
        assertEquals("1.2.3.0", MsixPackager.version(app("1.2.3-SNAPSHOT")));
        assertEquals("2025.1.0.0", MsixPackager.version(app("2025.1.0")));
        assertEquals("65535.65535.65535.0", MsixPackager.version(app("65535.65535.65535")));
        assertThrows(PackagingException.class, () -> MsixPackager.version(app("1.0.65536")));
    }

    /**
     * The upgrade code of the configuration, with or without braces, in any case : the same code in the MSI package
     * and in the setup program.
     */
    @Test
    void theUpgradeCodeOfTheConfiguration() throws Exception {
        for (String written : List.of("abcdefab-1111-2222-3333-444444444444", "{ABCDEFAB-1111-2222-3333-444444444444}",
                " {abcdefab-1111-2222-3333-444444444444} ")) {
            DesktopPackagingConfig config = TestConfig.of(Map.of("windows.upgrade-code", written));
            AppInfo app = TestConfig.app(config);
            assertEquals("ABCDEFAB-1111-2222-3333-444444444444", MsiPackager.upgradeCode(app, config.windows()));
            Element pkg = (Element) parse(wxs(config)).getElementsByTagName("Package").item(0);
            assertEquals("ABCDEFAB-1111-2222-3333-444444444444", pkg.getAttribute("UpgradeCode"));
            assertTrue(script(config).contains("AppId={{ABCDEFAB-1111-2222-3333-444444444444}\n"), script(config));
        }
        // not a GUID : neither the package nor the setup program is generated
        for (String written : List.of("my-application", "{", "{}", "{{abcdefab-1111-2222-3333-444444444444}",
                "abcdefab111122223333444444444444", "1-2-3-4-5", "abcdefab-1111-2222-3333-44444444444g")) {
            DesktopPackagingConfig config = TestConfig.of(Map.of("windows.upgrade-code", written));
            PackagingException e = assertThrows(PackagingException.class, () -> wxs(config));
            assertTrue(e.getMessage().contains("quarkus.desktop.packaging.windows.upgrade-code="), e.getMessage());
            assertThrows(PackagingException.class, () -> script(config));
        }
    }

    static String script(DesktopPackagingConfig config) {
        return InnoSetupPackager.script(TestConfig.app(config), config.windows(), Arch.X64, ROOT, "Acme Editor.exe",
                Path.of("application.ico"), Path.of("out"), "Acme-Editor");
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
        assertTrue(script.contains("Description: \"{cm:LaunchProgram,Acme Editor}\"; Flags: nowait postinstall"
                + " skipifsilent\n"), script);
        // nothing to sign
        assertFalse(script.contains("SignTool"), script);

        DesktopPackagingConfig perUser = TestConfig.of(Map.of("windows.per-user", "true", "windows.start-menu", "false"));
        String userScript = InnoSetupPackager.script(TestConfig.app(perUser), perUser.windows(), Arch.ARM64, ROOT,
                "Acme Editor.exe", Path.of("application.ico"), Path.of("out"), "Acme-Editor");
        assertTrue(userScript.contains("PrivilegesRequired=lowest\n"), userScript);
        assertTrue(userScript.contains("ArchitecturesAllowed=arm64\n"), userScript);
        assertFalse(userScript.contains("{autoprograms}"), userScript);
        assertFalse(userScript.contains("[Tasks]"), userScript);
    }

    /**
     * The name of the application in the message of the last page of the wizard : the argument of a constant.
     */
    @Test
    void theInnoSetupScriptEncodesTheArgumentOfTheLaunchMessage() {
        DesktopPackagingConfig config = TestConfig.defaults();
        AppInfo app = new AppInfo("100% {Fun}, A&B|C", "fun", "org.acme.Fun", "1.0", "Acme", "Fun", Optional.empty(),
                Optional.empty(), Optional.empty());
        String script = InnoSetupPackager.script(app, config.windows(), Arch.X64, ROOT, "Fun.exe",
                Path.of("application.ico"), Path.of("out"), "Fun-1.0");
        assertTrue(script.contains("Description: \"{cm:LaunchProgram,100%25 {{Fun%7d%2c A&&B%7cC}\"; Flags:"), script);
        // elsewhere the name is not the argument of a constant
        assertTrue(script.contains("AppName=100% {{Fun}, A&B|C\n"), script);
        assertTrue(script.contains("Name: \"{autoprograms}\\100% {{Fun}, A&B|C\"; Filename:"), script);
    }

    /**
     * With a certificate, the compiler of Inno Setup signs the uninstaller and the setup program with a sign tool,
     * the command of {@code signtool} : the quotes of its arguments are {@code $q}, its dollar signs {@code $$}.
     */
    @Test
    void theSignToolOfTheSetupProgram() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("windows.sign.thumbprint",
                "0123456789abcdef0123456789abcdef01234567"));
        String script = script(config);
        assertTrue(script.contains("\nSignTool=quarkus\n"), script);
        assertTrue(script.indexOf("SignTool=") < script.indexOf("[Languages]"), script);

        assertEquals("$qC:\\Program Files (x86)\\Windows Kits\\10\\bin\\signtool.exe$q $qsign$q $q/fd$q $qSHA256$q $q/f$q"
                + " $qC:\\acme\\acme.pfx$q $q/p$q $qPa$$$$w0rd $$f$q $q/n$q $qAcme \\$qEditor\\$q Inc\\\\$q $f",
                InnoSetupPackager.signTool(List.of("C:\\Program Files (x86)\\Windows Kits\\10\\bin\\signtool.exe", "sign",
                        "/fd", "SHA256", "/f", "C:\\acme\\acme.pfx", "/p", "Pa$$w0rd $f", "/n", "Acme \"Editor\" Inc\\")));
    }

    /**
     * The command of {@code signtool} : the options of the configuration, then its arguments ; the description of an
     * MSI package.
     */
    @Test
    void theCommandOfSigntool() {
        Path signtool = Path.of("signtool.exe");
        Path project = Path.of("project");
        assertFalse(WindowsSdk.signs(TestConfig.defaults().windows().sign()));

        DesktopPackagingConfig.Sign file = TestConfig.of(Map.of("windows.sign.certificate-file", "acme.pfx",
                "windows.sign.certificate-password", "secret", "windows.sign.timestamp-url", "http://timestamp.acme.org"))
                .windows().sign();
        assertTrue(WindowsSdk.signs(file));
        assertEquals(List.of(signtool.toAbsolutePath().toString(), "sign", "/fd", "SHA256", "/d", "Acme Editor", "/f",
                project.resolve("acme.pfx").toAbsolutePath().toString(), "/p", "secret", "/tr", "http://timestamp.acme.org",
                "/td", "SHA256"), WindowsSdk.command(signtool, file, project, "Acme Editor"));
        assertFalse(WindowsSdk.command(signtool, file, project, null).contains("/d"));

        // a signing service : arguments alone sign
        DesktopPackagingConfig.Sign service = TestConfig.of(Map.of("windows.sign.timestamp-url",
                "http://timestamp.acs.microsoft.com", "windows.sign.arguments",
                "/dlib, C:/tools/Azure.CodeSigning.Dlib.dll,/dmdf,C:/tools/metadata.json")).windows().sign();
        assertTrue(WindowsSdk.signs(service));
        assertEquals(List.of(signtool.toAbsolutePath().toString(), "sign", "/fd", "SHA256", "/tr",
                "http://timestamp.acs.microsoft.com", "/td", "SHA256", "/dlib", "C:/tools/Azure.CodeSigning.Dlib.dll",
                "/dmdf", "C:/tools/metadata.json"), WindowsSdk.command(signtool, service, project, null));

        // the description of the arguments, rather than the one of the package : signtool refuses an option twice
        DesktopPackagingConfig.Sign described = TestConfig.of(Map.of("windows.sign.thumbprint", "0123ABCD",
                "windows.sign.arguments", "/sm,/d,Acme")).windows().sign();
        assertEquals(List.of(signtool.toAbsolutePath().toString(), "sign", "/fd", "SHA256", "/sha1", "0123ABCD", "/sm",
                "/d", "Acme"), WindowsSdk.command(signtool, described, project, "Acme Editor"));
    }

    /**
     * The files of the application image that are signed : the launcher, and the libraries and executables without
     * signature.
     */
    @Test
    void theFilesOfTheApplicationImageToSign(@TempDir Path root) throws Exception {
        byte[] unsigned = PeImportsTest.library(0x200);
        ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN).putInt(0x58 + 108, 16);
        byte[] signed = unsigned.clone();
        ByteBuffer.wrap(signed).order(ByteOrder.LITTLE_ENDIAN).putInt(0x58 + 112 + 32, 0x400).putInt(0x58 + 112 + 36, 1400);
        Files.write(root.resolve("Acme Editor.exe"), new byte[] { 1 });
        Files.write(root.resolve("jvm.dll"), unsigned);
        Files.write(root.resolve("awt.dll"), signed);
        Files.writeString(root.resolve("notes.dll"), "not a library");
        Files.createDirectories(root.resolve("runtime/bin"));
        Files.write(root.resolve("runtime/bin/JAVA.DLL"), unsigned);
        Files.createDirectories(root.resolve("app"));
        Files.write(root.resolve("app/data.txt"), unsigned);
        assertEquals(List.of(root.resolve("Acme Editor.exe"), root.resolve("jvm.dll"), root.resolve("runtime/bin/JAVA.DLL")),
                WindowsSdk.unsigned(new ApplicationImage(root, "Acme Editor.exe", true)));
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
     * The capabilities of the MSIX manifest : trimmed, once, of their kind, {@code runFullTrust} always, the device
     * capabilities last.
     */
    @Test
    void theCapabilitiesOfTheMsixManifest() throws Exception {
        assertEquals(List.of("<rescap:Capability Name=\"runFullTrust\" />"),
                MsixPackager.capabilities(TestConfig.defaults().windows().msix()));
        DesktopPackagingConfig config = TestConfig.of(Map.of("windows.msix.capabilities", "internetClient, allowElevation ,"
                + " internetClient, rescap:packageQuery, uap:documentsLibrary, device:microphone, removableStorage"));
        assertEquals(List.of("<Capability Name=\"internetClient\" />", "<uap:Capability Name=\"documentsLibrary\" />",
                "<uap:Capability Name=\"removableStorage\" />", "<rescap:Capability Name=\"allowElevation\" />",
                "<rescap:Capability Name=\"packageQuery\" />", "<rescap:Capability Name=\"runFullTrust\" />",
                "<DeviceCapability Name=\"microphone\" />"), MsixPackager.capabilities(config.windows().msix()));
        Document document = parse(MsixPackager.manifest(TestConfig.app(config), config.windows().msix(), Arch.X64,
                "Acme Editor.exe"));
        Element capabilities = (Element) document.getElementsByTagName("Capabilities").item(0);
        Element last = null;
        for (int i = 0; i < capabilities.getChildNodes().getLength(); i++) {
            if (capabilities.getChildNodes().item(i) instanceof Element element) {
                last = element;
            }
        }
        assertEquals("DeviceCapability", last.getLocalName());
        // a capability without kind that is not known, or of an unknown kind
        for (String capability : List.of("runFullTrust,microphone", "runFulTrust", "uap3:x", "rescap:a b")) {
            DesktopPackagingConfig wrong = TestConfig.of(Map.of("windows.msix.capabilities", capability));
            PackagingException e = assertThrows(PackagingException.class,
                    () -> MsixPackager.capabilities(wrong.windows().msix()));
            assertTrue(e.getMessage().contains("quarkus.desktop.packaging.windows.msix.capabilities"), e.getMessage());
        }
    }

    /**
     * The mapping file of the MSIX package : only the paths of the generated files are refused in the image.
     */
    @Test
    void theMsixMappingFile() {
        Map<String, Path> generated = Map.of("AppxManifest.xml", Path.of("work", "AppxManifest.xml"),
                "Assets\\StoreLogo.png", Path.of("work", "Assets", "StoreLogo.png"));
        String mapping = MsixPackager.mapping(generated, ROOT, List.of(Path.of("Acme Editor.exe"),
                Path.of("assets", "logo.png"), Path.of("Assets", "sounds", "beep.wav"), Path.of("app", "AppxManifest.xml")));
        assertTrue(mapping.startsWith("[Files]\n"), mapping);
        assertTrue(mapping.contains("\"" + Path.of("work", "Assets", "StoreLogo.png") + "\" \"Assets\\StoreLogo.png\"\n"),
                mapping);
        // a directory named as the one of the logos is a directory of the image like the others
        assertTrue(mapping.contains("\"" + ROOT.resolve("assets").resolve("logo.png") + "\" \"assets\\logo.png\"\n"),
                mapping);
        assertTrue(mapping.contains("\" \"Assets\\sounds\\beep.wav\"\n"), mapping);
        assertTrue(mapping.contains("\" \"app\\AppxManifest.xml\"\n"), mapping);
        for (Path file : List.of(Path.of("appxmanifest.xml"), Path.of("ASSETS", "storelogo.png"))) {
            PackagingException e = assertThrows(PackagingException.class,
                    () -> MsixPackager.mapping(generated, ROOT, List.of(file)));
            assertTrue(e.getMessage().contains("rename"), e.getMessage());
        }
    }

    /**
     * signtool only reports {@code 0x8007000b} when the publisher of the package is not the subject of the
     * certificate : the failure says so.
     */
    @Test
    void theFailureOfTheSignatureOfAnMsixPackageNamesThePublisher() {
        PackagingException mismatch = new PackagingException("signtool failed to sign a.msix (exit code 1) :\n"
                + "Done Adding Additional Store\n"
                + "Error information: \"Error: SignerSign() failed.\" (-2147024885/0x8007000b)\n"
                + "SignTool Error: An unexpected internal error has occurred.");
        PackagingException explained = MsixPackager.signatureFailure(mismatch, "CN=Acme");
        assertTrue(explained.getMessage().startsWith(mismatch.getMessage()), explained.getMessage());
        assertTrue(explained.getMessage().contains("(CN=Acme)"), explained.getMessage());
        assertTrue(explained.getMessage().contains("quarkus.desktop.packaging.windows.msix.publisher"),
                explained.getMessage());
        assertSame(mismatch, explained.getCause());
        PackagingException timestamp = new PackagingException("signtool failed to sign a.msix (exit code 1) :\n"
                + "SignTool Error: The specified timestamp server either could not be reached or\n"
                + "returned an invalid response.");
        assertSame(timestamp, MsixPackager.signatureFailure(timestamp, "CN=Acme"));
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
