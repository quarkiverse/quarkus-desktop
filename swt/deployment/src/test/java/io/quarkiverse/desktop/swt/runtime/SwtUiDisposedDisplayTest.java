package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

/**
 * An exception that escapes {@code readAndDispatch()} once the {@code Display} is disposed is SWT's own (on Windows,
 * {@code WM_ENDSESSION} disposes it inside {@code PeekMessage}, then {@code Display.filterMessage} throws a
 * {@code NullPointerException}) : the event loop ends, the exception is not reported as an uncaught exception of the
 * application. A {@code Display} of its own (its own JVM : forkCount 1, reuseForks false), created on the thread of the
 * test : the first thread of the process on macOS, where the tests run with {@code -XstartOnFirstThread}. Needs a
 * display.
 */
class SwtUiDisposedDisplayTest {

    @Test
    void exceptionOnceTheDisplayIsDisposed() {
        Display display = new Display();
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        display.setRuntimeExceptionHandler(reported::add);
        display.setErrorHandler(reported::add);
        try {
            // a timer that disposes the Display, then throws what SWT does not catch (a checked exception)
            display.timerExec(10, () -> {
                display.dispose();
                SwtUiDisposedDisplayTest.<RuntimeException> sneakyThrow(new IOException("once disposed"));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!display.isDisposed() && System.nanoTime() < deadline) {
                assertDoesNotThrow(() -> SwtUi.dispatch(display));
            }
            assertTrue(display.isDisposed());
            // the event loop of the extension ends on a disposed Display, without reporting
            assertDoesNotThrow(() -> SwtUi.dispatch(display));
            assertEquals(List.of(), reported);
        } finally {
            if (!display.isDisposed()) {
                display.dispose();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable e) throws T {
        throw (T) e;
    }
}
