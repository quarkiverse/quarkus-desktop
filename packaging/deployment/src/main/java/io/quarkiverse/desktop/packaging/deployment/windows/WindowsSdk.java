package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.PackageFormat;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * The tools of the Windows SDK ({@code makeappx}, {@code signtool}), which its installer does not add to the
 * {@code PATH}, and the signature of the Windows executables and packages ; the application image as the Windows
 * packages install it.
 */
public final class WindowsSdk {

    private static final Logger LOGGER = Logger.getLogger(WindowsSdk.class);

    /**
     * The length of a command line of {@code signtool}, below the 32767 characters of a Windows command line.
     */
    private static final int COMMAND_LINE = 30000;

    /**
     * The bytes of a file that tell whether it is signed : its headers.
     */
    private static final int HEADERS = 64 * 1024;

    /**
     * The length of a path that the WiX Toolset and Inno Setup do not read, even where Windows allows longer paths : the
     * {@code MAX_PATH} of Windows.
     */
    static final int MAX_PATH = 260;

    private WindowsSdk() {
    }

    /**
     * The file name of a Windows package : the name of the application, its version and the architecture of the
     * package ({@code Acme-Editor-1.2.3-x64.msi}).
     */
    static String packageFileName(AppInfo app, Arch arch, PackageFormat format) {
        return app.fileName() + "-" + app.version() + "-" + arch.windows() + "." + format.extension();
    }

    /**
     * Checks that the tool of a package reads the files of the application image : the WiX Toolset and Inno Setup do
     * not read a file whose path has {@link #MAX_PATH} characters or more, and their error does not name it.
     *
     * @param root the directory of the application image
     * @param files the files of the application image, relative to its directory
     * @param tool the name of the tool, for the message
     * @throws PackagingException for such a path : the longest one, so that one change of the directories is enough
     */
    static void checkPathLengths(Path root, List<Path> files, String tool) {
        String longest = "";
        for (Path file : files) {
            String path = root.resolve(file).toAbsolutePath().toString();
            if (path.length() > longest.length()) {
                longest = path;
            }
        }
        if (longest.length() >= MAX_PATH) {
            throw new PackagingException("The path of " + longest + " has " + longest.length() + " characters : " + tool
                    + " does not read a file whose path has " + MAX_PATH + " characters or more, even where Windows"
                    + " allows longer paths. Build the project in a directory whose path is shorter by "
                    + (longest.length() - MAX_PATH + 1) + " characters or more, or set"
                    + " quarkus.desktop.packaging.output-directory to a short absolute directory (C:\\packaging, for"
                    + " instance)");
        }
    }

    /**
     * Where the Windows SDK has a tool : {@code Windows Kits\10\bin\<version>\<architecture>}, from the latest
     * version.
     *
     * @param executable the file name of the tool ({@code makeappx.exe})
     */
    static Path[] locations(String executable) {
        List<Path> locations = new ArrayList<>();
        String architecture = sdkArchitecture(System.getProperty("os.arch", ""));
        for (String variable : List.of("ProgramFiles(x86)", "ProgramFiles")) {
            String programFiles = System.getenv(variable);
            if (programFiles == null) {
                continue;
            }
            Path bin = Path.of(programFiles, "Windows Kits", "10", "bin");
            if (!Files.isDirectory(bin)) {
                continue;
            }
            try (Stream<Path> versions = Files.list(bin)) {
                versions.filter(version -> version.getFileName().toString().startsWith("10."))
                        .sorted(Comparator.comparing((Path version) -> versionKey(version.getFileName().toString()))
                                .reversed())
                        .forEach(version -> {
                            locations.add(version.resolve(architecture).resolve(executable));
                            locations.add(version.resolve("x64").resolve(executable));
                        });
            } catch (IOException e) {
                // no Windows SDK there
            }
        }
        return locations.toArray(Path[]::new);
    }

    /**
     * The directory of the Windows SDK whose tools run on the JVM of the build : {@code arm64}, {@code x86} or
     * {@code x64}. Its tools sign and package the files of any architecture : the packagers check the one of the
     * packages.
     *
     * @param osArch the architecture of the JVM ({@code os.arch})
     */
    static String sdkArchitecture(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "x86" -> "x86";
            default -> "x64";
        };
    }

    /**
     * A version of the Windows SDK as a string that sorts like its numbers : {@code 10.0.9600.0} before
     * {@code 10.0.26100.0}.
     */
    private static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) {
            key.append("0".repeat(Math.max(0, 8 - part.length()))).append(part).append('.');
        }
        return key.toString();
    }

    /**
     * Whether the configuration signs : it has a certificate file, a subject name, a thumbprint or arguments of
     * {@code signtool}.
     */
    static boolean signs(DesktopPackagingConfig.Sign sign) {
        return sign.certificateFile().isPresent() || sign.subjectName().isPresent() || sign.thumbprint().isPresent()
                || !arguments(sign).isEmpty();
    }

    /**
     * Whether the configuration of the build signs.
     */
    static boolean signs(PackagingContext context) {
        return signs(context.config().windows().sign());
    }

    /**
     * The {@code signtool} of the Windows SDK.
     */
    static Path signtool(PackagingContext context) {
        return context.tools().require("signtool",
                "install the Windows SDK (the signing tools for desktop applications)", locations("signtool.exe"));
    }

    /**
     * Checks what signs the application image and the Windows packages, before they are built : {@code signtool} and
     * its arguments, when the configuration signs.
     *
     * @throws PackagingException when {@code signtool} is not found, or when its arguments have a time stamp server
     *         too ({@link #checkArguments})
     */
    public static void checkSignature(PackagingContext context) {
        if (signs(context)) {
            signtool(context);
            checkArguments(context.config().windows().sign());
        }
    }

    /**
     * Checks the arguments of {@code signtool} against the other properties of the signature : {@code signtool} takes
     * one time stamp server, the one of {@code timestamp-url} or the one of the arguments ({@code /tr} or {@code /t}).
     *
     * @throws PackagingException when both have one
     */
    static void checkArguments(DesktopPackagingConfig.Sign sign) {
        if (sign.timestampUrl().isEmpty()) {
            return;
        }
        for (String argument : arguments(sign)) {
            if (isOption(argument, "tr") || isOption(argument, "t")) {
                throw new PackagingException("quarkus.desktop.packaging.windows.sign.timestamp-url and the " + argument
                        + " of quarkus.desktop.packaging.windows.sign.arguments both set a time stamp server, which"
                        + " signtool takes once : keep one of them");
            }
        }
    }

    /**
     * What signs, as the log names it : the certificate file, the subject or the thumbprint of the certificate, or the
     * arguments of {@code signtool}, whose values are not logged (they may have a secret).
     */
    static String signer(DesktopPackagingConfig.Sign sign) {
        if (sign.certificateFile().isPresent()) {
            return "the certificate " + sign.certificateFile().get();
        }
        if (sign.subjectName().isPresent()) {
            return "the certificate of subject " + sign.subjectName().get();
        }
        if (sign.thumbprint().isPresent()) {
            return "the certificate of thumbprint " + sign.thumbprint().get();
        }
        return "the arguments of quarkus.desktop.packaging.windows.sign.arguments";
    }

    /**
     * The warning of a configuration that does not sign, although it has another property of the signature : the
     * certificate may be an expression of a variable that the build does not define, such as a secret of continuous
     * integration, whose value is then empty.
     *
     * @return empty for a configuration that signs, or that has no property of the signature
     */
    static Optional<String> unsignedWarning(DesktopPackagingConfig.Sign sign) {
        if (signs(sign)) {
            return Optional.empty();
        }
        List<String> properties = new ArrayList<>();
        sign.certificatePassword()
                .ifPresent(password -> properties.add("quarkus.desktop.packaging.windows.sign.certificate-password"));
        sign.timestampUrl().ifPresent(url -> properties.add("quarkus.desktop.packaging.windows.sign.timestamp-url"));
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("The application image and the Windows packages are not signed : "
                + String.join(" and ", properties) + (properties.size() == 1 ? " is" : " are")
                + " set, but no certificate (quarkus.desktop.packaging.windows.sign.certificate-file, subject-name,"
                + " thumbprint or arguments). Check the value of the certificate : an expression of a variable that is"
                + " not defined, such as a secret that the build does not have, is empty");
    }

    /**
     * The warning of a configuration that signs without time stamp : the signatures become invalid when the
     * certificate expires.
     *
     * @return empty for a configuration that does not sign, or that has a time stamp server
     *         ({@code timestamp-url}, or {@code /tr} or {@code /t} in the arguments)
     */
    static Optional<String> timestampWarning(DesktopPackagingConfig.Sign sign) {
        if (!signs(sign) || sign.timestampUrl().isPresent() || arguments(sign).stream()
                .anyMatch(argument -> List.of("/tr", "-tr", "/t", "-t").contains(argument.toLowerCase(Locale.ROOT)))) {
            return Optional.empty();
        }
        return Optional.of("The signatures of the application image and of the Windows packages have no time stamp :"
                + " they become invalid when the certificate expires. Set"
                + " quarkus.desktop.packaging.windows.sign.timestamp-url to the time stamp server of the certificate"
                + " authority (http://timestamp.digicert.com, for instance)");
    }

    /**
     * Signs the executables of the application image, when the configuration signs : the launcher, and the libraries
     * and executables that have no signature, such as the {@code jvm.dll} and {@code java.dll} that native-image
     * links. The packages then install signed files. Logs why nothing is signed otherwise : once per build, before the
     * packages.
     */
    public static void signImage(PackagingContext context, ApplicationImage image) throws IOException {
        DesktopPackagingConfig.Sign sign = context.config().windows().sign();
        if (!signs(sign)) {
            Optional<String> warning = unsignedWarning(sign);
            if (warning.isPresent()) {
                LOGGER.warn(warning.get());
            } else {
                LOGGER.info("The application image and the Windows packages are not signed : no certificate is set"
                        + " (quarkus.desktop.packaging.windows.sign.certificate-file, subject-name, thumbprint or"
                        + " arguments)");
            }
            return;
        }
        timestampWarning(sign).ifPresent(LOGGER::warn);
        List<Path> files = unsigned(image);
        for (Path file : files) {
            // jpackage writes a read-only launcher, and the libraries are copied with their attributes
            file.toFile().setWritable(true);
        }
        sign(context, files, null);
    }

    /**
     * The files of the application image to sign : the launcher, then the libraries and executables that have no
     * signature. A file that has one keeps it : signing it again would replace the signature of its vendor.
     */
    static List<Path> unsigned(ApplicationImage image) throws IOException {
        Path launcher = image.launcherPath();
        List<Path> files = new ArrayList<>(List.of(launcher));
        for (Path relative : FileTrees.files(image.root())) {
            Path file = image.root().resolve(relative);
            String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
            if ((name.endsWith(".dll") || name.endsWith(".exe")) && !file.equals(launcher)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && PeImports.unsigned(headers(file))) {
                files.add(file);
            }
        }
        return files;
    }

    private static byte[] headers(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return input.readNBytes(HEADERS);
        }
    }

    /**
     * Warns that a Windows package does not have the empty directories of the application image : the packages install
     * its files, and an MSIX package cannot have an empty directory.
     *
     * @param windowsPackage the package
     */
    static void warnEmptyDirectories(ApplicationImage image, Path windowsPackage) throws IOException {
        for (Path directory : emptyDirectories(image.root())) {
            LOGGER.warnf("%s does not have %s, an empty directory of the application image : the Windows packages"
                    + " install files, add one to it", windowsPackage.getFileName(), directory);
        }
    }

    /**
     * The directories of a directory that have no entries, as sorted relative paths.
     */
    static List<Path> emptyDirectories(Path root) throws IOException {
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!path.equals(root) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (Stream<Path> entries = Files.list(path)) {
                        if (entries.findAny().isEmpty()) {
                            directories.add(root.relativize(path));
                        }
                    }
                }
            }
        }
        directories.sort(null);
        return directories;
    }

    /**
     * Signs an executable or a package with {@code signtool}, when the configuration signs.
     */
    static void sign(PackagingContext context, Path file) {
        sign(context, List.of(file), null);
    }

    /**
     * Signs files with {@code signtool}, when the configuration signs : several files per command. Logs what is signed,
     * and with what.
     *
     * @param description the description of the signed content, which Windows shows as the name of an MSI package
     *        when it asks for the rights of an administrator, or {@code null}
     */
    static void sign(PackagingContext context, List<Path> files, String description) {
        if (!signs(context) || files.isEmpty()) {
            return;
        }
        DesktopPackagingConfig.Sign sign = context.config().windows().sign();
        List<String> command = command(signtool(context), sign, context.projectDirectory(), description);
        int length = command.stream().mapToInt(argument -> argument.length() + 3).sum();
        List<Path> batch = new ArrayList<>();
        int batchLength = length;
        for (Path file : files) {
            int fileLength = file.toString().length() + 3;
            if (!batch.isEmpty() && batchLength + fileLength > COMMAND_LINE) {
                sign(command, batch);
                batch.clear();
                batchLength = length;
            }
            batch.add(file);
            batchLength += fileLength;
        }
        sign(command, batch);
        LOGGER.infof("Signed %s with %s", signed(files), signer(sign));
        files.forEach(file -> LOGGER.debugf("Signed %s", file));
    }

    /**
     * Signed files, as the log names them : the file name of the first one, and the number of the others.
     */
    static String signed(List<Path> files) {
        String first = files.get(0).getFileName().toString();
        int others = files.size() - 1;
        return others == 0 ? first : first + " and " + others + (others == 1 ? " other file" : " other files");
    }

    private static void sign(List<String> command, List<Path> files) {
        List<String> line = new ArrayList<>(command);
        files.forEach(file -> line.add(file.toString()));
        Tools.Result result = Tools.run(null, Map.of(), line, true);
        if (!result.succeeded()) {
            // not the command : it has the password of the certificate
            throw new PackagingException("signtool failed to sign " + (files.size() == 1 ? files.get(0) : files)
                    + " (exit code " + result.exitCode() + ") :\n" + result.output().strip());
        }
    }

    /**
     * The command of {@code signtool sign}, without the files to sign. The paths of {@code signtool} and of the
     * certificate file are absolute : the compiler of Inno Setup runs the command in its own directory. The ones of
     * the arguments are as written.
     * <p>
     * {@code signtool} refuses an option given twice : the file digest ({@code /fd SHA256}), the description and the
     * digest of the time stamp ({@code /td SHA256}) are left to the arguments that have them.
     *
     * @param projectDirectory the directory of a relative certificate file
     * @param description the description of the signed content, or {@code null}
     */
    static List<String> command(Path signtool, DesktopPackagingConfig.Sign sign, Path projectDirectory,
            String description) {
        List<String> arguments = arguments(sign);
        List<String> command = new ArrayList<>(List.of(signtool.toAbsolutePath().toString(), "sign"));
        if (!hasOption(arguments, "fd")) {
            command.add("/fd");
            command.add("SHA256");
        }
        if (description != null && !description.isBlank() && !hasOption(arguments, "d")) {
            command.add("/d");
            command.add(description);
        }
        sign.certificateFile().ifPresent(certificate -> {
            command.add("/f");
            command.add(projectDirectory.resolve(certificate).toAbsolutePath().toString());
        });
        sign.certificatePassword().ifPresent(password -> {
            command.add("/p");
            command.add(password);
        });
        sign.subjectName().ifPresent(subject -> {
            command.add("/n");
            command.add(subject);
        });
        sign.thumbprint().ifPresent(thumbprint -> {
            command.add("/sha1");
            command.add(thumbprint);
        });
        sign.timestampUrl().ifPresent(url -> {
            command.add("/tr");
            command.add(url);
            if (!hasOption(arguments, "td")) {
                command.add("/td");
                command.add("SHA256");
            }
        });
        command.addAll(arguments);
        return command;
    }

    /**
     * Whether arguments of {@code signtool} have an option ({@link #isOption}).
     */
    static boolean hasOption(List<String> arguments, String name) {
        return arguments.stream().anyMatch(argument -> isOption(argument, name));
    }

    /**
     * Whether an argument of {@code signtool} is an option : {@code /<name>} or {@code -<name>}, in any case.
     */
    private static boolean isOption(String argument, String name) {
        return argument.equalsIgnoreCase("/" + name) || argument.equalsIgnoreCase("-" + name);
    }

    /**
     * The file digest of the signatures : the value of the first {@code /fd} option of the arguments, in upper case,
     * or {@code SHA256}, which the command has otherwise.
     */
    static String fileDigest(DesktopPackagingConfig.Sign sign) {
        List<String> arguments = arguments(sign);
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (isOption(arguments.get(i), "fd")) {
                return arguments.get(i + 1).toUpperCase(Locale.ROOT);
            }
        }
        return "SHA256";
    }

    /**
     * The arguments of the configuration, without the white space that follows the commas of a list.
     */
    private static List<String> arguments(DesktopPackagingConfig.Sign sign) {
        return sign.arguments().orElse(List.of()).stream().map(String::strip).filter(argument -> !argument.isEmpty())
                .toList();
    }
}
