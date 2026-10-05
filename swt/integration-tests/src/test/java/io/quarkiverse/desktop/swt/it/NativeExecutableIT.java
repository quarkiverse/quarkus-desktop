package io.quarkiverse.desktop.swt.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Checks the native executable itself : on macOS, the minimum macOS version and the SDK version it declares.
 */
@EnabledOnOs(OS.MAC)
public class NativeExecutableIT {

    private static Path executable;

    @BeforeAll
    static void executable() throws IOException {
        executable = Path.of(System.getProperty("native.image.path"));
        // the integration tests also run against the jar (-DskipITs=false without -Dnative) : no executable to check then
        assumeTrue(nativeArtifact(executable.getParent()), "the application is not built as a native executable");
        assertTrue(Files.isRegularFile(executable), executable + " not found");
    }

    /**
     * Whether Quarkus built a native executable : {@code type=native} in the {@code quarkus-artifact.properties} file of
     * the build, which the integration tests of Quarkus read too.
     */
    private static boolean nativeArtifact(Path target) throws IOException {
        Properties artifact = new Properties();
        try (Reader reader = Files.newBufferedReader(target.resolve("quarkus-artifact.properties"))) {
            artifact.load(reader);
        }
        return "native".equals(artifact.getProperty("type"));
    }

    /**
     * The macOS executable declares the minimum macOS version and the SDK version of the java launcher of the JDK
     * (quarkus.desktop.swt.macos.jdk-build-version) : the same look of the windows and controls as in JVM mode, and it
     * starts on the macOS versions of the JDK, not only on the version of the SDK of the Xcode tools.
     */
    @Test
    // with the property false, the executable declares the versions of the Xcode tools : nothing stable to compare
    @DisabledIfSystemProperty(named = "quarkus.desktop.swt.macos.jdk-build-version", matches = "false")
    @DisabledIfEnvironmentVariable(named = "QUARKUS_DESKTOP_SWT_MACOS_JDK_BUILD_VERSION", matches = "false")
    void macExecutableHasTheBuildVersionOfTheJavaLauncher() throws IOException, InterruptedException {
        Path jdkHome = builderJdkHome();
        assertEquals(buildVersion(jdkHome.resolve("bin").resolve("java")), buildVersion(executable),
                "the build version of the java launcher of " + jdkHome);
    }

    /**
     * The JDK of the native build, in the order of the extension (DesktopSwtProcessor.builderJdkHome) :
     * quarkus.native.graalvm-home (GRAALVM_HOME by default), then quarkus.native.java-home, then java.home. Failsafe
     * gives the test the -D properties of the Maven command line.
     */
    private static Path builderJdkHome() {
        String[] homes = { System.getProperty("quarkus.native.graalvm-home"), System.getenv("GRAALVM_HOME"),
                System.getProperty("quarkus.native.java-home") };
        for (String home : homes) {
            if (home != null && !home.isBlank() && Files.isDirectory(Path.of(home))) {
                return Path.of(home);
            }
        }
        return Path.of(System.getProperty("java.home"));
    }

    /**
     * The minimum macOS version and the SDK version of a Mach-O file ({@code otool -l}).
     */
    private static List<String> buildVersion(Path file) throws IOException, InterruptedException {
        List<String> versions = new ArrayList<>();
        String command = "";
        for (String line : run("otool", "-l", file.toString()).lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("cmd ")) {
                command = trimmed.substring(4);
            } else if (command.equals("LC_BUILD_VERSION")
                    && (trimmed.startsWith("minos ") || trimmed.startsWith("sdk "))
                    || command.equals("LC_VERSION_MIN_MACOSX")
                            && (trimmed.startsWith("version ") || trimmed.startsWith("sdk "))) {
                // not the versions of the tools that built the file (LC_BUILD_VERSION "tool ... version ...")
                versions.add(trimmed);
            }
        }
        assertFalse(versions.isEmpty(), "no build version in " + file);
        return versions;
    }

    /**
     * The standard output of a command, which must succeed.
     */
    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + " failed :\n" + output);
        return output;
    }
}
