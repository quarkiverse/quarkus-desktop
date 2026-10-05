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
     * The JDK of the native build, chosen as the extension chooses it (DesktopSwtProcessor.builderJdkHome) and as Quarkus
     * chooses its native-image : quarkus.native.graalvm-home (QUARKUS_NATIVE_GRAALVM_HOME, GRAALVM_HOME by default),
     * then quarkus.native.java-home (QUARKUS_NATIVE_JAVA_HOME, java.home by default), each when it has
     * bin/native-image, else the native-image of the PATH (links resolved, up to the directory of the release file of
     * the JDK), else java.home. Failsafe gives the test the -D properties of the Maven command line, not the
     * application.properties : set the homes there with -D or the environment.
     */
    private static Path builderJdkHome() throws IOException {
        String javaHome = System.getProperty("java.home");
        String[] homes = { first(System.getProperty("quarkus.native.graalvm-home"),
                System.getenv("QUARKUS_NATIVE_GRAALVM_HOME"), System.getenv("GRAALVM_HOME")),
                first(System.getProperty("quarkus.native.java-home"), System.getenv("QUARKUS_NATIVE_JAVA_HOME"),
                        javaHome) };
        for (String home : homes) {
            if (home != null && Files.isRegularFile(Path.of(home, "bin", "native-image"))) {
                return Path.of(home);
            }
        }
        String path = System.getenv("PATH");
        for (String directory : path == null ? new String[0] : path.split(java.io.File.pathSeparator)) {
            Path candidate = directory.isBlank() ? null : Path.of(directory, "native-image");
            if (candidate != null && Files.isRegularFile(candidate)) {
                // GraalVM links <home>/bin/native-image to <home>/lib/svm/bin/native-image
                for (Path home = candidate.toRealPath().getParent(); home != null; home = home.getParent()) {
                    if (Files.isRegularFile(home.resolve("release"))) {
                        return home;
                    }
                }
            }
        }
        return Path.of(javaHome);
    }

    /**
     * The first value that is set, as SmallRye Config resolves a property (system property, environment variable,
     * default).
     */
    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
