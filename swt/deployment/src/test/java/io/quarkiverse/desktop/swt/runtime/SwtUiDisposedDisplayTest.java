package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

/**
 * An exception that escapes {@code readAndDispatch()} once the {@code Display} is disposed : SWT's own (on Windows,
 * {@code WM_ENDSESSION} disposes it inside {@code PeekMessage}, then {@code Display.filterMessage} throws a
 * {@code NullPointerException}) ends the event loop without being reported as an uncaught exception of the application,
 * while one of the application (a listener or a timer that disposes the {@code Display}, then fails) is still reported :
 * by the event loop of the extension when it escapes {@code readAndDispatch()} (macOS, Windows, and a checked exception
 * everywhere), by SWT itself for the RuntimeExceptions and Errors of a timer on Linux. A {@code Display} of its own per
 * test (its own JVM : forkCount 1, reuseForks false), created on the thread of the
 * test : the first thread of the process on macOS, where the tests run with {@code -XstartOnFirstThread}. Needs a
 * display.
 */
class SwtUiDisposedDisplayTest {

    @Test
    void exceptionOfSwtOnceTheDisplayIsDisposed() {
        // thrown where SWT throws (Display.filterMessage). A checked exception, so that it escapes readAndDispatch() on
        // every platform : GTK gives the RuntimeExceptions and Errors of a timer to the handlers itself (timerProc)
        IOException swt = new IOException("of SWT");
        swt.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.eclipse.swt.widgets.Display", "filterMessage", "Display.java", 1) });
        List<Throwable> reported = disposeThenThrow(swt);
        // the event loop of the extension ends on a disposed Display, without reporting
        assertEquals(List.of(), reported);
    }

    @Test
    void exceptionOfTheApplicationOnceTheDisplayIsDisposed() {
        IllegalStateException application = new IllegalStateException("of the application");
        List<Throwable> reported = disposeThenThrow(application);
        assertEquals(List.of(application), reported);
    }

    @Test
    void checkedExceptionOfTheApplicationOnceTheDisplayIsDisposed() {
        // what SWT does not catch : a checked exception, thrown without being declared
        IOException application = new IOException("of the application");
        List<Throwable> reported = disposeThenThrow(application);
        assertEquals(1, reported.size(), reported.toString());
        UndeclaredThrowableException wrapped = assertInstanceOf(UndeclaredThrowableException.class, reported.get(0));
        assertSame(application, wrapped.getCause());
    }

    @Test
    void exceptionWithoutStackTraceOnceTheDisplayIsDisposed() {
        // nothing tells that SWT threw it : reported
        IllegalStateException unknown = new IllegalStateException("without stack trace");
        unknown.setStackTrace(new StackTraceElement[0]);
        List<Throwable> reported = disposeThenThrow(unknown);
        assertEquals(List.of(unknown), reported);
    }

    @Test
    void thrownBySwt() {
        NullPointerException swt = new NullPointerException();
        swt.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.eclipse.swt.widgets.Display", "runTimers", "Display.java", 1),
                new StackTraceElement("com.acme.Main", "main", "Main.java", 1) });
        assertTrue(SwtUi.thrownBySwt(swt));
        // thrown by the application, rethrown by SWT : the stack trace keeps the frame of the application on top
        assertFalse(SwtUi.thrownBySwt(new IllegalStateException()));
        IllegalStateException empty = new IllegalStateException();
        empty.setStackTrace(new StackTraceElement[0]);
        assertFalse(SwtUi.thrownBySwt(empty));
    }

    /**
     * A timer that disposes the {@code Display}, then throws : the event loop of the extension runs until the
     * {@code Display} is disposed, and once more. Returns what reached the handlers of the {@code Display}.
     */
    private static List<Throwable> disposeThenThrow(Throwable thrown) {
        Display display = new Display();
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        display.setRuntimeExceptionHandler(reported::add);
        display.setErrorHandler(reported::add);
        try {
            display.timerExec(10, () -> {
                display.dispose();
                SwtUiDisposedDisplayTest.<RuntimeException> sneakyThrow(thrown);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!display.isDisposed() && System.nanoTime() < deadline) {
                assertDoesNotThrow(() -> SwtUi.dispatch(display));
            }
            assertTrue(display.isDisposed());
            assertDoesNotThrow(() -> SwtUi.dispatch(display));
            return reported;
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
