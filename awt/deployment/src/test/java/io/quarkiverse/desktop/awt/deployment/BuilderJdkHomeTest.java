package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JDK of the native build, where the extension takes the files of the JDK it embeds or copies (the fonts, the Visual
 * C++ runtime) and, on macOS, the build version of the executable : chosen as Quarkus chooses its {@code native-image},
 * and as the Desktop SWT extension chooses it (an application may have both).
 */
class BuilderJdkHomeTest {

    @Test
    void builderJdkHome(@TempDir Path directory) throws IOException {
        Path graalvm = nativeImageHome(directory.resolve("graalvm"));
        Path javaHome = nativeImageHome(directory.resolve("java"));
        Path jdkWithout = Files.createDirectories(directory.resolve("jdk").resolve("bin"));
        Path fallback = directory.resolve("fallback");

        assertEquals(graalvm, DesktopAwtProcessor.builderJdkHome(Optional.of(graalvm.toString()), javaHome.toFile(),
                null, false, fallback));
        // a home without native-image is not the one Quarkus runs
        assertEquals(javaHome, DesktopAwtProcessor.builderJdkHome(Optional.of(jdkWithout.getParent().toString()),
                javaHome.toFile(), null, false, fallback));
        assertEquals(javaHome, DesktopAwtProcessor.builderJdkHome(Optional.of(" "), javaHome.toFile(), null, false,
                fallback));

        // the native-image of the PATH, also when the Java home (java.home by default, the JDK running Maven) exists
        // without native-image
        String plainPath = directory.resolve("empty") + File.pathSeparator + graalvm.resolve("bin");
        assertEquals(graalvm.toRealPath(), DesktopAwtProcessor.builderJdkHome(Optional.empty(),
                jdkWithout.getParent().toFile(), plainPath, false, fallback));
        // native-image.cmd on Windows
        assertEquals(fallback, DesktopAwtProcessor.builderJdkHome(Optional.of(graalvm.toString()), null, plainPath,
                true, fallback));
        assertEquals(fallback, DesktopAwtProcessor.builderJdkHome(Optional.empty(), null, null, false, fallback));

        // the native-image of the PATH, through links, as GraalVM installs it on Linux and macOS :
        // <home>/bin/native-image -> ../lib/svm/bin/native-image, the release file of the JDK in <home>
        Path svm = directory.resolve("svm-graalvm");
        Files.createFile(Files.createDirectories(svm.resolve("lib").resolve("svm").resolve("bin")).resolve("native-image"));
        Files.writeString(svm.resolve("release"), "OS_ARCH=\"aarch64\"\n");
        Files.createDirectories(svm.resolve("bin"));
        Path links = Files.createDirectories(directory.resolve("links"));
        try {
            Files.createSymbolicLink(svm.resolve("bin").resolve("native-image"),
                    Path.of("..", "lib", "svm", "bin", "native-image"));
            Files.createSymbolicLink(links.resolve("native-image"), svm.resolve("bin").resolve("native-image"));
        } catch (FileSystemException | UnsupportedOperationException e) {
            // Windows needs the Developer Mode, or an elevated process, to create links
            Assumptions.abort("Symbolic links cannot be created here : " + e);
        }
        for (Path bin : List.of(svm.resolve("bin"), links)) {
            String path = directory.resolve("empty") + File.pathSeparator + bin;
            assertEquals(svm.toRealPath(), DesktopAwtProcessor.builderJdkHome(Optional.empty(),
                    jdkWithout.getParent().toFile(), path, false, fallback), bin.toString());
        }
    }

    private static Path nativeImageHome(Path home) throws IOException {
        Files.createFile(Files.createDirectories(home.resolve("bin")).resolve("native-image"));
        return home;
    }
}
