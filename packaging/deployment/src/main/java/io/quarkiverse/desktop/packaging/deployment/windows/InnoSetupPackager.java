package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.Associations;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.PackageFormat;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * A Windows setup program ({@code .exe}), built with Inno Setup 6.3 or later ({@code ISCC}) from a generated script :
 * a wizard that installs the application image, its shortcuts, its file types and URL schemes, and an uninstaller.
 */
public final class InnoSetupPackager implements Packager {

    private static final Logger LOGGER = Logger.getLogger(InnoSetupPackager.class);

    /**
     * The name of the sign tool of the script.
     */
    private static final String SIGN_TOOL = "quarkus";

    /**
     * Checks the architecture of the application, the compiler of Inno Setup and the upgrade code.
     */
    @Override
    public void check(PackagingContext context) {
        context.arch();
        iscc(context);
        MsiPackager.upgradeCode(context.app(), context.config().windows());
    }

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path iscc = iscc(context);
        Path work = context.workDirectory("exe");
        Path icon = FileTrees.write(work.resolve("application.ico"), context.icons().ico());
        List<Path> files = FileTrees.files(image.root());
        WindowsSdk.checkPathLengths(image.root(), files, "Inno Setup");
        Map<String, Path> typeIcons = WindowsSdk.writeFileTypeIcons(context.app(), work, files);
        String fileName = WindowsSdk.packageFileName(context.app(), context.arch(), PackageFormat.EXE);
        Path exe = context.output(fileName);
        // the compiler names the setup program after its base name
        String baseName = fileName.substring(0, fileName.length() - ".exe".length());
        WindowsSdk.warnEmptyDirectories(image, exe);
        String script = script(context.app(), context.config().windows(), context.arch(), image.root(),
                image.launcher().replace('/', '\\'), icon, typeIcons, context.outputDirectory(), baseName, files,
                image.nativeExecutable());
        // with a byte order mark : the compiler reads the script as UTF-8 then
        Path iss = work.resolve("application.iss");
        Files.write(iss, ("\ufeff" + script).getBytes(StandardCharsets.UTF_8));
        if (!WindowsSdk.signs(context)) {
            Tools.exec(work, Map.of(), List.of(iscc.toString(), "/Qp", iss.toString()));
            return exe;
        }
        // the compiler signs the uninstaller that it embeds, then the setup program, and prints the output of signtool,
        // unless it is quiet (/Qp). It prints the command of signtool too, which has the password of the certificate :
        // the output is logged, and tells why the compiler failed, without the lines of the command
        List<String> signtool = WindowsSdk.command(WindowsSdk.signtool(context), context.config().windows().sign(),
                context.projectDirectory(), null);
        Tools.Result result = Tools.run(work, Map.of(), List.of(iscc.toString(),
                "/S" + SIGN_TOOL + "=" + signTool(signtool), iss.toString()), true);
        LOGGER.debugf("%s exited with %d : %s", iscc.getFileName(), result.exitCode(),
                withoutCommands(result.output(), signtool.get(0)));
        if (!result.succeeded()) {
            throw new PackagingException(iscc.getFileName() + " failed with exit code " + result.exitCode() + " :\n"
                    + failureReason(result.output(), signtool.get(0)));
        }
        LOGGER.infof("Signed %s and its uninstaller with %s", exe.getFileName(),
                WindowsSdk.signer(context.config().windows().sign()));
        return exe;
    }

    /**
     * The command of a sign tool of Inno Setup : the command of {@code signtool}, whose arguments are quoted as the C
     * runtime reads them, with {@code $q} for a quote, {@code $$} for a dollar sign, and {@code $f} for the file to
     * sign.
     *
     * @throws PackagingException for an argument with a line break : the compiler prints the command, and the part of
     *         the password after the break would not be left out of its output ({@link #withoutCommands})
     */
    static String signTool(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String argument : command) {
            if (argument.indexOf('\r') >= 0 || argument.indexOf('\n') >= 0) {
                throw new PackagingException("The setup program cannot be signed with an argument of signtool that has"
                        + " a line break : remove the line break from"
                        + " quarkus.desktop.packaging.windows.sign.certificate-password, subject-name, thumbprint,"
                        + " timestamp-url or arguments");
            }
            line.append(quote(argument)).append(' ');
        }
        return line.toString().replace("$", "$$").replace("\"", "$q") + "$f";
    }

    /**
     * The output of the compiler without the lines of the command of the sign tool, which has the password of the
     * certificate : the lines that run it, and the ones that have the path of {@code signtool}, which starts the
     * command.
     *
     * @param signtool the path of {@code signtool}, as the command has it
     */
    static String withoutCommands(String output, String signtool) {
        return output.lines().filter(line -> !line.contains("Running Sign Tool") && !line.contains(signtool))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Why the compiler failed, from its output, without the lines of the command of the sign tool : its error, after
     * what {@code signtool} printed the last time that it ran when the error is the one of the sign tool. The whole
     * output when it has no error.
     *
     * @param signtool the path of {@code signtool}, as the command has it
     */
    static String failureReason(String output, String signtool) {
        List<String> lines = output.lines().toList();
        int error = 0;
        while (error < lines.size() && !lines.get(error).startsWith("Error")) {
            error++;
        }
        if (error == lines.size()) {
            return withoutCommands(output, signtool).strip();
        }
        int start = error;
        if (lines.get(error).contains("Sign Tool")) {
            // the lines after the last command : the output of signtool
            for (int i = error - 1; i >= 0; i--) {
                if (lines.get(i).contains("Running Sign Tool")) {
                    start = i + 1;
                    break;
                }
            }
        }
        StringBuilder reason = new StringBuilder();
        for (String line : lines.subList(start, lines.size())) {
            reason.append(line.strip()).append('\n');
        }
        return withoutCommands(reason.toString(), signtool).strip();
    }

    /**
     * An argument of a command line, quoted as the C runtime reads it : a quote is escaped, and the backslashes before
     * a quote are doubled.
     */
    private static String quote(String argument) {
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            quoted.append("\\".repeat(c == '"' ? 2 * backslashes + 1 : backslashes)).append(c);
            backslashes = 0;
        }
        return quoted.append("\\".repeat(2 * backslashes)).append('"').toString();
    }

    /**
     * The compiler of Inno Setup of the machine ({@code ISCC}).
     */
    private static Path iscc(PackagingContext context) {
        return context.tools().require("iscc",
                "install Inno Setup 6.3 or later (https://jrsoftware.org/isdl.php, or winget install JRSoftware.InnoSetup)",
                locations());
    }

    /**
     * Where the installer of Inno Setup puts its compiler : it is not on the {@code PATH}.
     */
    private static Path[] locations() {
        return List.of(Optional.ofNullable(System.getenv("ProgramFiles(x86)")),
                Optional.ofNullable(System.getenv("ProgramFiles")),
                Optional.ofNullable(System.getenv("LOCALAPPDATA")).map(local -> local + "\\Programs"))
                .stream().flatMap(Optional::stream)
                .flatMap(directory -> List.of(Path.of(directory, "Inno Setup 6", "ISCC.exe"),
                        Path.of(directory, "Inno Setup 7", "ISCC.exe")).stream())
                .toArray(Path[]::new);
    }

    /**
     * The Inno Setup script of the setup program.
     *
     * @param root the directory of the application image
     * @param launcher the path of the executable in the application image, with {@code \} separators
     * @param typeIcons the icons of the file types that have one, by name of type
     *        ({@link WindowsSdk#writeFileTypeIcons}), which the setup program installs in the installation directory
     * @param outputDirectory the directory of the setup program
     * @param baseName the file name of the setup program, without extension
     * @param files the files of the application image, relative to its directory
     * @param nativeExecutable whether the application image has a native executable, rather than the launcher and the
     *        Java runtime of {@code jpackage}
     */
    static String script(AppInfo app, DesktopPackagingConfig.Windows config, Arch arch, Path root, String launcher,
            Path icon, Map<String, Path> typeIcons, Path outputDirectory, String baseName, List<Path> files,
            boolean nativeExecutable) {
        String name = constant(app.name());
        String architecture = arch == Arch.ARM64 ? "arm64" : "x64compatible";
        String upgradeCode = MsiPackager.upgradeCode(app, config);
        StringBuilder script = new StringBuilder();
        // the built-in preprocessor, rather than the Inno Setup Preprocessor, which would evaluate {#...} in the values
        // and join a line that ends with a backslash to the next one : the directive has to be the first line
        script.append("#preproc builtin\n");
        script.append("; Generated by the Quarkus Desktop Packaging extension\n");
        script.append("[Setup]\n");
        // the identifier of the application for the upgrades and the uninstaller : the upgrade code of the MSI
        script.append("AppId={{").append(upgradeCode).append("}\n");
        script.append("AppName=").append(name).append('\n');
        script.append("AppVersion=").append(constant(app.version())).append('\n');
        script.append("AppVerName=").append(name).append(' ').append(constant(app.version())).append('\n');
        script.append("AppPublisher=").append(constant(app.vendor())).append('\n');
        app.homepage().ifPresent(homepage -> script.append("AppPublisherURL=").append(constant(homepage)).append('\n'));
        app.copyright().ifPresent(copyright -> script.append("AppCopyright=").append(constant(copyright)).append('\n'));
        script.append("VersionInfoVersion=").append(String.join(".", versionNumbers(app))).append('\n');
        script.append("VersionInfoDescription=").append(name).append(" Setup\n");
        script.append("DefaultDirName={autopf}\\").append(constant(config.installDirectory().orElse(app.name())))
                .append('\n');
        script.append("DefaultGroupName=").append(constant(config.startMenuGroup().orElse(app.name()))).append('\n');
        script.append("DisableProgramGroupPage=yes\n");
        // for the current user, without administrator rights, or for all users
        script.append("PrivilegesRequired=").append(config.perUser() ? "lowest" : "admin").append('\n');
        script.append("ArchitecturesAllowed=").append(architecture).append('\n');
        script.append("ArchitecturesInstallIn64BitMode=").append(architecture).append('\n');
        script.append("SetupIconFile=").append(icon).append('\n');
        script.append("UninstallDisplayIcon={app}\\").append(constant(launcher)).append('\n');
        script.append("UninstallDisplayName=").append(name).append('\n');
        app.licenseFile().ifPresent(license -> script.append("LicenseFile=").append(license).append('\n'));
        script.append("OutputDir=").append(outputDirectory).append('\n');
        script.append("OutputBaseFilename=").append(constant(baseName)).append('\n');
        script.append("Compression=lzma2\n");
        script.append("SolidCompression=yes\n");
        script.append("WizardStyle=modern\n");
        if (!app.associations().isEmpty()) {
            // Explorer reads the associations again at the end of the installation and of the removal
            script.append("ChangesAssociations=yes\n");
        }
        if (WindowsSdk.signs(config.sign())) {
            // the sign tool of the command line of the compiler : it signs the uninstaller too
            script.append("SignTool=").append(SIGN_TOOL).append('\n');
        }
        script.append('\n');
        script.append("[Languages]\n");
        script.append("Name: \"english\"; MessagesFile: \"compiler:Default.isl\"\n");
        script.append('\n');
        if (config.desktopShortcut()) {
            script.append("[Tasks]\n");
            script.append("Name: \"desktopicon\"; Description: \"{cm:CreateDesktopIcon}\"; GroupDescription:")
                    .append(" \"{cm:AdditionalIcons}\"\n");
            script.append('\n');
        }
        if (!nativeExecutable) {
            // the directories of jpackage, whose files change with the version (the jars, the Java runtime, the
            // configuration of the launcher) : the setup program installs over the installed version. Only in its
            // directory : a first installation may go into an existing directory, whose app and runtime directories
            // are not the ones of the application
            script.append("[InstallDelete]\n");
            script.append("Type: filesandordirs; Name: \"{app}\\app\"; Check: InstallsOverPreviousVersion\n");
            script.append("Type: filesandordirs; Name: \"{app}\\runtime\"; Check: InstallsOverPreviousVersion\n");
            script.append('\n');
        }
        script.append("[Files]\n");
        // a line per file, as the MSI and MSIX packages have them : the compiler leaves out the hidden files that a
        // wildcard matches
        for (Path file : files) {
            Path parent = file.getParent();
            script.append("Source: \"").append(quoted(root.resolve(file).toString())).append("\"; DestDir: \"{app}")
                    .append(parent == null ? "" : "\\" + quoted(constant(parent.toString().replace('/', '\\'))))
                    .append("\"; Flags: ignoreversion\n");
        }
        for (Path typeIcon : typeIcons.values()) {
            script.append("Source: \"").append(quoted(typeIcon.toString())).append("\"; DestDir: \"{app}\"; Flags:")
                    .append(" ignoreversion\n");
        }
        script.append('\n');
        script.append("[Icons]\n");
        String target = "{app}\\" + quoted(constant(launcher));
        // the AppUserModelID of the process of the application : its windows and its pinned shortcut are one button.
        // The compiler expands the constants of the value
        String appUserModelId = MsiPackager.appUserModelId(app)
                .map(id -> "; AppUserModelID: \"" + quoted(constant(id)) + "\"").orElse("");
        if (config.startMenu()) {
            String folder = config.startMenuGroup().isPresent() ? "{group}" : "{autoprograms}";
            script.append("Name: \"").append(folder).append("\\").append(quoted(name)).append("\"; Filename: \"")
                    .append(target).append("\"; WorkingDir: \"{app}\"").append(appUserModelId).append('\n');
        }
        if (config.desktopShortcut()) {
            script.append("Name: \"{autodesktop}\\").append(quoted(name)).append("\"; Filename: \"").append(target)
                    .append("\"; WorkingDir: \"{app}\"; Tasks: desktopicon").append(appUserModelId).append('\n');
        }
        script.append('\n');
        if (!app.associations().isEmpty()) {
            registry(script, app, launcher, typeIcons);
        }
        script.append("[Run]\n");
        script.append("Filename: \"").append(target).append("\"; Description: \"{cm:LaunchProgram,")
                .append(quoted(argument(name))).append("}\"; Flags: nowait postinstall skipifsilent\n");
        script.append('\n');
        // not over the MSI package of the application, which has the same upgrade code : the two would install the
        // same files, and the removal of either would take them from the other
        script.append("[Code]\n");
        script.append("function MsiEnumRelatedProducts(UpgradeCode: String; Reserved: Cardinal; Index: Cardinal;")
                .append(" ProductCode: String): Cardinal;\n");
        script.append("  external 'MsiEnumRelatedProductsW@msi.dll stdcall';\n");
        script.append('\n');
        script.append("function InitializeSetup(): Boolean;\n");
        script.append("var\n");
        script.append("  ProductCode: String;\n");
        script.append("begin\n");
        // the buffer of the product code : 38 characters and the terminating zero
        script.append("  ProductCode := StringOfChar(#0, 39);\n");
        // 0 when a product has the upgrade code, ERROR_NO_MORE_ITEMS otherwise
        script.append("  Result := MsiEnumRelatedProducts('{").append(upgradeCode)
                .append("}', 0, 0, ProductCode) <> 0;\n");
        script.append("  if not Result then\n");
        script.append("    SuppressibleMsgBox(").append(string(app.name() + " is installed by its MSI package : remove it"
                + " first.")).append(", mbCriticalError, MB_OK, IDOK);\n");
        script.append("end;\n");
        if (!nativeExecutable) {
            // whether the setup program installs in the directory of the installed version, which the uninstall key of
            // the application has : none on a first installation
            script.append('\n');
            script.append("function InstallsOverPreviousVersion(): Boolean;\n");
            script.append("begin\n");
            script.append("  Result := (WizardForm.PrevAppDir <> '') and SameText(RemoveBackslashUnlessRoot(")
                    .append("WizardForm.PrevAppDir), RemoveBackslashUnlessRoot(ExpandConstant('{app}')));\n");
            script.append("end;\n");
        }
        return script.toString();
    }

    /**
     * The {@code [Registry]} section of the file types and URL schemes, in the classes of the user or of the machine,
     * as the privileges of the setup program ({@code HKA}) : the extensions name the programmatic identifier of their
     * type, whose command opens the file with the launcher, and each URL scheme has the command of the launcher too.
     * The uninstaller removes the keys of the application, and the values that it gave to the extensions (and their
     * keys, when they have nothing else).
     *
     * @param launcher the path of the executable in the application image, with {@code \} separators
     * @param typeIcons the icons of the file types that have one, by name of type : the other types have the icon of
     *        the launcher
     */
    private static void registry(StringBuilder script, AppInfo app, String launcher, Map<String, Path> typeIcons) {
        String command = "\"{app}\\" + constant(launcher) + "\" \"%1\"";
        String launcherIcon = "{app}\\" + constant(launcher) + ",0";
        script.append("[Registry]\n");
        for (Associations.FileType type : app.associations().fileTypes()) {
            String progId = constant(type.identifier(app));
            for (String extension : type.extensions()) {
                String key = "Software\\Classes\\." + constant(extension);
                registryValue(script, key, "", progId, "uninsdeletevalue uninsdeletekeyifempty");
                registryValue(script, key, "Content Type", constant(type.mimeType()), "uninsdeletevalue uninsdeletekeyifempty");
                registryValue(script, key + "\\OpenWithProgids", progId, "", "uninsdeletevalue uninsdeletekeyifempty");
            }
            String key = "Software\\Classes\\" + progId;
            registryValue(script, key, "", constant(type.description()), "uninsdeletekey");
            registryValue(script, key + "\\DefaultIcon", "", typeIcons.containsKey(type.name())
                    ? "{app}\\" + constant(WindowsSdk.fileTypeIconName(app, type)) + ",0"
                    : launcherIcon, null);
            registryValue(script, key + "\\shell\\open\\command", "", command, null);
        }
        for (String scheme : app.associations().urlSchemes()) {
            String key = "Software\\Classes\\" + constant(scheme);
            registryValue(script, key, "", "URL:" + constant(app.name()), "uninsdeletekey");
            registryValue(script, key, "URL Protocol", "", null);
            registryValue(script, key + "\\DefaultIcon", "", launcherIcon, null);
            registryValue(script, key + "\\shell\\open\\command", "", command, null);
        }
        script.append('\n');
    }

    /**
     * A string value of the {@code [Registry]} section.
     *
     * @param key the key, a text of the script
     * @param name the name of the value, a text of the script : empty for the default value of the key
     * @param data the value, a text of the script
     * @param flags the flags of the entry, or {@code null}
     */
    private static void registryValue(StringBuilder script, String key, String name, String data, String flags) {
        script.append("Root: HKA; Subkey: \"").append(quoted(key)).append("\"; ValueType: string; ValueName: \"")
                .append(quoted(name)).append("\"; ValueData: \"").append(quoted(data)).append('"')
                .append(flags == null ? "" : "; Flags: " + flags).append('\n');
    }

    /**
     * A text as a string of the code of the script : a quote is doubled. The constants are not expanded there.
     */
    private static String string(String text) {
        return "'" + text.replace("'", "''").replace("\r", " ").replace("\n", " ") + "'";
    }

    /**
     * A text of the script as the argument of the message of a check box : the percent sign, the comma, the vertical
     * bar and the closing brace are percent encoded in the argument of a constant, and an ampersand is doubled (a
     * single one marks the access key of the check box). The opening brace stays doubled : the argument is expanded as
     * a text of the script once it is decoded.
     */
    private static String argument(String text) {
        return text.replace("%", "%25").replace(",", "%2c").replace("|", "%7c").replace("}", "%7d").replace("&", "&&");
    }

    /**
     * The four numbers of the version of the setup program.
     */
    private static String[] versionNumbers(AppInfo app) {
        String[] parts = app.numericVersion(4).split("\\.");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = String.valueOf(Math.min(65535, Long.parseLong(parts[i])));
        }
        return parts;
    }

    /**
     * A text of the script : an opening brace starts a constant, unless it is doubled.
     */
    private static String constant(String text) {
        return text.replace("{", "{{").replace("\r", " ").replace("\n", " ");
    }

    /**
     * A text in a quoted parameter : a quote is doubled.
     */
    private static String quoted(String text) {
        return text.replace("\"", "\"\"");
    }
}
