package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
