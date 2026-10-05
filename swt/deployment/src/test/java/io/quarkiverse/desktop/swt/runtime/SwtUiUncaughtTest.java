package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exceptions and errors that escape {@code readAndDispatch()} go to the handlers of the {@code Display}, as the
 * ones of the listeners and of the tasks : on macOS, a timer that fired while the event loop slept runs in
 * {@code readAndDispatch()} without them ({@code Display.runTimers()}). A handler that throws does not end the event
 * loop. The {@code Display} is created on the thread of the test : the first thread of the process on macOS, where the
 * tests run with {@code -XstartOnFirstThread}. Needs a display.
 */
class SwtUiUncaughtTest {

    static Display display;

    final List<RuntimeException> exceptions = new CopyOnWriteArrayList<>();

    final List<Error> errors = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createDisplay() {
        display = new Display();
    }

    @AfterAll
    static void disposeDisplay() {
        display.dispose();
    }

    @BeforeEach
    void handlers() {
        display.setRuntimeExceptionHandler(exceptions::add);
        display.setErrorHandler(errors::add);
    }

    @Test
    void timerFiredWhileTheEventLoopSlept() {
        IllegalStateException failure = new IllegalStateException("timer");
        display.timerExec(10, () -> {
            throw failure;
        });
        Runnable wake = () -> {
        };
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (exceptions.isEmpty() && System.nanoTime() < deadline) {
            // ends the sleep of the event loop, should the exception be lost
            display.timerExec(500, wake);
            SwtUi.dispatch(display);
        }
        display.timerExec(-1, wake);
        assertEquals(List.of(failure), exceptions);
        assertEquals(List.of(), errors);
    }

    @Test
    void exceptionsAndErrors() {
        IllegalStateException exception = new IllegalStateException("task");
        SwtUi.uncaught(display, exception);
        AssertionError error = new AssertionError("task");
        SwtUi.uncaught(display, error);
        assertEquals(List.of(exception), exceptions);
        assertEquals(List.of(error), errors);
    }

    @Test
    void handlersThatThrow() {
        // as the default handlers of SWT, which rethrow
        display.setRuntimeExceptionHandler(e -> {
            throw e;
        });
        display.setErrorHandler(e -> {
            throw new IllegalStateException("the error handler failed", e);
        });
        assertDoesNotThrow(() -> SwtUi.uncaught(display, new IllegalStateException("task")));
        assertDoesNotThrow(() -> SwtUi.uncaught(display, new AssertionError("task")));
        // the event loop goes on
        display.timerExec(10, () -> {
            throw new IllegalStateException("timer");
        });
        display.timerExec(50, () -> display.setData("ran"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (display.getData() == null && System.nanoTime() < deadline) {
            assertDoesNotThrow(() -> SwtUi.dispatch(display));
        }
        assertEquals("ran", display.getData());
        display.setData(null);
    }
}
