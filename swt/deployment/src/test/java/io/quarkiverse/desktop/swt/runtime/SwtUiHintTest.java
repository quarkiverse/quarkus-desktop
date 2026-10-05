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
            assertTrue(library.contains("SWT could not load its native libraries") && library.contains("Mac OS X "),
                    library);
            assertFalse(library.contains("first thread"), library);
        }
    }
}
