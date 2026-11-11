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
import java.util.stream.Stream;

import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * The tools of the Windows SDK ({@code makeappx}, {@code signtool}), which its installer does not add to the
 * {@code PATH}, and the signature of the Windows executables and packages.
 */
public final class WindowsSdk {

    /**
     * The length of a command line of {@code signtool}, below the 32767 characters of a Windows command line.
     */
    private static final int COMMAND_LINE = 30000;

    /**
     * The bytes of a file that tell whether it is signed : its headers.
     */
    private static final int HEADERS = 64 * 1024;

    private WindowsSdk() {
    }

    /**
     * Where the Windows SDK has a tool : {@code Windows Kits\10\bin\<version>\<architecture>}, from the latest
     * version.
     *
     * @param executable the file name of the tool ({@code makeappx.exe})
     */
    static Path[] locations(String executable) {
        List<Path> locations = new ArrayList<>();
        String architecture = Arch.host().windows();
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
     * Signs the executables of the application image, when the configuration signs : the launcher, and the libraries
     * and executables that have no signature, such as the {@code jvm.dll} and {@code java.dll} that native-image
     * links. The packages then install signed files.
     */
    public static void signImage(PackagingContext context, ApplicationImage image) throws IOException {
        if (!signs(context)) {
            return;
        }
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
     * Signs an executable or a package with {@code signtool}, when the configuration signs.
     */
    static void sign(PackagingContext context, Path file) {
        sign(context, List.of(file), null);
    }

    /**
     * Signs files with {@code signtool}, when the configuration signs : several files per command.
     *
     * @param description the description of the signed content, which Windows shows as the name of an MSI package
     *        when it asks for the rights of an administrator, or {@code null}
     */
    static void sign(PackagingContext context, List<Path> files, String description) {
        if (!signs(context) || files.isEmpty()) {
            return;
        }
        List<String> command = command(signtool(context), context.config().windows().sign(), context.projectDirectory(),
                description);
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
     * The command of {@code signtool sign}, without the files to sign. Its paths are absolute : the compiler of Inno
     * Setup runs the command in its own directory.
     *
     * @param projectDirectory the directory of a relative certificate file
     * @param description the description of the signed content, or {@code null}
     */
    static List<String> command(Path signtool, DesktopPackagingConfig.Sign sign, Path projectDirectory,
            String description) {
        List<String> arguments = arguments(sign);
        List<String> command = new ArrayList<>(List.of(signtool.toAbsolutePath().toString(), "sign", "/fd", "SHA256"));
        // signtool refuses an option given twice
        if (description != null && !description.isBlank()
                && arguments.stream().noneMatch(argument -> argument.equalsIgnoreCase("/d") || argument.equals("-d"))) {
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
            command.add("/td");
            command.add("SHA256");
        });
        command.addAll(arguments);
        return command;
    }

    /**
     * The arguments of the configuration, without the white space that follows the commas of a list.
     */
    private static List<String> arguments(DesktopPackagingConfig.Sign sign) {
        return sign.arguments().orElse(List.of()).stream().map(String::strip).filter(argument -> !argument.isEmpty())
                .toList();
    }
}
