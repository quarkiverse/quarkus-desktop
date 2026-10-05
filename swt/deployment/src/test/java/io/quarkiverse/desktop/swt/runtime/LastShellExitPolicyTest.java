package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;

/**
 * The exit on last shell closed policy : the last visible shell closed stops the application, the shells disposed with
 * the {@code Display} when the application disposes it do not (the event loop stops the application then, and says
 * why). The {@code Display} is created on the thread of the test : the first thread of the process on macOS, where the
 * tests run with {@code -XstartOnFirstThread}. Needs a display.
 */
class LastShellExitPolicyTest {

    /**
     * The events and tasks run at most after the shell closed.
     */
    static final int DISPATCH_LIMIT = 10_000;

    @Test
    void lastVisibleShellClosed() {
        Display display = new Display();
        try {
            AtomicInteger exits = new AtomicInteger();
            LastShellExitPolicy.install(display, exits::incrementAndGet);
            Shell shell = new Shell(display);
            shell.setSize(200, 100);
            shell.open();
            shell.dispose();
            // the shells are counted in a later task
            for (int i = 0; i < DISPATCH_LIMIT && display.readAndDispatch(); i++) {
                // the events of the shell, then the task counting the shells
            }
            assertEquals(1, exits.get());
        } finally {
            display.dispose();
        }
    }

    @Test
    void displayDisposedByTheApplication() {
        Display display = new Display();
        try {
            AtomicInteger exits = new AtomicInteger();
            LastShellExitPolicy.install(display, exits::incrementAndGet);
            Shell shell = new Shell(display);
            shell.setSize(200, 100);
            shell.open();
            // SWT sends the SWT.Dispose event of the Display, disposes the shells, then runs the tasks left (the one
            // counting the shells) while isDisposed() is still false
            display.dispose();
            assertEquals(0, exits.get());
        } finally {
            if (!display.isDisposed()) {
                // the setup failed : the next test of this JVM creates its own Display
                display.dispose();
            }
        }
    }
}
