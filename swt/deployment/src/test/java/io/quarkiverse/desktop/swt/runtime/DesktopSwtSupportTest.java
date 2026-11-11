package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where SWT finds and extracts its native libraries : in a strictly confined snap, under {@code SNAP_USER_COMMON},
 * unless {@code swt.library.path} is set, and next to a native executable built with
 * {@code quarkus.desktop.swt.native-libraries=next-to-executable}.
 */
class DesktopSwtSupportTest {

    /**
     * The home directory of the user ({@code user.home}).
     */
    private static final String USER_HOME = "/home/jane";

    /**
     * The environment that snapd gives a strictly confined snap : a home directory of its own.
     */
    private static final Map<String, String> STRICT = Map.of("SNAP_USER_COMMON", "/home/jane/snap/acme-editor/common",
            "SNAP_USER_DATA", "/home/jane/snap/acme-editor/x1", "HOME", "/home/jane/snap/acme-editor/x1");

    /**
     * The {@code swt.library.path} of the JVM of the tests, restored after each test.
     */
    private String libraryPath;

    /**
     * Each test starts without {@code swt.library.path}.
     */
    @BeforeEach
    void saveLibraryPath() {
        libraryPath = System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH);
        System.clearProperty(DesktopSwtSupport.SWT_LIBRARY_PATH);
    }

    /**
     * The other tests of the JVM get the {@code swt.library.path} that it had, and no directory of a snap.
     */
    @AfterEach
    void restoreLibraryPath() {
        if (libraryPath == null) {
            System.clearProperty(DesktopSwtSupport.SWT_LIBRARY_PATH);
        } else {
            System.setProperty(DesktopSwtSupport.SWT_LIBRARY_PATH, libraryPath);
        }
        DesktopSwtSupport.snapLibraryPath = null;
        DesktopSwtSupport.librariesNextToExecutable = null;
    }

    /**
     * The directory of SWT, for each architecture, in the {@code SNAP_USER_COMMON} that the revisions share.
     */
    @Test
    void theDirectoryOfAStrictlyConfinedSnap() {
        assertEquals(Optional.of(Path.of("/home/jane/snap/acme-editor/common", ".swt", "lib", "linux", "x86_64")),
                DesktopSwtSupport.snapLibraryPath(STRICT, USER_HOME, "Linux", "amd64"));
        assertEquals(Optional.of(Path.of("/home/jane/snap/acme-editor/common", ".swt", "lib", "linux", "aarch64")),
                DesktopSwtSupport.snapLibraryPath(STRICT, USER_HOME, "Linux", "aarch64"));
    }

    /**
     * Outside a snap, and in a classic snap or an application that it starts (a terminal of an IDE installed as a
     * classic snap), whose {@code HOME} is the home directory of the user : SWT extracts into {@code ~/.swt}.
     */
    @Test
    void noDirectoryOutsideAStrictlyConfinedSnap() {
        assertEquals(Optional.empty(), DesktopSwtSupport.snapLibraryPath(Map.of("HOME", USER_HOME), USER_HOME, "Linux",
                "amd64"));
        assertEquals(Optional.empty(), DesktopSwtSupport.snapLibraryPath(Map.of("SNAP_USER_COMMON",
                "/home/jane/snap/code/common", "SNAP_USER_DATA", "/home/jane/snap/code/200", "HOME", USER_HOME),
                USER_HOME, "Linux", "amd64"));
        assertEquals(Optional.empty(), DesktopSwtSupport.snapLibraryPath(Map.of("SNAP_USER_COMMON", " ", "HOME",
                "/home/jane/snap/acme-editor/x1"), USER_HOME, "Linux", "amd64"));
    }

    /**
     * The directory is created and is {@code swt.library.path}, until the libraries next to a native executable take
     * its place.
     */
    @Test
    void theLibraryPathOfASnap(@TempDir Path common) {
        DesktopSwtSupport.useSnapLibraryPath(strict(common));
        Path directory = common.resolve(".swt/lib").resolve(SwtUi.swtOs(System.getProperty("os.name")))
                .resolve(SwtUi.swtArch(System.getProperty("os.arch")));
        assertEquals(directory.toString(), System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
        // SWT does not create the directory of swt.library.path, where it extracts its libraries
        assertTrue(Files.isDirectory(directory), directory + " is a directory");

        // the libraries next to a native executable take precedence : here, the executable of this JVM
        Optional<Path> executable = ProcessHandle.current().info().command().map(Path::of);
        assumeTrue(executable.isPresent(), "The executable of the process is not known");
        DesktopSwtSupport.useLibrariesNextToExecutable(executable.get().getFileName().toString());
        assertEquals(executable.get().toAbsolutePath().getParent().toString(),
                System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
    }

    /**
     * A {@code swt.library.path} set by the user wins, in a snap and next to a native executable.
     */
    @Test
    void aLibraryPathThatIsSetIsKept(@TempDir Path common) {
        System.setProperty(DesktopSwtSupport.SWT_LIBRARY_PATH, "/opt/libraries");
        DesktopSwtSupport.useSnapLibraryPath(strict(common));
        assertEquals("/opt/libraries", System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
        assertFalse(Files.exists(common.resolve(".swt")), "No directory created in " + common);
        Optional<Path> executable = ProcessHandle.current().info().command().map(Path::of);
        assumeTrue(executable.isPresent(), "The executable of the process is not known");
        DesktopSwtSupport.useLibrariesNextToExecutable(executable.get().getFileName().toString());
        assertEquals("/opt/libraries", System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
    }

    /**
     * A library that is missing next to the native executable, in a strictly confined snap : SWT looks for it in the
     * directory of the snap, which the service of the libraries applies before the service of the user interface, in
     * either order.
     */
    @Test
    void aMissingLibraryInASnap(@TempDir Path common) {
        Path directory = common.resolve(".swt/lib").resolve(SwtUi.swtOs(System.getProperty("os.name")))
                .resolve(SwtUi.swtArch(System.getProperty("os.arch")));
        DesktopSwtSupport.useLibrariesNextToExecutable("libswt-missing.so", strict(common));
        assertEquals(directory.toString(), System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
        assertTrue(Files.isDirectory(directory), directory + " is a directory");
        assertNull(DesktopSwtSupport.librariesNextToExecutable);
        // the service of the user interface, afterwards
        DesktopSwtSupport.useSnapLibraryPath(strict(common));
        assertEquals(directory.toString(), System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
        // the services in the other order
        DesktopSwtSupport.useLibrariesNextToExecutable("libswt-missing.so", strict(common));
        assertEquals(directory.toString(), System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
        assertNull(DesktopSwtSupport.librariesNextToExecutable);
    }

    /**
     * Outside a snap, a library that is missing next to the native executable leaves {@code swt.library.path} unset :
     * SWT looks for it in {@code ~/.swt}.
     */
    @Test
    void aMissingLibraryOutsideASnap() {
        DesktopSwtSupport.useLibrariesNextToExecutable("libswt-missing.so", Map.of("HOME", USER_HOME));
        assertNull(System.getProperty(DesktopSwtSupport.SWT_LIBRARY_PATH));
        assertNull(DesktopSwtSupport.librariesNextToExecutable);
    }

    /**
     * The warning of a library that is missing next to the native executable names the places where SWT looks for it,
     * in its order : the directory of a snap ({@code swt.library.path}) when it is set, {@code ~/.swt} otherwise.
     */
    @Test
    void theWarningOfAMissingLibrary() {
        String executable = "/snap/acme-editor/x1/lib/acme-editor";
        String snap = "/home/jane/snap/acme-editor/common/.swt/lib/linux/x86_64";
        assertEquals("The native library libswt-gtk-4974r10.so of SWT is not next to the native executable (" + executable
                + ") : SWT looks for it in " + snap + " (swt.library.path) and in java.library.path",
                DesktopSwtSupport.missingLibraryMessage("libswt-gtk-4974r10.so", executable, snap, "Linux", "amd64"));
        assertEquals("The native library libswt-gtk-4974r10.so of SWT is not next to the native executable"
                + " (/opt/acme-editor) : SWT looks for it in java.library.path and in ~/.swt/lib/linux/x86_64",
                DesktopSwtSupport.missingLibraryMessage("libswt-gtk-4974r10.so", "/opt/acme-editor", null, "Linux",
                        "amd64"));
    }

    /**
     * The environment of a strictly confined snap whose {@code SNAP_USER_COMMON} is a directory of the test.
     */
    private static Map<String, String> strict(Path common) {
        return Map.of("SNAP_USER_COMMON", common.toString(), "SNAP_USER_DATA", common.resolveSibling("x1").toString(),
                "HOME", common.resolveSibling("x1").toString());
    }
}
