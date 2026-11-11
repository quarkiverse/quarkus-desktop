package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * A Windows setup program ({@code .exe}), built with Inno Setup 6.3 or later ({@code ISCC}) from a generated script :
 * a wizard that installs the application image and its shortcuts, and an uninstaller.
 */
public final class InnoSetupPackager implements Packager {

    /**
     * The name of the sign tool of the script.
     */
    private static final String SIGN_TOOL = "quarkus";

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path iscc = context.tools().require("iscc",
                "install Inno Setup 6.3 or later (https://jrsoftware.org/isdl.php, or winget install JRSoftware.InnoSetup)",
                locations());
        Path work = context.workDirectory("exe");
        Path icon = FileTrees.write(work.resolve("application.ico"), context.icons().ico());
        String baseName = context.app().fileName() + "-" + context.app().version();
        Path exe = context.output(baseName + ".exe");
        WindowsSdk.warnEmptyDirectories(image, exe);
        String script = script(context.app(), context.config().windows(), context.arch(), image.root(),
                image.launcher().replace('/', '\\'), icon, context.outputDirectory(), baseName,
                FileTrees.files(image.root()), image.nativeExecutable());
        // with a byte order mark : the compiler reads the script as UTF-8 then
        Path iss = work.resolve("application.iss");
        Files.write(iss, ("\ufeff" + script).getBytes(StandardCharsets.UTF_8));
        if (!WindowsSdk.signs(context)) {
            Tools.exec(work, Map.of(), List.of(iscc.toString(), "/Qp", iss.toString()));
            return exe;
        }
        // the compiler signs the uninstaller that it embeds, then the setup program. Quiet (/Qp) : it would print the
        // command of signtool, which has the password of the certificate
        List<String> signtool = WindowsSdk.command(WindowsSdk.signtool(context), context.config().windows().sign(),
                context.projectDirectory(), null);
        Tools.Result result = Tools.run(work, Map.of(), List.of(iscc.toString(), "/Qp",
                "/S" + SIGN_TOOL + "=" + signTool(signtool), iss.toString()), true);
        if (!result.succeeded()) {
            // not the command, for the same reason
            throw new PackagingException(iscc.getFileName() + " failed with exit code " + result.exitCode() + " :\n"
                    + result.output().strip());
        }
        return exe;
    }

    /**
     * The command of a sign tool of Inno Setup : the command of {@code signtool}, whose arguments are quoted as the C
     * runtime reads them, with {@code $q} for a quote, {@code $$} for a dollar sign, and {@code $f} for the file to
     * sign.
     */
    static String signTool(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String argument : command) {
            line.append(quote(argument)).append(' ');
        }
        return line.toString().replace("$", "$$").replace("\"", "$q") + "$f";
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
     * @param outputDirectory the directory of the setup program
     * @param baseName the file name of the setup program, without extension
     * @param files the files of the application image, relative to its directory
     * @param nativeExecutable whether the application image has a native executable, rather than the launcher and the
     *        Java runtime of {@code jpackage}
     */
    static String script(AppInfo app, DesktopPackagingConfig.Windows config, Arch arch, Path root, String launcher,
            Path icon, Path outputDirectory, String baseName, List<Path> files, boolean nativeExecutable) {
        String name = constant(app.name());
        String architecture = arch == Arch.ARM64 ? "arm64" : "x64compatible";
        String upgradeCode = MsiPackager.upgradeCode(app, config);
        StringBuilder script = new StringBuilder();
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
            // configuration of the launcher) : the setup program installs over the installed version
            script.append("[InstallDelete]\n");
            script.append("Type: filesandordirs; Name: \"{app}\\app\"\n");
            script.append("Type: filesandordirs; Name: \"{app}\\runtime\"\n");
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
        script.append('\n');
        script.append("[Icons]\n");
        String target = "{app}\\" + quoted(constant(launcher));
        if (config.startMenu()) {
            String folder = config.startMenuGroup().isPresent() ? "{group}" : "{autoprograms}";
            script.append("Name: \"").append(folder).append("\\").append(quoted(name)).append("\"; Filename: \"")
                    .append(target).append("\"; WorkingDir: \"{app}\"\n");
        }
        if (config.desktopShortcut()) {
            script.append("Name: \"{autodesktop}\\").append(quoted(name)).append("\"; Filename: \"").append(target)
                    .append("\"; WorkingDir: \"{app}\"; Tasks: desktopicon\n");
        }
        script.append('\n');
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
        return script.toString();
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
