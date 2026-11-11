package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The build steps of the packages.
 */
class DesktopPackagingProcessorTest {

    /**
     * A native executable compressed with UPX hides its shared libraries from the automatic dependencies of the Debian
     * and RPM packages.
     */
    @Test
    void theWarningOfACompressedNativeExecutable() {
        DesktopPackagingConfig.Linux config = TestConfig.defaults().linux();
        String deb = DesktopPackagingProcessor.compressionWarning(config, List.of(PackageFormat.DEB), true).orElseThrow();
        assertTrue(deb.contains("quarkus.native.compression.level"), deb);
        assertTrue(deb.contains("quarkus.desktop.packaging.linux.deb.depends"), deb);
        assertFalse(deb.contains("rpm.requires"), deb);
        String rpm = DesktopPackagingProcessor.compressionWarning(config, List.of(PackageFormat.RPM), true).orElseThrow();
        assertTrue(rpm.contains("quarkus.desktop.packaging.linux.rpm.requires"), rpm);
        assertFalse(rpm.contains("deb.depends"), rpm);
        String both = DesktopPackagingProcessor.compressionWarning(config,
                List.of(PackageFormat.DIRECTORY, PackageFormat.DEB, PackageFormat.RPM), true).orElseThrow();
        assertTrue(both.contains("quarkus.desktop.packaging.linux.deb.depends and"
                + " quarkus.desktop.packaging.linux.rpm.requires"), both);

        // not compressed, no format with automatic dependencies, or no automatic dependencies
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.compressionWarning(config, List.of(PackageFormat.DEB, PackageFormat.RPM), false));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.compressionWarning(config,
                List.of(PackageFormat.DIRECTORY, PackageFormat.APPIMAGE, PackageFormat.FLATPAK, PackageFormat.SNAP), true));
        DesktopPackagingConfig.Linux manual = TestConfig.of(Map.of("linux.deb.auto-depends", "false",
                "linux.rpm.auto-requires", "false")).linux();
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.compressionWarning(manual, List.of(PackageFormat.DEB, PackageFormat.RPM), true));
    }

    /**
     * A resource file that the application links itself has the identifiers of the icon and of the version
     * information of the one of the extension : the extension does not link its own then.
     */
    @Test
    void theResourceFileThatTheApplicationLinks() {
        assertEquals(Optional.of("C:\\app\\app.res"), DesktopPackagingProcessor
                .linkedResourceFile(List.of("--verbose", "-H:NativeLinkerOption=C:\\app\\app.res")));
        assertEquals(Optional.of("C:\\my app\\APP.RES"), DesktopPackagingProcessor
                .linkedResourceFile(List.of(" -H:NativeLinkerOption=\"C:\\my app\\APP.RES\" ")));
        // other linker options and files
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.linkedResourceFile(List.of("-H:NativeLinkerOption=/STACK:8388608")));
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.linkedResourceFile(List.of("-H:NativeLinkerOption=user32.lib")));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.linkedResourceFile(List.of("-H:Name=app.res")));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.linkedResourceFile(List.of()));
    }

    /**
     * A native executable built in a container is a Linux executable : on Windows and macOS, it is not packaged.
     */
    @Test
    void theFormatsOfANativeExecutable() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("formats", "msi,dmg,deb,directory"));
        assertEquals(List.of(), DesktopPackagingProcessor.nativeFormats(config, TargetOs.LINUX, TargetOs.WINDOWS));
        assertEquals(List.of(), DesktopPackagingProcessor.nativeFormats(config, TargetOs.LINUX, TargetOs.MACOS));
        // a container build on Linux, and a build without a container
        assertEquals(List.of(PackageFormat.DEB, PackageFormat.DIRECTORY),
                DesktopPackagingProcessor.nativeFormats(config, TargetOs.LINUX, TargetOs.LINUX));
        assertEquals(List.of(PackageFormat.MSI, PackageFormat.DIRECTORY),
                DesktopPackagingProcessor.nativeFormats(config, TargetOs.WINDOWS, TargetOs.WINDOWS));
        assertEquals(List.of(), DesktopPackagingProcessor.nativeFormats(TestConfig.defaults(), TargetOs.LINUX,
                TargetOs.WINDOWS));
    }

    /**
     * A native-image bundle is built instead of the executable, and the executable of a build with profile-guided
     * optimization is instrumented : the optimized one is packaged by a second build, which reuses it.
     */
    @Test
    void aNativeExecutableThatIsNotPackaged() {
        assertEquals(Optional.empty(), DesktopPackagingProcessor.nativeExecutableUnavailable(false, false, false));
        String bundle = DesktopPackagingProcessor.nativeExecutableUnavailable(true, false, false).orElseThrow();
        assertTrue(bundle.contains("quarkus.native.bundle"), bundle);
        bundle = DesktopPackagingProcessor.nativeExecutableUnavailable(true, true, false).orElseThrow();
        assertTrue(bundle.contains("quarkus.native.bundle"), bundle);
        String pgo = DesktopPackagingProcessor.nativeExecutableUnavailable(false, true, false).orElseThrow();
        assertTrue(pgo.contains("quarkus.native.pgo.enabled"), pgo);
        assertTrue(pgo.contains("-Dquarkus.native.reuse-existing=true"), pgo);
        // the executable of a former build, which the native build returns without building a bundle or an executable
        assertEquals(Optional.empty(), DesktopPackagingProcessor.nativeExecutableUnavailable(false, true, true));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.nativeExecutableUnavailable(true, false, true));
    }

    /**
     * The Visual C++ runtime of a Windows native executable comes from the GraalVM of the native build first.
     */
    @Test
    void theJdksOfTheVisualCppRuntime() {
        Path graalvm = Path.of("graalvm");
        Path jdk = Path.of("jdk");
        Path build = Path.of("build");
        assertEquals(List.of(graalvm, jdk, build),
                DesktopPackagingProcessor.jdkHomes(Optional.of(" graalvm "), jdk.toFile(), build));
        assertEquals(List.of(jdk, build), DesktopPackagingProcessor.jdkHomes(Optional.of(" "), jdk.toFile(), build));
        // the Java home of the native build is the JDK of the build by default
        assertEquals(List.of(build), DesktopPackagingProcessor.jdkHomes(Optional.empty(), build.toFile(), build));
        assertEquals(List.of(build), DesktopPackagingProcessor.jdkHomes(Optional.empty(), null, build));
    }
}
