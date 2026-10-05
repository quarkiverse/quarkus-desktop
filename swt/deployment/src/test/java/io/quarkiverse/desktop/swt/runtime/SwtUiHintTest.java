package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The hint of the error logged when the {@code Display} cannot be created : on macOS, the JVM option in JVM mode, the
 * native image option in a native executable, and the option of the Desktop AWT extension when it parked the first
 * thread.
 */
class SwtUiHintTest {

    /**
     * A failure of {@code new Display()} other than the loading of the native libraries.
     */
    private static final Throwable FAILURE = new IllegalStateException("Invalid thread access");

    @Test
    void macosInJvmMode() {
        String hint = SwtUi.hint(FAILURE, "Mac OS X", false, null);
        assertTrue(hint.contains("start the JVM with -XstartOnFirstThread"), hint);
        assertTrue(hint.contains("run SwtLifecycle.run() on the main thread"), hint);
        assertTrue(hint.contains("not the dev mode"), hint);
        assertFalse(hint.contains("RunMainInNewThread"), hint);
        assertFalse(hint.contains("park-main-thread"), hint);
    }

    @Test
    void macosInANativeExecutable() {
        // the JVM options do not apply : main runs on the first thread, unless the executable is built to move it to a
        // new thread, named main too
        String hint = SwtUi.hint(FAILURE, "Mac OS X", true, null);
        assertTrue(hint.contains("must not be built with -H:+RunMainInNewThread"), hint);
        assertTrue(hint.contains("SwtLifecycle.run() must run on the first thread of the process"), hint);
        assertFalse(hint.contains("main thread"), hint);
        assertFalse(hint.contains("park-main-thread"), hint);
        assertFalse(hint.contains("-XstartOnFirstThread"), hint);
        assertFalse(hint.contains("dev mode"), hint);
    }

    @Test
    void macosInANativeExecutableWhoseFirstThreadIsParkedByAwt() {
        // the Desktop AWT extension with the main of another extension (Picocli...) : the first thread runs the Cocoa
        // event loop, the application runs on a new thread named main (io.quarkiverse.desktop.main-thread-parked=true)
        String hint = SwtUi.hint(FAILURE, "Mac OS X", true, "true");
        assertTrue(hint.contains("the Desktop AWT extension keeps that thread for the Cocoa event loop"), hint);
        assertTrue(hint.contains("with quarkus.desktop.awt.macos.park-main-thread=false"), hint);
        assertTrue(hint.contains("SwtLifecycle.run() must run on the first thread of the process"), hint);
        assertFalse(hint.contains("main thread"), hint);
        assertFalse(hint.contains("RunMainInNewThread"), hint);
        assertFalse(hint.contains("-XstartOnFirstThread"), hint);
    }

    @Test
    void otherPlatforms() {
        for (boolean nativeExecutable : new boolean[] { false, true }) {
            String linux = SwtUi.hint(FAILURE, "Linux", nativeExecutable, null);
            assertTrue(linux.contains("SWT needs GTK 3 and a display"), linux);
            assertEquals("", SwtUi.hint(FAILURE, "Windows 11", nativeExecutable, null));
            // the SWT jar of another platform, on every platform
            String library = SwtUi.hint(new UnsatisfiedLinkError(), "Mac OS X", nativeExecutable, "true");
            assertTrue(library.startsWith(" (SWT could not load its native libraries : "), library);
            assertFalse(library.contains("first thread"), library);
            // the native libraries that an executable of another architecture extracted, which SWT loads
            String cache = "delete ~/.swt/lib/macosx/" + SwtUi.swtArch(System.getProperty("os.arch")) + ", where an"
                    + " executable of another architecture may have left its libraries (SWT never replaces them)";
            assertTrue(library.contains(cache), library);
            // and the jar to use in a native executable ; in JVM mode the jar was checked before (SwtUi.checkSwtJar)
            assertEquals(nativeExecutable, library.contains("Mac OS X ") && library.contains(
                    "org.eclipse.platform:org.eclipse.swt.cocoa.macosx." + SwtUi.swtArch(System.getProperty("os.arch"))),
                    library);
            String loading = SwtUi.hint(new UnsatisfiedLinkError("Can't load library: /home/user/.swt/lib/linux/x86_64/"
                    + "libswt-pi3-gtk-4971r6.so"), "Linux", nativeExecutable, null);
            assertTrue(loading.contains("delete ~/.swt/lib/linux/" + SwtUi.swtArch(System.getProperty("os.arch"))
                    + ", where"), loading);
            // in JVM mode the jar was checked before : only the cache
            assertEquals(nativeExecutable, loading.contains("org.eclipse.platform:"), loading);
            // not when the SWT jar is the cause (SwtUi.checkSwtJar)
            String jar = SwtUi.hint(new UnsatisfiedLinkError(SwtUi.SWT_JAR_ERROR + "file:/app/lib/swt.jar has no"
                    + " SWT-OS and SWT-Arch attributes in its manifest"), "Mac OS X", nativeExecutable, null);
            assertTrue(jar.contains("the manifest of the SWT jar is missing, use the default fast-jar packaging"), jar);
            assertFalse(jar.contains("~/.swt/lib"), jar);
            assertFalse(jar.contains("org.eclipse.platform:"), jar);
            // the SWT jar of another platform (SwtUi.checkSwtJar) : the jar to use, not the cache
            String other = SwtUi.hint(new UnsatisfiedLinkError(SwtUi.SWT_JAR_ERROR + "file:/app/lib/swt.jar declares,"
                    + " in its manifest, SWT-OS macosx and SWT-Arch x86_64, not macosx and aarch64"), "Mac OS X",
                    nativeExecutable, null);
            assertTrue(other.contains("the SWT jar of the application must be the one of"), other);
            assertFalse(other.contains("~/.swt/lib"), other);
            // a system library missing on Linux (GTK 3 not installed) : GTK 3, not the cache nor the jar
            String gtk = SwtUi.hint(new UnsatisfiedLinkError("Could not load SWT library. Reasons: \n\tno swt-pi3-gtk-"
                    + "4971r15 in java.library.path\n\t/home/u/.swt/lib/linux/x86_64/libswt-pi3-gtk-4971r15.so:"
                    + " libgtk-3.so.0: cannot open shared object file: No such file or directory"), "Linux",
                    nativeExecutable, null);
            assertTrue(gtk.contains("libgtk-3.so.0 is missing, on Linux SWT needs GTK 3"), gtk);
            assertFalse(gtk.contains("~/.swt/lib"), gtk);
            assertFalse(gtk.contains("org.eclipse.platform:"), gtk);
            // swt.library.path (the libraries next to a native executable...) : SWT extracts there, not in ~/.swt/lib
            String path = SwtUi.hint(new UnsatisfiedLinkError("Can't load library: /opt/app/libswt-gtk-4971r15.so"),
                    "Linux", nativeExecutable, null, "/opt/app");
            assertTrue(path.contains("delete the SWT libraries of /opt/app (swt.library.path), where"), path);
            assertFalse(path.contains("~/.swt/lib"), path);
        }
    }

    @Test
    void missingSystemLibrary() {
        assertEquals("libgtk-3.so.0", SwtUi.missingSystemLibrary("/home/u/.swt/lib/linux/x86_64/libswt-pi3-gtk-4971r15"
                + ".so: libgtk-3.so.0: cannot open shared object file: No such file or directory"));
        assertEquals("libXtst.so.6", SwtUi.missingSystemLibrary("Could not load SWT library. Reasons: \n\t/tmp/libswt-"
                + "gtk-4971r15.so: libXtst.so.6: cannot open shared object file: No such file or directory"));
        // the library of SWT itself is missing : not a system library
        assertEquals(null, SwtUi.missingSystemLibrary("Could not load SWT library. Reasons: \n\t/tmp/x/libswt-gtk-"
                + "4971r15.so: cannot open shared object file: No such file or directory"));
        assertEquals(null, SwtUi.missingSystemLibrary("/tmp/libswt-gtk-4971r15.so: wrong ELF class: ELFCLASS32"));
        assertEquals(null, SwtUi.missingSystemLibrary(""));
    }
}
