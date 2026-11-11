package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * The tools of the Windows SDK ({@code makeappx}, {@code signtool}), which its installer does not add to the
 * {@code PATH}, and the signature of the Windows executables and packages.
 */
public final class WindowsSdk {

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
     * Whether the configuration has a signing certificate.
     */
    static boolean signs(PackagingContext context) {
        var sign = context.config().windows().sign();
        return sign.certificateFile().isPresent() || sign.subjectName().isPresent() || sign.thumbprint().isPresent();
    }

    /**
     * Signs the launcher of the application image, when the configuration has a signing certificate : the packages
     * then install a signed executable.
     */
    public static void signLauncher(PackagingContext context, ApplicationImage image) {
        if (signs(context)) {
            // jpackage writes a read-only launcher
            image.launcherPath().toFile().setWritable(true);
            sign(context, image.launcherPath());
        }
    }

    /**
     * Signs an executable or a package with {@code signtool}, when the configuration has a signing certificate.
     */
    static void sign(PackagingContext context, Path file) {
        if (!signs(context)) {
            return;
        }
        var sign = context.config().windows().sign();
        Path signtool = context.tools().require("signtool",
                "install the Windows SDK (the signing tools for desktop applications)", locations("signtool.exe"));
        List<String> command = new ArrayList<>(List.of(signtool.toString(), "sign", "/fd", "SHA256"));
        sign.certificateFile().ifPresent(certificate -> {
            command.add("/f");
            command.add(context.projectDirectory().resolve(certificate).toString());
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
        command.add(file.toString());
        Tools.Result result = Tools.run(null, Map.of(), command);
        if (!result.succeeded()) {
            // not the command : it has the password of the certificate
            throw new PackagingException("signtool failed to sign " + file
                    + " (exit code " + result.exitCode() + ") :\n" + result.output().strip());
        }
    }
}
