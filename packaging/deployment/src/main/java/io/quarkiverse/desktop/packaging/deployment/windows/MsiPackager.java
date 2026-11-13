package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Associations;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.PackageFormat;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * A Windows Installer package ({@code .msi}), built with the WiX Toolset 4 or later ({@code wix build}) from a
 * generated source : the files of the application image, the shortcuts, the file types and URL schemes, and a major
 * upgrade (the package of a new version replaces the installed one).
 */
public final class MsiPackager implements Packager {

    static final String LAUNCHER_FILE = "LauncherFile";

    private static final String ICON = "ApplicationIcon.ico";

    /**
     * The component of the file types and URL schemes.
     */
    private static final String ASSOCIATIONS = "Associations";

    /**
     * The name of a variable of WiX, and its scope : identifiers, which start with a letter or an underscore, and go on
     * with letters, digits and underscores (and dots in the scope).
     */
    private static final Pattern VARIABLE_NAME = Pattern
            .compile("[A-Za-z_][A-Za-z_\\p{Nd}]*(\\.[A-Za-z_][A-Za-z_.\\p{Nd}]*)?");

    /**
     * The length of the longest AppUserModelID : Windows refuses one of 128 characters or more
     * ({@code SetCurrentProcessExplicitAppUserModelID} fails with {@code E_INVALIDARG}), and the process of the
     * application then has none.
     */
    static final int MAX_APP_USER_MODEL_ID = 127;

    /**
     * Checks the architecture of the application, the WiX Toolset, the version, the upgrade code, the installation
     * directory and the Start menu group.
     */
    @Override
    public void check(PackagingContext context) {
        context.arch();
        DesktopPackagingConfig.Windows config = context.config().windows();
        wix(context);
        version(context.app());
        upgradeCode(context.app(), config);
        directories("install-directory", config.installDirectory().orElse(context.app().name()));
        if (config.startMenu()) {
            config.startMenuGroup().ifPresent(group -> directories("start-menu-group", group));
        }
    }

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path wix = wix(context);
        Path work = context.workDirectory("msi");
        Path icon = FileTrees.write(work.resolve("application.ico"), context.icons().ico());
        List<Path> files = FileTrees.files(image.root());
        WindowsSdk.checkPathLengths(image.root(), files, "The WiX Toolset");
        Map<String, Path> typeIcons = WindowsSdk.writeFileTypeIcons(context.app(), work, files);
        String source = wxs(context.app(), context.config().windows(), image.root(), image.launcher(), icon, typeIcons,
                files);
        Path wxs = FileTrees.write(work.resolve("application.wxs"), source);
        Path msi = context.output(WindowsSdk.packageFileName(context.app(), context.arch(), PackageFormat.MSI));
        WindowsSdk.warnEmptyDirectories(image, msi);
        Tools.exec(work, Map.of(), List.of(wix.toString(), "build", "-arch", context.arch().windows(), "-pdbtype", "none",
                "-intermediatefolder", work.resolve("obj").toString(), "-o", msi.toString(), wxs.toString()));
        // the description of the signature is the name of the package in the prompt of the rights of an administrator
        WindowsSdk.sign(context, List.of(msi), context.app().name());
        return msi;
    }

    /**
     * The {@code wix} of the WiX Toolset of the machine.
     */
    private static Path wix(PackagingContext context) {
        // the directory of the .NET global tools is on the PATH of the sessions opened after the first installation
        return context.tools().require("wix",
                "install the WiX Toolset 4 or later, with the .NET SDK : dotnet tool install --global wix --version 5.0.2",
                Path.of(System.getProperty("user.home"), ".dotnet", "tools", "wix.exe"));
    }

    /**
     * The version of a Windows Installer product : three numbers, of at most 255, 255 and 65535. A larger number is
     * refused rather than cut down, which would change the order of the versions ({@code 2024.12.0} and
     * {@code 2025.1.0} would be {@code 255.12.0} and {@code 255.1.0}) : Windows Installer compares them to upgrade the
     * application.
     *
     * @throws PackagingException when a number is larger
     */
    static String version(AppInfo app) {
        String version = app.numericVersion(3);
        String[] parts = version.split("\\.");
        if (Long.parseLong(parts[0]) > 255 || Long.parseLong(parts[1]) > 255 || Long.parseLong(parts[2]) > 65535) {
            throw new PackagingException("quarkus.desktop.packaging.version=" + app.version() + " : the version of an MSI"
                    + " package has three numbers of at most 255, 255 and 65535, which Windows Installer compares to"
                    + " upgrade the application : set a version within these limits (24.10.5 rather than 2024.10.5)");
        }
        return version;
    }

    /**
     * The upgrade code of the application, without braces and in upper case : the one of the configuration, written
     * with or without braces, or the GUID derived from the identifier of the application.
     *
     * @throws PackagingException when the upgrade code of the configuration is not a GUID
     */
    static String upgradeCode(AppInfo app, DesktopPackagingConfig.Windows config) {
        if (config.upgradeCode().isEmpty()) {
            return app.guid("upgrade");
        }
        String code = config.upgradeCode().get().strip();
        if (code.matches("\\{.*}")) {
            code = code.substring(1, code.length() - 1);
        }
        if (!code.matches("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")) {
            throw new PackagingException("quarkus.desktop.packaging.windows.upgrade-code=" + config.upgradeCode().get()
                    + " : an upgrade code is a GUID such as 11111111-2222-3333-4444-555555555555, with or without braces");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    /**
     * A text as the value of an attribute of the WiX source : escaped for XML, and for the variables that WiX expands
     * in every attribute. A dollar sign is doubled : {@code $(var.X)} is a variable of the preprocessor, {@code $$} a
     * dollar sign. An exclamation mark is doubled before a variable of WiX ({@code !(loc.X)}, {@code !(wix.X)},
     * {@code !(bind.X)}), as WiX reads {@code !!(} as an exclamation mark there only ; WiX reads these variables from
     * the start of the text, and takes the text as it is after an {@code !(} that is not one.
     */
    static String text(String value) {
        StringBuilder text = new StringBuilder();
        int start = 0;
        int index;
        int end;
        while ((index = value.indexOf("!(", start)) >= 0 && (end = variableEnd(value, index)) >= 0) {
            text.append(value, start, index).append('!').append(value, index, end);
            start = end;
        }
        text.append(value, start, value.length());
        return Xml.escape(text.toString()).replace("$", "$$");
    }

    /**
     * The end of the variable of WiX at an index of a text, after its closing parenthesis, or -1 when the text has none
     * there : {@code !(loc.name)}, {@code !(wix.name)} or {@code !(bind.name)}, where the name may have a scope
     * ({@code name.scope}) and be followed by a default value ({@code name=value}).
     */
    private static int variableEnd(String value, int index) {
        int dot = value.indexOf('.', index);
        int close = dot < 0 ? -1 : value.indexOf(')', dot);
        if (close < 0 || !List.of("loc", "wix", "bind").contains(value.substring(index + 2, dot))) {
            return -1;
        }
        int equals = value.indexOf('=', dot);
        String name = value.substring(dot + 1, equals >= 0 && equals < close ? equals : close);
        return VARIABLE_NAME.matcher(name).matches() ? close + 1 : -1;
    }

    /**
     * The AppUserModelID of the shortcuts : the name that the application gives itself at run time
     * ({@link AppInfo#applicationId}), which is the explicit AppUserModelID of its process, so that its windows and its
     * shortcut pinned to the taskbar are one button. Empty when the application gives itself no name, or one that
     * Windows refuses.
     */
    static Optional<String> appUserModelId(AppInfo app) {
        return app.applicationId().filter(id -> id.length() <= MAX_APP_USER_MODEL_ID);
    }

    /**
     * The nested directories of a path of the configuration, separated by {@code \} or {@code /} : each is a directory
     * of the source, which the package can remove from the profile of the user (WiX would create the parents of a
     * path written as one name, without removing them).
     *
     * @param property the property of the path, after {@code quarkus.desktop.packaging.windows.}
     * @throws PackagingException for a path without a name
     */
    static List<String> directories(String property, String path) {
        List<String> names = Arrays.stream(path.split("[\\\\/]")).filter(name -> !name.isEmpty()).toList();
        if (names.isEmpty()) {
            throw new PackagingException("quarkus.desktop.packaging.windows." + property + "=" + path
                    + " : write the name of a directory, or the names of nested directories separated by \\");
        }
        return names;
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
     * @param typeIcons the icons of the file types that have one, by name of type
     *        ({@link WindowsSdk#writeFileTypeIcons})
     * @param files the files of the application image, relative to its directory
     */
    static String wxs(AppInfo app, DesktopPackagingConfig.Windows config, Path root, String launcher, Path icon,
            Map<String, Path> typeIcons, List<Path> files) {
        boolean perUser = config.perUser();
        String upgradeCode = upgradeCode(app, config);
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
        xml.append("  <Package Name=\"").append(text(app.name())).append("\" Manufacturer=\"")
                .append(text(app.vendor())).append("\" Version=\"").append(version(app)).append("\" UpgradeCode=\"")
                .append(upgradeCode).append("\" Language=\"1033\" Scope=\"")
                .append(perUser ? "perUser" : "perMachine").append("\" InstallerVersion=\"500\">\n");
        xml.append("    <SummaryInformation Description=\"").append(text(app.description())).append("\" />\n");
        // a new version replaces the installed one ; a rebuild of the same version too
        xml.append("    <MajorUpgrade AllowSameVersionUpgrades=\"yes\" DowngradeErrorMessage=\"A newer version of")
                .append(" [ProductName] is already installed.\" />\n");
        // not over the setup program of the application, whose uninstall key is named after the upgrade code : the two
        // would install the same files, and the removal of either would take them from the other
        xml.append("    <Property Id=\"INNOSETUPINSTALLED\">\n");
        xml.append("      <RegistrySearch Root=\"").append(perUser ? "HKCU" : "HKLM")
                .append("\" Key=\"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{").append(upgradeCode)
                .append("}_is1\" Name=\"UninstallString\" Type=\"raw\" />\n");
        xml.append("    </Property>\n");
        xml.append("    <Launch Condition=\"Installed OR NOT INNOSETUPINSTALLED\" Message=\"[ProductName] is installed")
                .append(" by its setup program : remove it first.\" />\n");
        xml.append("    <MediaTemplate EmbedCab=\"yes\" CompressionLevel=\"high\" />\n");
        xml.append("    <Icon Id=\"").append(ICON).append("\" SourceFile=\"").append(text(icon.toString()))
                .append("\" />\n");
        xml.append("    <Property Id=\"ARPPRODUCTICON\" Value=\"").append(ICON).append("\" />\n");
        app.homepage().ifPresent(homepage -> xml.append("    <Property Id=\"ARPURLINFOABOUT\" Value=\"")
                .append(text(homepage)).append("\" />\n"));

        List<String> installDirectory = directories("install-directory", config.installDirectory().orElse(app.name()));
        if (perUser) {
            xml.append("    <StandardDirectory Id=\"LocalAppDataFolder\">\n");
            xml.append("      <Directory Id=\"UserProgramsFolder\" Name=\"Programs\">\n");
        } else {
            xml.append("    <StandardDirectory Id=\"ProgramFiles6432Folder\">\n");
        }
        String indent = perUser ? "        " : "      ";
        List<String> installParents = openDirectories(xml, indent, installDirectory, "INSTALLFOLDER", "InstallParent");
        // the directories that a per-user package creates in the profile of the user above the installation directory,
        // which have to be removed too (ICE64)
        List<String> created = new ArrayList<>();
        if (perUser) {
            created.add("UserProgramsFolder");
            created.addAll(installParents);
        }
        directory(xml, indent + "  ".repeat(installDirectory.size()), tree, "INSTALLFOLDER", app, root, launcher, perUser,
                registryKey, ids, created);
        closeDirectories(xml, indent, installDirectory.size());
        if (perUser) {
            xml.append("      </Directory>\n");
        }
        xml.append("    </StandardDirectory>\n");

        if (config.startMenu()) {
            xml.append("    <StandardDirectory Id=\"ProgramMenuFolder\">\n");
            List<String> group = config.startMenuGroup().map(path -> directories("start-menu-group", path))
                    .orElse(List.of());
            List<String> groupParents = openDirectories(xml, "      ", group, "StartMenuGroup", "StartMenuGroup");
            shortcut(xml, "      " + "  ".repeat(group.size()), "StartMenuShortcut", app, registryKey, !group.isEmpty(),
                    groupParents);
            closeDirectories(xml, "      ", group.size());
            xml.append("    </StandardDirectory>\n");
            ids.components.add("StartMenuShortcut");
        }
        if (config.desktopShortcut()) {
            xml.append("    <StandardDirectory Id=\"DesktopFolder\">\n");
            shortcut(xml, "      ", "DesktopShortcut", app, registryKey, false, List.of());
            xml.append("    </StandardDirectory>\n");
            ids.components.add("DesktopShortcut");
        }
        if (!app.associations().isEmpty()) {
            associations(xml, app, perUser, registryKey, typeIcons);
            ids.components.add(ASSOCIATIONS);
        }

        xml.append("    <Feature Id=\"Application\" Title=\"").append(text(app.name())).append("\" Level=\"1\">\n");
        for (String component : ids.components) {
            xml.append("      <ComponentRef Id=\"").append(component).append("\" />\n");
        }
        xml.append("    </Feature>\n");
        xml.append("  </Package>\n");
        xml.append("</Wix>\n");
        return xml.toString();
    }

    /**
     * Opens the nested directories of a path : the last one has the given identifier, its parents a numbered one.
     *
     * @return the identifiers of the parents
     */
    private static List<String> openDirectories(StringBuilder xml, String indent, List<String> names, String id,
            String parentId) {
        List<String> parents = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String directoryId = i == names.size() - 1 ? id : parentId + (i + 1);
            xml.append(indent).append("  ".repeat(i)).append("<Directory Id=\"").append(directoryId).append("\" Name=\"")
                    .append(text(names.get(i))).append("\">\n");
            if (i < names.size() - 1) {
                parents.add(directoryId);
            }
        }
        return parents;
    }

    /**
     * Closes the nested directories that {@link #openDirectories} opened.
     */
    private static void closeDirectories(StringBuilder xml, String indent, int count) {
        for (int i = count - 1; i >= 0; i--) {
            xml.append(indent).append("  ".repeat(i)).append("</Directory>\n");
        }
    }

    /**
     * The components of the files of a directory, and its sub directories. A component has one file, which makes its
     * GUID stable : WiX derives it from the path of the installed file. In a per-user package, the files are in the
     * profile of the user : Windows Installer then wants a registry value of the user as the key path of each
     * component, and the removal of each directory ; WiX does not generate the GUID of such a component, which is
     * derived here from the identifier of the application and the path of the file.
     *
     * @param parents the directories of the profile of the user that the package creates above this one, which its
     *        first component removes too
     */
    private static void directory(StringBuilder xml, String indent, Directory directory, String directoryId, AppInfo app,
            Path root, String launcher, boolean perUser, String registryKey, Ids ids, List<String> parents) {
        if (perUser) {
            String component = ids.next("r");
            ids.components.add(component);
            xml.append(indent).append("<Component Id=\"").append(component).append("\">\n");
            xml.append(indent).append("  <RemoveFolder Id=\"").append(component).append("\" Directory=\"")
                    .append(directoryId).append("\" On=\"uninstall\" />\n");
            removeFolders(xml, indent + "  ", parents);
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
                    .append("\" Source=\"").append(text(root.resolve(file).toString())).append("\"")
                    .append(perUser ? " KeyPath=\"no\"" : "").append(" />\n");
            if (perUser) {
                registryKeyPath(xml, indent + "  ", registryKey + "\\Components", component);
            }
            xml.append(indent).append("</Component>\n");
        }
        for (Map.Entry<String, Directory> child : directory.directories.entrySet()) {
            String id = ids.next("d");
            xml.append(indent).append("<Directory Id=\"").append(id).append("\" Name=\"")
                    .append(text(child.getKey())).append("\">\n");
            directory(xml, indent + "  ", child.getValue(), id, app, root, launcher, perUser, registryKey, ids, List.of());
            xml.append(indent).append("</Directory>\n");
        }
    }

    /**
     * A text as a value of the {@code Formatted} type of Windows Installer : the brackets, which enclose the name of a
     * property, are escaped ({@code [\[]} and {@code [\]]}).
     */
    static String formatted(String text) {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == ']') {
                formatted.append("[\\").append(c).append(']');
            } else {
                formatted.append(c);
            }
        }
        return formatted.toString();
    }

    /**
     * A shortcut to the launcher, with the AppUserModelID of the application when it has one
     * ({@link #appUserModelId}). The key path of the component of a shortcut is a registry value of the user.
     *
     * @param removeFolder whether the component removes the folder of the shortcut
     * @param parents the parents of the folder of the shortcut that the component removes too
     */
    private static void shortcut(StringBuilder xml, String indent, String id, AppInfo app, String registryKey,
            boolean removeFolder, List<String> parents) {
        Optional<String> appUserModelId = appUserModelId(app);
        xml.append(indent).append("<Component Id=\"").append(id).append("\">\n");
        xml.append(indent).append("  <Shortcut Id=\"").append(id).append("\" Name=\"").append(text(app.name()))
                .append("\" Description=\"").append(text(app.description())).append("\" Target=\"[#")
                .append(LAUNCHER_FILE).append("]\" WorkingDirectory=\"INSTALLFOLDER\" Icon=\"").append(ICON)
                .append(appUserModelId.isPresent() ? "\">\n" : "\" />\n");
        if (appUserModelId.isPresent()) {
            xml.append(indent).append("    <ShortcutProperty Key=\"System.AppUserModel.ID\" Value=\"")
                    .append(text(formatted(appUserModelId.get()))).append("\" />\n");
            xml.append(indent).append("  </Shortcut>\n");
        }
        if (removeFolder) {
            xml.append(indent).append("  <RemoveFolder Id=\"Remove").append(id).append("Folder\" On=\"uninstall\" />\n");
        }
        removeFolders(xml, indent + "  ", parents);
        registryKeyPath(xml, indent + "  ", registryKey, id);
        xml.append(indent).append("</Component>\n");
    }

    /**
     * The removal of directories when the application is removed, when they are empty.
     */
    private static void removeFolders(StringBuilder xml, String indent, List<String> directoryIds) {
        for (String directoryId : directoryIds) {
            xml.append(indent).append("<RemoveFolder Id=\"Remove").append(directoryId).append("\" Directory=\"")
                    .append(directoryId).append("\" On=\"uninstall\" />\n");
        }
    }

    private static void registryKeyPath(StringBuilder xml, String indent, String key, String name) {
        registryKeyPath(xml, indent, "HKCU", key, name);
    }

    private static void registryKeyPath(StringBuilder xml, String indent, String root, String key, String name) {
        xml.append(indent).append("<RegistryValue Root=\"").append(root).append("\" Key=\"").append(text(key))
                .append("\" Name=\"").append(name).append("\" Type=\"integer\" Value=\"1\" KeyPath=\"yes\" />\n");
    }

    /**
     * The component of the file types and URL schemes of the application, in the installation directory : a
     * programmatic identifier per file type, whose extensions open with the launcher, and the keys of the URL schemes,
     * in the classes of the user for a per-user package, of the machine otherwise. Its key path is a registry value of
     * the application there, and its GUID is derived from the identifier of the application.
     *
     * @param typeIcons the icons of the file types that have one, by name of type, which the component installs : the
     *        other types have the icon of the launcher
     */
    private static void associations(StringBuilder xml, AppInfo app, boolean perUser, String registryKey,
            Map<String, Path> typeIcons) {
        String root = perUser ? "HKCU" : "HKLM";
        xml.append("    <Component Id=\"").append(ASSOCIATIONS).append("\" Directory=\"INSTALLFOLDER\" Guid=\"")
                .append(app.guid("component:associations:" + root)).append("\">\n");
        int icons = 0;
        for (Associations.FileType type : app.associations().fileTypes()) {
            String iconFile = LAUNCHER_FILE;
            Path icon = typeIcons.get(type.name());
            if (icon != null) {
                iconFile = "FileTypeIcon" + (++icons);
                xml.append("      <File Id=\"").append(iconFile).append("\" Name=\"")
                        .append(text(WindowsSdk.fileTypeIconName(app, type))).append("\" Source=\"")
                        .append(text(icon.toString())).append("\" KeyPath=\"no\" />\n");
            }
            // WiX writes the description as a registry value of Windows Installer
            xml.append("      <ProgId Id=\"").append(text(type.identifier(app))).append("\" Description=\"")
                    .append(text(registryString(type.description()))).append("\" Icon=\"").append(iconFile)
                    .append("\" IconIndex=\"0\">\n");
            // the verb is a key of the programmatic identifier : in its first extension only, as WiX writes it once
            for (int i = 0; i < type.extensions().size(); i++) {
                xml.append("        <Extension Id=\"").append(text(type.extensions().get(i))).append("\" ContentType=\"")
                        .append(text(type.mimeType())).append(i == 0 ? "\">\n" : "\" />\n");
                if (i == 0) {
                    xml.append("          <Verb Id=\"open\" TargetFile=\"").append(LAUNCHER_FILE)
                            .append("\" Argument=\"&quot;%1&quot;\" />\n");
                    xml.append("        </Extension>\n");
                }
            }
            xml.append("      </ProgId>\n");
        }
        for (String scheme : app.associations().urlSchemes()) {
            String key = "Software\\Classes\\" + scheme;
            registryValue(xml, root, key, null, registryString("URL:" + app.name()));
            registryValue(xml, root, key, "URL Protocol", "");
            registryValue(xml, root, key + "\\DefaultIcon", null, "[#" + LAUNCHER_FILE + "],0");
            registryValue(xml, root, key + "\\shell\\open\\command", null, "\"[#" + LAUNCHER_FILE + "]\" \"%1\"");
        }
        registryKeyPath(xml, "      ", root, registryKey + "\\Components", ASSOCIATIONS);
        xml.append("    </Component>\n");
    }

    /**
     * A text as a string value of the {@code Registry} table of Windows Installer, a formatted text : the brackets
     * and the braces are escaped ({@code [\[]}, {@code [\{]}), as a brace without its pair is an invalid value (ICE03),
     * and a leading number sign is doubled (a value that starts with one is a number).
     */
    static String registryString(String text) {
        StringBuilder value = new StringBuilder(text.startsWith("#") ? "#" : "");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("[]{}".indexOf(c) >= 0) {
                value.append("[\\").append(c).append(']');
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    /**
     * A string value of the registry, in the component of the associations.
     *
     * @param name the name of the value, {@code null} for the default value of the key
     * @param value the value, a formatted text of Windows Installer
     */
    private static void registryValue(StringBuilder xml, String root, String key, String name, String value) {
        xml.append("      <RegistryValue Root=\"").append(root).append("\" Key=\"").append(text(key)).append("\"")
                .append(name == null ? "" : " Name=\"" + text(name) + "\"").append(" Type=\"string\" Value=\"")
                .append(text(value)).append("\" />\n");
    }
}
