package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.Tools;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * A Windows Installer package ({@code .msi}), built with the WiX Toolset 4 or later ({@code wix build}) from a
 * generated source : the files of the application image, the shortcuts, and a major upgrade (the package of a new
 * version replaces the installed one).
 */
public final class MsiPackager implements Packager {

    static final String LAUNCHER_FILE = "LauncherFile";

    private static final String ICON = "ApplicationIcon.ico";

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        // the directory of the .NET global tools is on the PATH of the sessions opened after the first installation
        Path wix = context.tools().require("wix",
                "install the WiX Toolset 4 or later, with the .NET SDK : dotnet tool install --global wix",
                Path.of(System.getProperty("user.home"), ".dotnet", "tools", "wix.exe"));
        Path work = context.workDirectory("msi");
        Path icon = FileTrees.write(work.resolve("application.ico"), context.icons().ico());
        String source = wxs(context.app(), context.config().windows(), image.root(), image.launcher(), icon,
                FileTrees.files(image.root()));
        Path wxs = FileTrees.write(work.resolve("application.wxs"), source);
        Path msi = context.output(context.app().fileName() + "-" + context.app().version() + ".msi");
        Tools.exec(work, Map.of(), List.of(wix.toString(), "build", "-arch", context.arch().windows(), "-pdbtype", "none",
                "-intermediatefolder", work.resolve("obj").toString(), "-o", msi.toString(), wxs.toString()));
        WindowsSdk.sign(context, msi);
        return msi;
    }

    /**
     * The version of a Windows Installer product : three numbers, of at most 255, 255 and 65535.
     */
    static String version(AppInfo app) {
        String[] parts = app.numericVersion(3).split("\\.");
        return Math.min(255, Long.parseLong(parts[0])) + "." + Math.min(255, Long.parseLong(parts[1])) + "."
                + Math.min(65535, Long.parseLong(parts[2]));
    }

    /**
     * A directory of the application image : its files and its sub directories, by name.
     */
    private static final class Directory {

        final List<Path> files = new ArrayList<>();

        final Map<String, Directory> directories = new TreeMap<>();
    }

    /**
     * The identifiers of the generated elements, and the components to add to the feature.
     */
    private static final class Ids {

        final List<String> components = new ArrayList<>();

        int next;

        String next(String prefix) {
            return prefix + (++next);
        }
    }

    /**
     * The WiX source of the package.
     *
     * @param root the directory of the application image
     * @param launcher the path of the executable in the application image, with {@code /} separators
     * @param icon the icon of the application in the installed programs
     * @param files the files of the application image, relative to its directory
     */
    static String wxs(AppInfo app, DesktopPackagingConfig.Windows config, Path root, String launcher, Path icon,
            List<Path> files) {
        boolean perUser = config.perUser();
        String registryKey = "Software\\" + app.vendor() + "\\" + app.name();
        Directory tree = new Directory();
        for (Path file : files) {
            Directory directory = tree;
            for (int i = 0; i < file.getNameCount() - 1; i++) {
                directory = directory.directories.computeIfAbsent(file.getName(i).toString(), name -> new Directory());
            }
            directory.files.add(file);
        }
        Ids ids = new Ids();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\">\n");
        xml.append("  <Package Name=\"").append(Xml.escape(app.name())).append("\" Manufacturer=\"")
                .append(Xml.escape(app.vendor())).append("\" Version=\"").append(version(app)).append("\" UpgradeCode=\"")
                .append(config.upgradeCode().orElseGet(() -> app.guid("upgrade"))).append("\" Language=\"1033\" Scope=\"")
                .append(perUser ? "perUser" : "perMachine").append("\" InstallerVersion=\"500\">\n");
        xml.append("    <SummaryInformation Description=\"").append(Xml.escape(app.description())).append("\" />\n");
        // a new version replaces the installed one ; a rebuild of the same version too
        xml.append("    <MajorUpgrade AllowSameVersionUpgrades=\"yes\" DowngradeErrorMessage=\"A newer version of")
                .append(" [ProductName] is already installed.\" />\n");
        xml.append("    <MediaTemplate EmbedCab=\"yes\" CompressionLevel=\"high\" />\n");
        xml.append("    <Icon Id=\"").append(ICON).append("\" SourceFile=\"").append(Xml.escape(icon.toString()))
                .append("\" />\n");
        xml.append("    <Property Id=\"ARPPRODUCTICON\" Value=\"").append(ICON).append("\" />\n");
        app.homepage().ifPresent(homepage -> xml.append("    <Property Id=\"ARPURLINFOABOUT\" Value=\"")
                .append(Xml.escape(homepage)).append("\" />\n"));

        String installDirectory = Xml.escape(config.installDirectory().orElse(app.name()));
        if (perUser) {
            xml.append("    <StandardDirectory Id=\"LocalAppDataFolder\">\n");
            xml.append("      <Directory Id=\"UserProgramsFolder\" Name=\"Programs\">\n");
        } else {
            xml.append("    <StandardDirectory Id=\"ProgramFiles6432Folder\">\n");
        }
        String indent = perUser ? "        " : "      ";
        xml.append(indent).append("<Directory Id=\"INSTALLFOLDER\" Name=\"").append(installDirectory).append("\">\n");
        directory(xml, indent + "  ", tree, "INSTALLFOLDER", app, root, launcher, perUser, registryKey, ids);
        xml.append(indent).append("</Directory>\n");
        if (perUser) {
            xml.append("      </Directory>\n");
        }
        xml.append("    </StandardDirectory>\n");

        if (config.startMenu()) {
            xml.append("    <StandardDirectory Id=\"ProgramMenuFolder\">\n");
            String shortcutIndent = "      ";
            if (config.startMenuGroup().isPresent()) {
                xml.append("      <Directory Id=\"StartMenuGroup\" Name=\"")
                        .append(Xml.escape(config.startMenuGroup().get())).append("\">\n");
                shortcutIndent = "        ";
            }
            shortcut(xml, shortcutIndent, "StartMenuShortcut", app, registryKey, config.startMenuGroup().isPresent());
            if (config.startMenuGroup().isPresent()) {
                xml.append("      </Directory>\n");
            }
            xml.append("    </StandardDirectory>\n");
            ids.components.add("StartMenuShortcut");
        }
        if (config.desktopShortcut()) {
            xml.append("    <StandardDirectory Id=\"DesktopFolder\">\n");
            shortcut(xml, "      ", "DesktopShortcut", app, registryKey, false);
            xml.append("    </StandardDirectory>\n");
            ids.components.add("DesktopShortcut");
        }

        xml.append("    <Feature Id=\"Application\" Title=\"").append(Xml.escape(app.name())).append("\" Level=\"1\">\n");
        for (String component : ids.components) {
            xml.append("      <ComponentRef Id=\"").append(component).append("\" />\n");
        }
        xml.append("    </Feature>\n");
        xml.append("  </Package>\n");
        xml.append("</Wix>\n");
        return xml.toString();
    }

    /**
     * The components of the files of a directory, and its sub directories. A component has one file, which makes its
     * GUID stable : WiX derives it from the path of the installed file. In a per-user package, the files are in the
     * profile of the user : Windows Installer then wants a registry value of the user as the key path of each
     * component, and the removal of each directory ; WiX does not generate the GUID of such a component, which is
     * derived here from the identifier of the application and the path of the file.
     */
    private static void directory(StringBuilder xml, String indent, Directory directory, String directoryId, AppInfo app,
            Path root, String launcher, boolean perUser, String registryKey, Ids ids) {
        if (perUser) {
            String component = ids.next("r");
            ids.components.add(component);
            xml.append(indent).append("<Component Id=\"").append(component).append("\">\n");
            xml.append(indent).append("  <RemoveFolder Id=\"").append(component).append("\" Directory=\"")
                    .append(directoryId).append("\" On=\"uninstall\" />\n");
            registryKeyPath(xml, indent + "  ", registryKey + "\\Components", component);
            xml.append(indent).append("</Component>\n");
        }
        for (Path file : directory.files) {
            String component = ids.next("c");
            ids.components.add(component);
            String path = file.toString().replace('\\', '/');
            boolean isLauncher = path.equals(launcher);
            xml.append(indent).append("<Component Id=\"").append(component).append("\"")
                    .append(perUser ? " Guid=\"" + app.guid("component:" + path) + "\"" : "").append(">\n");
            xml.append(indent).append("  <File Id=\"").append(isLauncher ? LAUNCHER_FILE : ids.next("f"))
                    .append("\" Source=\"").append(Xml.escape(root.resolve(file).toString())).append("\"")
                    .append(perUser ? " KeyPath=\"no\"" : "").append(" />\n");
            if (perUser) {
                registryKeyPath(xml, indent + "  ", registryKey + "\\Components", component);
            }
            xml.append(indent).append("</Component>\n");
        }
        for (Map.Entry<String, Directory> child : directory.directories.entrySet()) {
            String id = ids.next("d");
            xml.append(indent).append("<Directory Id=\"").append(id).append("\" Name=\"")
                    .append(Xml.escape(child.getKey())).append("\">\n");
            directory(xml, indent + "  ", child.getValue(), id, app, root, launcher, perUser, registryKey, ids);
            xml.append(indent).append("</Directory>\n");
        }
    }

    /**
     * A shortcut to the launcher. The key path of the component of a shortcut is a registry value of the user.
     */
    private static void shortcut(StringBuilder xml, String indent, String id, AppInfo app, String registryKey,
            boolean removeFolder) {
        xml.append(indent).append("<Component Id=\"").append(id).append("\">\n");
        xml.append(indent).append("  <Shortcut Id=\"").append(id).append("\" Name=\"").append(Xml.escape(app.name()))
                .append("\" Description=\"").append(Xml.escape(app.description())).append("\" Target=\"[#")
                .append(LAUNCHER_FILE).append("]\" WorkingDirectory=\"INSTALLFOLDER\" Icon=\"").append(ICON)
                .append("\" />\n");
        if (removeFolder) {
            xml.append(indent).append("  <RemoveFolder Id=\"Remove").append(id).append("Folder\" On=\"uninstall\" />\n");
        }
        registryKeyPath(xml, indent + "  ", registryKey, id);
        xml.append(indent).append("</Component>\n");
    }

    private static void registryKeyPath(StringBuilder xml, String indent, String key, String name) {
        xml.append(indent).append("<RegistryValue Root=\"HKCU\" Key=\"").append(Xml.escape(key)).append("\" Name=\"")
                .append(name).append("\" Type=\"integer\" Value=\"1\" KeyPath=\"yes\" />\n");
    }
}
