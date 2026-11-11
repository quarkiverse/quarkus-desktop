package io.quarkiverse.desktop.packaging.deployment.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.regex.Pattern;

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
import io.quarkiverse.desktop.packaging.deployment.PackageFormat;
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
        // not over the setup program of the application, whose uninstall key is named after the upgrade code
        Element search = (Element) document.getElementsByTagName("RegistrySearch").item(0);
        assertEquals("INNOSETUPINSTALLED", ((Element) search.getParentNode()).getAttribute("Id"));
        assertEquals("HKLM", search.getAttribute("Root"));
        assertEquals("Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{" + TestConfig.app(config).guid("upgrade")
                + "}_is1", search.getAttribute("Key"));
        assertEquals("Installed OR NOT INNOSETUPINSTALLED",
                ((Element) document.getElementsByTagName("Launch").item(0)).getAttribute("Condition"));
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
        // each directory is removed : the installation directory, app and app\lib, the folder of the shortcut, and the
        // Programs directory of the profile, which the package may have created (ICE64)
        assertEquals(3 + 2, document.getElementsByTagName("RemoveFolder").getLength());
        assertTrue(removedFolders(document).contains("UserProgramsFolder"), removedFolders(document).toString());
        // the setup program of the application is installed for the current user too
        Element search = (Element) document.getElementsByTagName("RegistrySearch").item(0);
        assertEquals("HKCU", search.getAttribute("Root"));
        assertEquals("Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{11111111-2222-3333-4444-555555555555}_is1",
                search.getAttribute("Key"));
        assertEquals(wxs(config), xml);
    }

    /**
     * The directories of the {@code Directory} attribute of the {@code RemoveFolder} elements.
     */
    static List<String> removedFolders(Document document) {
        List<String> directories = new ArrayList<>();
        NodeList folders = document.getElementsByTagName("RemoveFolder");
        for (int i = 0; i < folders.getLength(); i++) {
            directories.add(((Element) folders.item(i)).getAttribute("Directory"));
        }
        return directories;
    }

    /**
     * An installation directory and a folder of the Start menu with a path : nested directories, the parents of which
     * are removed from the profile of the user.
     */
    @Test
    void theWixSourceOfNestedDirectories() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("windows.per-user", "true", "windows.install-directory",
                "Acme\\Editor", "windows.start-menu-group", "Acme/Tools/"));
        Document document = parse(wxs(config));
        Element installFolder = directory(document, "INSTALLFOLDER");
        assertEquals("Editor", installFolder.getAttribute("Name"));
        Element installParent = (Element) installFolder.getParentNode();
        assertEquals("Acme", installParent.getAttribute("Name"));
        assertEquals("UserProgramsFolder", ((Element) installParent.getParentNode()).getAttribute("Id"));
        Element group = directory(document, "StartMenuGroup");
        assertEquals("Tools", group.getAttribute("Name"));
        Element groupParent = (Element) group.getParentNode();
        assertEquals("Acme", groupParent.getAttribute("Name"));
        assertEquals("ProgramMenuFolder", ((Element) groupParent.getParentNode()).getAttribute("Id"));
        NodeList directories = document.getElementsByTagName("Directory");
        for (int i = 0; i < directories.getLength(); i++) {
            String name = ((Element) directories.item(i)).getAttribute("Name");
            assertFalse(name.contains("\\") || name.contains("/"), name);
        }
        List<String> removed = removedFolders(document);
        assertTrue(removed.containsAll(List.of("UserProgramsFolder", installParent.getAttribute("Id"),
                groupParent.getAttribute("Id"))), removed.toString());

        // for all users, only the folder of the Start menu (the directory of the component of the shortcut) and its
        // parent are removed : the ICE checks take the Start menu as a directory of the profile
        DesktopPackagingConfig perMachine = TestConfig.of(Map.of("windows.install-directory", "Acme\\Editor",
                "windows.start-menu-group", "Acme\\Tools"));
        Document machineDocument = parse(wxs(perMachine));
        assertEquals("Acme", ((Element) directory(machineDocument, "INSTALLFOLDER").getParentNode()).getAttribute("Name"));
        assertEquals("StartMenuGroup1", ((Element) directory(machineDocument, "StartMenuGroup").getParentNode())
                .getAttribute("Id"));
        assertEquals(List.of("", "StartMenuGroup1"), removedFolders(machineDocument));

        // no name
        for (Map.Entry<String, String> path : Map.of("windows.install-directory", "\\", "windows.start-menu-group", "/")
                .entrySet()) {
            DesktopPackagingConfig empty = TestConfig.of(Map.of(path.getKey(), path.getValue()));
            PackagingException e = assertThrows(PackagingException.class, () -> wxs(empty));
            assertTrue(e.getMessage().contains("quarkus.desktop.packaging." + path.getKey() + "="), e.getMessage());
        }
    }

    /**
     * The {@code Directory} element of an identifier.
     */
    static Element directory(Document document, String id) {
        NodeList directories = document.getElementsByTagName("Directory");
        for (int i = 0; i < directories.getLength(); i++) {
            if (((Element) directories.item(i)).getAttribute("Id").equals(id)) {
                return (Element) directories.item(i);
            }
        }
        throw new AssertionError("No directory " + id);
    }

    /**
     * The texts of the WiX source are escaped for the variables that WiX expands in every attribute : a dollar sign is
     * doubled, an exclamation mark before a variable of WiX, as WiX 5 reads them.
     */
    @Test
    void theWixSourceEscapesTheVariablesOfWix() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "A$$B", "description",
                "Costs $(5), $$ and !(loc.Foo) or Hello!(beta)"));
        List<Path> files = new ArrayList<>(FILES);
        files.add(Path.of("pay$(x).txt"));
        String xml = MsiPackager.wxs(TestConfig.app(config), config.windows(), ROOT, "Acme Editor.exe",
                Path.of("application.ico"), files);
        assertTrue(xml.contains("Manufacturer=\"A$$$$B\""), xml);
        assertTrue(xml.contains("<SummaryInformation Description=\"Costs $$(5), $$$$ and !!(loc.Foo) or Hello!(beta)\""),
                xml);
        assertTrue(xml.contains("Description=\"Costs $$(5), $$$$ and !!(loc.Foo) or Hello!(beta)\" Target="), xml);
        assertTrue(xml.contains("Source=\"" + ROOT.resolve("pay$(x).txt").toString().replace("$", "$$") + "\""), xml);

        assertEquals("end$$", MsiPackager.text("end$"));
        assertEquals("A &amp; B $$$$", MsiPackager.text("A & B $$"));
        assertEquals("a!!!(loc.x)", MsiPackager.text("a!!(loc.x)"));
        assertEquals("!!(wix.Foo) !!(bind.FileVersion.f1) !!(loc.x=a b) !!(loc._x.y.z)",
                MsiPackager.text("!(wix.Foo) !(bind.FileVersion.f1) !(loc.x=a b) !(loc._x.y.z)"));
        // not variables : WiX reads !!( as an exclamation mark before a variable only, and takes the text as it is after
        // an !( that is not a variable
        for (String text : List.of("Hello!(beta)", "!(LOC.x)", "!(loc.1x)", "!(loc.x B)", "!(loc.x.)", "!(bindpath.x)",
                "!(loc.x B", "Hello!(beta) !(loc.Foo)")) {
            assertEquals(text, MsiPackager.text(text));
        }
        assertEquals("!!(loc.x) a!(b.c) !(loc.y)", MsiPackager.text("!(loc.x) a!(b.c) !(loc.y)"));
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
            // the same code in the checks of the other installer
            assertTrue(wxs(config).contains("Uninstall\\{ABCDEFAB-1111-2222-3333-444444444444}_is1\""), wxs(config));
            assertTrue(script(config).contains("MsiEnumRelatedProducts('{ABCDEFAB-1111-2222-3333-444444444444}', 0, 0,"),
                    script(config));
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
                Path.of("application.ico"), Path.of("out"), "Acme-Editor", FILES, true);
    }

    @Test
    void theInnoSetupScript() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme", "homepage", "https://acme.org",
                "windows.desktop-shortcut", "true"));
        AppInfo app = TestConfig.app(config);
        String script = InnoSetupPackager.script(app, config.windows(), Arch.X64, ROOT, "Acme Editor.exe",
                Path.of("application.ico"), Path.of("out"), "Acme-Editor-1.2.3-SNAPSHOT",
                List.of(Path.of("Acme Editor.exe"), Path.of("lib", "a.dll"), Path.of("{x}", "b.txt")), true);
        // not through the Inno Setup Preprocessor : the directive is the first line
        assertTrue(script.startsWith("#preproc builtin\n"), script);
        assertTrue(script.contains("AppId={{" + app.guid("upgrade") + "}\n"), script);
        assertTrue(script.contains("AppName=Acme Editor\n"), script);
        assertTrue(script.contains("AppVersion=1.2.3-SNAPSHOT\n"), script);
        assertTrue(script.contains("VersionInfoVersion=1.2.3.0\n"), script);
        assertTrue(script.contains("DefaultDirName={autopf}\\Acme Editor\n"), script);
        assertTrue(script.contains("PrivilegesRequired=admin\n"), script);
        assertTrue(script.contains("ArchitecturesAllowed=x64compatible\n"), script);
        assertTrue(script.contains("OutputBaseFilename=Acme-Editor-1.2.3-SNAPSHOT\n"), script);
        // a line per file, rather than a wildcard, which leaves out the hidden files
        assertTrue(script.contains("Source: \"" + ROOT.resolve("Acme Editor.exe") + "\"; DestDir: \"{app}\"; Flags:"
                + " ignoreversion\n"), script);
        assertTrue(script.contains("Source: \"" + ROOT.resolve("lib").resolve("a.dll") + "\"; DestDir: \"{app}\\lib\";"
                + " Flags: ignoreversion\n"), script);
        assertTrue(script.contains("Source: \"" + ROOT.resolve("{x}").resolve("b.txt") + "\"; DestDir: \"{app}\\{{x}\";"
                + " Flags: ignoreversion\n"), script);
        assertFalse(script.contains("\\*\""), script);
        assertFalse(script.contains("recursesubdirs"), script);
        // a native executable : no directory of jpackage to delete
        assertFalse(script.contains("[InstallDelete]"), script);
        assertTrue(script.contains("Name: \"{autoprograms}\\Acme Editor\"; Filename: \"{app}\\Acme Editor.exe\""), script);
        assertTrue(script.contains("Name: \"{autodesktop}\\Acme Editor\""), script);
        assertTrue(script.contains("[Tasks]"), script);
        assertTrue(script.contains("Description: \"{cm:LaunchProgram,Acme Editor}\"; Flags: nowait postinstall"
                + " skipifsilent\n"), script);
        // nothing to sign
        assertFalse(script.contains("SignTool"), script);
        // not over the MSI package of the application : the products of its upgrade code
        assertTrue(script.contains("external 'MsiEnumRelatedProductsW@msi.dll stdcall';\n"), script);
        assertTrue(script.contains("  Result := MsiEnumRelatedProducts('{" + app.guid("upgrade") + "}', 0, 0, ProductCode)"
                + " <> 0;\n"), script);
        assertTrue(script.contains("SuppressibleMsgBox('Acme Editor is installed by its MSI package : remove it first.',"
                + " mbCriticalError, MB_OK, IDOK);\n"), script);
        assertTrue(script.indexOf("[Code]") > script.indexOf("[Run]"), script);

        DesktopPackagingConfig perUser = TestConfig.of(Map.of("windows.per-user", "true", "windows.start-menu", "false"));
        String userScript = InnoSetupPackager.script(TestConfig.app(perUser), perUser.windows(), Arch.ARM64, ROOT,
                "Acme Editor.exe", Path.of("application.ico"), Path.of("out"), "Acme-Editor", FILES, false);
        assertTrue(userScript.contains("PrivilegesRequired=lowest\n"), userScript);
        assertTrue(userScript.contains("ArchitecturesAllowed=arm64\n"), userScript);
        assertFalse(userScript.contains("{autoprograms}"), userScript);
        assertFalse(userScript.contains("[Tasks]"), userScript);
        // the launcher of jpackage : the setup program deletes the jars and the Java runtime of the installed version
        assertTrue(userScript.contains("[InstallDelete]\nType: filesandordirs; Name: \"{app}\\app\"\n"
                + "Type: filesandordirs; Name: \"{app}\\runtime\"\n"), userScript);
        assertTrue(userScript.indexOf("[InstallDelete]") < userScript.indexOf("[Files]"), userScript);
    }

    /**
     * The name of the application in the message of the setup program when the MSI package is installed : a string of
     * the code, whose quotes are doubled, and whose braces are not constants.
     */
    @Test
    void theInnoSetupScriptNamesTheApplicationInItsCode() {
        AppInfo app = new AppInfo("Acme's {Editor}", "acme", "org.acme.Editor", "1.0", "Acme", "Edits", Optional.empty(),
                Optional.empty(), Optional.empty());
        String script = InnoSetupPackager.script(app, TestConfig.defaults().windows(), Arch.X64, ROOT, "Editor.exe",
                Path.of("application.ico"), Path.of("out"), "Editor-1.0", FILES, true);
        assertTrue(script.contains("    SuppressibleMsgBox('Acme''s {Editor} is installed by its MSI package : remove it"
                + " first.', mbCriticalError, MB_OK, IDOK);\n"), script);
        assertTrue(script.contains("AppName=Acme's {{Editor}\n"), script);
    }

    /**
     * The values of the script are not evaluated by the Inno Setup Preprocessor ({@code {#...}}), and a line that ends
     * with a backslash is not joined to the next one : the script uses the built-in preprocessor.
     */
    @Test
    void theInnoSetupScriptIsNotPreprocessed() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("name", "Acme {#1+1}", "copyright", "(c) Acme \\",
                "vendor", "Acme{#Exec('cmd.exe'),'tmp'}}"));
        String script = script(config);
        assertTrue(script.startsWith("#preproc builtin\n; Generated by"), script);
        assertTrue(script.contains("AppName=Acme {{#1+1}\n"), script);
        assertTrue(script.contains("AppCopyright=(c) Acme \\\nVersionInfoVersion=1.2.3.0\n"), script);
        assertTrue(script.contains("AppPublisher=Acme{{#Exec('cmd.exe'),'tmp'}}\n"), script);
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
                Path.of("application.ico"), Path.of("out"), "Fun-1.0", FILES, true);
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
     * The log of the signature : what signs, and the warnings of a configuration that does not sign although it has
     * another property of the signature, and of signatures without time stamp.
     */
    @Test
    void theLogOfTheSignature() {
        DesktopPackagingConfig.Sign none = TestConfig.defaults().windows().sign();
        assertEquals(Optional.empty(), WindowsSdk.unsignedWarning(none));
        assertEquals(Optional.empty(), WindowsSdk.timestampWarning(none));

        // no certificate, or an empty one, such as the expression of a variable that the build does not define
        for (Map<String, String> properties : List.of(Map.of("windows.sign.timestamp-url", "http://timestamp.digicert.com"),
                Map.of("windows.sign.certificate-password", "hunter2"),
                Map.of("windows.sign.certificate-file", "", "windows.sign.certificate-password", "hunter2"))) {
            DesktopPackagingConfig.Sign sign = TestConfig.of(properties).windows().sign();
            assertFalse(WindowsSdk.signs(sign), properties.toString());
            String warning = WindowsSdk.unsignedWarning(sign).orElseThrow();
            assertTrue(warning.contains("quarkus.desktop.packaging.windows.sign.certificate-file"), warning);
            assertFalse(warning.contains("hunter2"), warning);
            assertEquals(Optional.empty(), WindowsSdk.timestampWarning(sign));
        }
        assertTrue(WindowsSdk.unsignedWarning(TestConfig.of(Map.of("windows.sign.timestamp-url",
                "http://timestamp.digicert.com")).windows().sign()).orElseThrow()
                .contains("quarkus.desktop.packaging.windows.sign.timestamp-url is set"));

        // a certificate : no time stamp, then one
        DesktopPackagingConfig.Sign file = TestConfig.of(Map.of("windows.sign.certificate-file", "acme.pfx",
                "windows.sign.certificate-password", "hunter2")).windows().sign();
        assertEquals(Optional.empty(), WindowsSdk.unsignedWarning(file));
        String timestamp = WindowsSdk.timestampWarning(file).orElseThrow();
        assertTrue(timestamp.contains("quarkus.desktop.packaging.windows.sign.timestamp-url"), timestamp);
        assertEquals(Optional.empty(), WindowsSdk.timestampWarning(TestConfig.of(Map.of("windows.sign.thumbprint", "0123ABCD",
                "windows.sign.timestamp-url", "http://timestamp.digicert.com")).windows().sign()));
        // the time stamp server of the arguments
        DesktopPackagingConfig.Sign service = TestConfig.of(Map.of("windows.sign.arguments",
                "/dlib,C:/tools/Azure.CodeSigning.Dlib.dll,/TR,http://timestamp.acs.microsoft.com")).windows().sign();
        assertEquals(Optional.empty(), WindowsSdk.timestampWarning(service));
        assertTrue(WindowsSdk.timestampWarning(TestConfig.of(Map.of("windows.sign.arguments", "/sm")).windows().sign())
                .isPresent());

        // what signs, without the password
        assertEquals("the certificate acme.pfx", WindowsSdk.signer(file));
        assertEquals("the certificate of subject Acme", WindowsSdk.signer(TestConfig.of(Map.of(
                "windows.sign.subject-name", "Acme")).windows().sign()));
        assertEquals("the certificate of thumbprint 0123ABCD", WindowsSdk.signer(TestConfig.of(Map.of(
                "windows.sign.thumbprint", "0123ABCD")).windows().sign()));
        assertEquals("the arguments of quarkus.desktop.packaging.windows.sign.arguments", WindowsSdk.signer(service));
        // what is signed
        assertEquals("Acme Editor.exe", WindowsSdk.signed(List.of(ROOT.resolve("Acme Editor.exe"))));
        assertEquals("Acme Editor.exe and 1 other file",
                WindowsSdk.signed(List.of(ROOT.resolve("Acme Editor.exe"), ROOT.resolve("jvm.dll"))));
        assertEquals("Acme Editor.exe and 2 other files", WindowsSdk.signed(List.of(ROOT.resolve("Acme Editor.exe"),
                ROOT.resolve("jvm.dll"), ROOT.resolve("java.dll"))));
    }

    /**
     * The file names of the Windows packages : the name of the application, its version and the architecture of the
     * package, as the Linux packages have it.
     */
    @Test
    void theFileNamesOfThePackages() {
        AppInfo app = TestConfig.app();
        assertEquals("Acme-Editor-1.2.3-SNAPSHOT-x64.msi", WindowsSdk.packageFileName(app, Arch.X64, PackageFormat.MSI));
        assertEquals("Acme-Editor-1.2.3-SNAPSHOT-x64.exe", WindowsSdk.packageFileName(app, Arch.X64, PackageFormat.EXE));
        assertEquals("Acme-Editor-1.2.3-SNAPSHOT-arm64.msix",
                WindowsSdk.packageFileName(app, Arch.ARM64, PackageFormat.MSIX));
    }

    /**
     * The WiX Toolset and Inno Setup do not read a file whose path has 260 characters or more : the build fails before
     * them, with the longest path.
     */
    @Test
    void theLengthOfThePathsOfTheApplicationImage(@TempDir Path root) {
        // the separator after the root, then the file name
        int room = WindowsSdk.MAX_PATH - 1 - root.toAbsolutePath().toString().length() - 1;
        assumeTrue(room > 10, "The temporary directory has a long path : " + root);
        WindowsSdk.checkPathLengths(root, List.of(Path.of("a.txt"), Path.of("x".repeat(room))), "The WiX Toolset");
        WindowsSdk.checkPathLengths(root, List.of(), "Inno Setup");

        Path longest = root.resolve("z".repeat(room + 2)).toAbsolutePath();
        PackagingException e = assertThrows(PackagingException.class, () -> WindowsSdk.checkPathLengths(root,
                List.of(Path.of("a.txt"), Path.of("y".repeat(room + 1)), Path.of("z".repeat(room + 2))),
                "The WiX Toolset"));
        assertTrue(e.getMessage().startsWith("The path of " + longest + " has 261 characters : The WiX Toolset does not"
                + " read a file whose path has 260 characters or more"), e.getMessage());
        assertTrue(e.getMessage().contains("shorter by 2 characters"), e.getMessage());
        assertTrue(e.getMessage().contains("quarkus.desktop.packaging.output-directory"), e.getMessage());
        // exactly 260 characters
        assertThrows(PackagingException.class,
                () -> WindowsSdk.checkPathLengths(root, List.of(Path.of("y".repeat(room + 1))), "Inno Setup"));
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

    /**
     * The empty directories of the application image, which the Windows packages do not have.
     */
    @Test
    void theEmptyDirectoriesOfTheApplicationImage(@TempDir Path root) throws Exception {
        Files.write(root.resolve("Acme Editor.exe"), new byte[] { 1 });
        Files.createDirectories(root.resolve("plugins"));
        Files.createDirectories(root.resolve("data/cache"));
        Files.writeString(root.resolve("data/notes.txt"), "notes");
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/a.dll"), "a");
        assertEquals(List.of(Path.of("data", "cache"), Path.of("plugins")), WindowsSdk.emptyDirectories(root));
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
        int general = xml.indexOf("<Capability Name=\"internetClient\"");
        int restricted = xml.indexOf("<rescap:Capability Name=\"runFullTrust\"");
        assertTrue(general >= 0 && restricted >= 0 && general < restricted, xml);
        assertEquals("Acme, Inc.", document.getElementsByTagName("PublisherDisplayName").item(0).getTextContent());

        DesktopPackagingConfig invalid = TestConfig.of(Map.of("windows.msix.identity-name", "a b"));
        assertThrows(PackagingException.class,
                () -> MsixPackager.manifest(TestConfig.app(invalid), invalid.windows().msix(), Arch.X64, "a.exe"));
    }

    /**
     * The texts of the MSIX manifest, as its schema allows them : on one line, without white space around them, of at
     * most 2048 characters for a description and 256 for a name.
     */
    @Test
    void theTextsOfTheMsixManifest() throws Exception {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme Corp \n", "description",
                " First line\n\tSecond line\r\n"));
        Document document = parse(MsixPackager.manifest(TestConfig.app(config), config.windows().msix(), Arch.X64,
                "Acme Editor.exe"));
        assertEquals("Acme Corp", document.getElementsByTagName("PublisherDisplayName").item(0).getTextContent());
        assertEquals("First line Second line", document.getElementsByTagName("Description").item(0).getTextContent());
        assertEquals("First line Second line",
                ((Element) document.getElementsByTagNameNS("*", "VisualElements").item(0)).getAttribute("Description"));

        DesktopPackagingConfig limits = TestConfig.of(Map.of("description", "x".repeat(3000),
                "windows.msix.publisher-display-name", "\tAcme " + "y".repeat(300)));
        Document limited = parse(MsixPackager.manifest(TestConfig.app(limits), limits.windows().msix(), Arch.X64,
                "Acme Editor.exe"));
        assertEquals("x".repeat(2048), limited.getElementsByTagName("Description").item(0).getTextContent());
        assertEquals("Acme " + "y".repeat(251),
                limited.getElementsByTagName("PublisherDisplayName").item(0).getTextContent());

        // only white space : the name of the application
        assertEquals("Acme Editor", MsixPackager.text(" \n\t", 2048, "Acme Editor"));
        // a control character, and a character outside the Basic Multilingual Plane at the limit
        assertEquals("a b", MsixPackager.text("a\u0001\u007fb", 10, "Acme Editor"));
        assertEquals("x".repeat(9), MsixPackager.text("x".repeat(9) + "😀", 10, "Acme Editor"));
        assertEquals("x".repeat(8) + "😀", MsixPackager.text("x".repeat(8) + "😀y", 10, "Acme Editor"));

        // a name of executable that the schema refuses, which the executable may keep from the build
        PackagingException e = assertThrows(PackagingException.class, () -> MsixPackager.manifest(TestConfig.app(config),
                config.windows().msix(), Arch.X64, "100% Editor.exe"));
        assertTrue(e.getMessage().contains("100% Editor.exe"), e.getMessage());
        assertTrue(e.getMessage().contains("quarkus.desktop.packaging.name"), e.getMessage());
        assertTrue(MsixPackager.manifest(TestConfig.app(config), config.windows().msix(), Arch.X64, "bin\\Acme Editor.exe")
                .contains("Executable=\"bin\\Acme Editor.exe\""));
    }

    /**
     * The versions of Windows of the MSIX manifest : the minimum one, and the tested one, which is not before the
     * minimum one (makeappx refuses the package then).
     */
    @Test
    void theVersionsOfWindowsOfTheMsixManifest() throws Exception {
        assertEquals(List.of("10.0.17763.0", "10.0.26100.0"), windowsVersions(TestConfig.defaults()));
        assertEquals(List.of("10.0.26200.0", "10.0.26200.0"),
                windowsVersions(TestConfig.of(Map.of("windows.msix.min-version", "10.0.26200.0"))));
        assertEquals(List.of("11.0.0.0", "11.0.0.0"),
                windowsVersions(TestConfig.of(Map.of("windows.msix.min-version", "11.0.0.0"))));
        // compared as numbers
        assertEquals(List.of("10.0.9600.0", "10.0.26100.0"),
                windowsVersions(TestConfig.of(Map.of("windows.msix.min-version", "10.0.9600.0"))));
        assertEquals(List.of("10.0.26100.1", "10.0.26100.1"),
                windowsVersions(TestConfig.of(Map.of("windows.msix.min-version", " 10.0.26100.1 "))));
        // as the schema of the manifest
        for (String version : List.of("10.0", "10.0.70000.0", "10.0.017763.0", "10.0.17763.0.1", "10.0.x.0")) {
            DesktopPackagingConfig config = TestConfig.of(Map.of("windows.msix.min-version", version));
            PackagingException e = assertThrows(PackagingException.class, () -> MsixPackager.manifest(TestConfig.app(config),
                    config.windows().msix(), Arch.X64, "Acme Editor.exe"));
            assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.windows.msix.min-version=" + version + " :"),
                    e.getMessage());
        }
    }

    /**
     * The minimum and the tested versions of Windows of the MSIX manifest of a configuration.
     */
    static List<String> windowsVersions(DesktopPackagingConfig config) throws Exception {
        Element family = (Element) parse(MsixPackager.manifest(TestConfig.app(config), config.windows().msix(), Arch.X64,
                "Acme Editor.exe")).getElementsByTagName("TargetDeviceFamily").item(0);
        return List.of(family.getAttribute("MinVersion"), family.getAttribute("MaxVersionTested"));
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
                "ProductVersion", "1.2.3-SNAPSHOT", "OriginalFilename", "Acme Editor.exe", "LegalCopyright",
                "Copyright (c) Acme", "VarFileInfo", "Translation")) {
            assertTrue(strings.contains(expected), expected);
        }
        // the file description is the name of the program for Windows (the task manager) : the name of the
        // application, rather than its description, which is a comment. A key, then the zeros of its end and padding
        assertTrue(Pattern.compile("FileDescription\u0000+Acme Editor\u0000").matcher(strings).find(), strings);
        assertTrue(Pattern.compile("Comments\u0000+Edits <documents>\u0000").matcher(strings).find(), strings);
        assertEquals(List.of(1, 2, 3, 0), List.of(WindowsResources.versionNumbers(app)[0],
                WindowsResources.versionNumbers(app)[1], WindowsResources.versionNumbers(app)[2],
                WindowsResources.versionNumbers(app)[3]));
    }

    private static AppInfo app(String version) {
        return new AppInfo("Acme Editor", "acme-editor", "org.acme.AcmeEditor", version, "Acme", "Edits documents",
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
