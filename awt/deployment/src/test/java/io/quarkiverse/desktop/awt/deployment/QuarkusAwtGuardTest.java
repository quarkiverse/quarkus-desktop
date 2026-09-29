package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;

/**
 * The guard against quarkus-awt substitutions that the extension does not remove (renamed or new ones), and the check of
 * the quarkus-awt versions that support macOS native executables.
 */
class QuarkusAwtGuardTest {

    private static final String RUNTIME = "io/quarkus/awt/runtime/";

    /**
     * The classes of the quarkus-awt runtime with macOS support (Quarkus 4.0 and later, the pull request "Enable quarkus-awt
     * on macOS", https://github.com/quarkusio/quarkus/pull/56979).
     */
    static final List<String> MAC_SUPPORT_CLASSES = List.of(
            "JDKSubstitutions.class",
            "MacHeadless.class",
            "Target_sun_awt_CGraphicsEnvironment.class",
            "Target_sun_awt_FontConfiguration_Linux.class",
            "Target_sun_awt_FontConfiguration_Mac.class",
            "Target_sun_awt_FontConfiguration_Windows.class",
            "Target_sun_awt_HeadlessToolkit.class",
            "Target_sun_awt_PlatformGraphicsInfo_Mac.class",
            "Target_sun_awt_im_CompositionAreaHandler.class",
            "Target_sun_awt_im_ExecutableInputMethodManager.class",
            "Target_sun_awt_windows_WObjectPeer.class",
            "Target_sun_awt_windows_WToolkit.class",
            "Target_sun_font_Type1Font.class",
            "Target_sun_java2d_windows_WindowsFlags.class",
            "Target_sun_lwawt_macosx_LWCToolkit.class",
            "Target_sun_print_PlatformPrinterJobProxy.class",
            "graal/AwtFeature.class",
            "graal/DarwinAwtFeature.class");

    @TempDir
    Path directory;

    /**
     * The quarkus-awt version of the build (3.40) : no macOS support.
     */
    @Test
    void quarkusAwtOfTheBuild() throws URISyntaxException, ClassNotFoundException {
        Path jar = Path.of(Class.forName("io.quarkus.awt.runtime.JDKSubstitutions", false, getClass().getClassLoader())
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        ResolvedDependency quarkusAwt = dependency(jar, "3.40.0");
        assertFalse(quarkusAwt.getContentTree().contains(DesktopAwtProcessor.QUARKUS_AWT_MAC_SENTINEL),
                "a Quarkus version with macOS support : update the version of this test");
        assertEquals(DesktopAwtProcessor.QUARKUS_AWT_WINDOWS_GUI_BLOCKERS,
                DesktopAwtProcessor.expectedGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
        assertEquals(Optional.of(String.format(DesktopAwtProcessor.MAC_QUARKUS_TOO_OLD, "3.40.0")),
                DesktopAwtProcessor.macUnsupportedMessage(quarkusAwt));
    }

    @Test
    void quarkusAwtWithMacSupport() throws IOException {
        ResolvedDependency quarkusAwt = quarkusAwt(MAC_SUPPORT_CLASSES.stream().map(c -> RUNTIME + c).toList());
        List<String> expected = new ArrayList<>(DesktopAwtProcessor.QUARKUS_AWT_WINDOWS_GUI_BLOCKERS);
        expected.addAll(DesktopAwtProcessor.QUARKUS_AWT_MAC_GUI_BLOCKERS);
        assertEquals(expected, DesktopAwtProcessor.expectedGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
        assertEquals(Optional.empty(), DesktopAwtProcessor.macUnsupportedMessage(quarkusAwt));
    }

    /**
     * A quarkus-awt jar given with {@code -Dquarkus-awt.jar=<path>} (for instance one built from the Quarkus branch with
     * macOS support) : every substitution is known, and the ones to remove are there.
     */
    @Test
    @EnabledIfSystemProperty(named = "quarkus-awt.jar", matches = ".+")
    void givenQuarkusAwt() {
        ResolvedDependency quarkusAwt = dependency(Path.of(System.getProperty("quarkus-awt.jar")), "999-SNAPSHOT");
        assertEquals(List.of(), DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
        assertEquals(Boolean.getBoolean("quarkus-awt.jar.mac"),
                DesktopAwtProcessor.macUnsupportedMessage(quarkusAwt).isEmpty());
    }

    @Test
    void knownSubstitutionsOnly() throws IOException {
        ResolvedDependency quarkusAwt = quarkusAwt(RUNTIME + "JDKSubstitutions.class",
                RUNTIME + "Target_sun_awt_FontConfiguration_Windows.class",
                RUNTIME + "Target_sun_awt_windows_WObjectPeer.class",
                RUNTIME + "Target_sun_java2d_windows_WindowsFlags.class",
                RUNTIME + "Target_sun_awt_windows_WToolkit.class",
                RUNTIME + "Target_sun_font_Type1Font.class");
        assertEquals(List.of(), DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
    }

    /**
     * The Type 1 fonts substitution is removed when present, but not expected : a quarkus-awt version without it supports
     * Type 1 fonts.
     */
    @Test
    void withoutType1FontSubstitution() throws IOException {
        ResolvedDependency quarkusAwt = quarkusAwt(RUNTIME + "JDKSubstitutions.class",
                RUNTIME + "Target_sun_awt_windows_WObjectPeer.class",
                RUNTIME + "Target_sun_java2d_windows_WindowsFlags.class",
                RUNTIME + "Target_sun_awt_windows_WToolkit.class");
        assertEquals(List.of(), DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(), DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
    }

    @Test
    void renamedType1FontSubstitution() throws IOException {
        ResolvedDependency quarkusAwt = quarkusAwt(RUNTIME + "JDKSubstitutions.class",
                RUNTIME + "Target_sun_awt_windows_WObjectPeer.class",
                RUNTIME + "Target_sun_java2d_windows_WindowsFlags.class",
                RUNTIME + "Target_sun_awt_windows_WToolkit.class",
                RUNTIME + "Target_sun_font_Type1Font_NotSupported.class");
        assertEquals(List.of(RUNTIME + "Target_sun_font_Type1Font_NotSupported.class"),
                DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
    }

    @Test
    void renamedWindowsSubstitution() throws IOException {
        ResolvedDependency quarkusAwt = quarkusAwt(RUNTIME + "JDKSubstitutions.class",
                RUNTIME + "Target_sun_awt_windows_WObjectPeer_Headless.class",
                RUNTIME + "Target_sun_java2d_windows_WindowsFlags.class",
                RUNTIME + "Target_sun_awt_windows_WToolkit.class");
        assertEquals(List.of(RUNTIME + "Target_sun_awt_windows_WObjectPeer.class"),
                DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(RUNTIME + "Target_sun_awt_windows_WObjectPeer_Headless.class"),
                DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
    }

    @Test
    void renamedMacSubstitution() throws IOException {
        List<String> entries = new ArrayList<>();
        for (String entry : MAC_SUPPORT_CLASSES) {
            entries.add(
                    RUNTIME + entry.replace("Target_sun_lwawt_macosx_LWCToolkit", "Target_sun_lwawt_macosx_LWCToolkit_Mac"));
        }
        entries.add(RUNTIME + "Target_sun_awt_SunToolkit.class");
        ResolvedDependency quarkusAwt = quarkusAwt(entries);
        assertEquals(List.of(RUNTIME + "Target_sun_lwawt_macosx_LWCToolkit.class"),
                DesktopAwtProcessor.missingGuiBlockers(quarkusAwt));
        assertEquals(List.of(RUNTIME + "Target_sun_awt_SunToolkit.class",
                RUNTIME + "Target_sun_lwawt_macosx_LWCToolkit_Mac.class"),
                DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
        assertTrue(DesktopAwtProcessor.macUnsupportedMessage(quarkusAwt).isEmpty());
    }

    /**
     * Without the macOS font configuration (the sentinel), the macOS substitutions are not expected : a version without
     * macOS support.
     */
    @Test
    void macSubstitutionsWithoutSentinel() throws IOException {
        ResolvedDependency quarkusAwt = quarkusAwt(MAC_SUPPORT_CLASSES.stream()
                .filter(c -> !c.equals("Target_sun_awt_FontConfiguration_Mac.class")).map(c -> RUNTIME + c).toList());
        assertEquals(DesktopAwtProcessor.QUARKUS_AWT_WINDOWS_GUI_BLOCKERS,
                DesktopAwtProcessor.expectedGuiBlockers(quarkusAwt));
        assertEquals(DesktopAwtProcessor.QUARKUS_AWT_MAC_GUI_BLOCKERS.stream().sorted().toList(),
                DesktopAwtProcessor.unknownSubstitutions(quarkusAwt));
        assertTrue(DesktopAwtProcessor.macUnsupportedMessage(quarkusAwt).isPresent());
    }

    private ResolvedDependency quarkusAwt(String... entries) throws IOException {
        return quarkusAwt(List.of(entries));
    }

    private ResolvedDependency quarkusAwt(List<String> entries) throws IOException {
        Path jar = directory.resolve("quarkus-awt.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
        }
        return dependency(jar, "3.40.0");
    }

    private static ResolvedDependency dependency(Path jar, String version) {
        return ResolvedDependencyBuilder.newInstance()
                .setCoords(ArtifactCoords.jar("io.quarkus", "quarkus-awt", version))
                .setResolvedPath(jar)
                .build();
    }
}
