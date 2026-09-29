package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The application stops when its last visible window is closed or hidden, once a first window opened. Needs a display.
 */
class LastWindowExitPolicyTest {

    @Test
    void exitsWhenLastVisibleWindowCloses() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AtomicInteger exits = new AtomicInteger();
        LastWindowExitPolicy policy = LastWindowExitPolicy.install(exits::incrementAndGet);
        AtomicReference<Frame> first = new AtomicReference<>();
        AtomicReference<Frame> second = new AtomicReference<>();
        try {
            // not armed : a window disposed before any window opened
            EventQueue.invokeAndWait(() -> {
                Frame splash = new Frame("splash");
                splash.pack();
                splash.dispose();
            });
            flush();
            assertEquals(0, exits.get());

            first.set(show("first"));
            second.set(show("second"));
            // one window hidden (HIDE_ON_CLOSE), the other one stays visible
            EventQueue.invokeAndWait(() -> first.get().setVisible(false));
            flush();
            assertEquals(0, exits.get());
            // a window disposed and another one shown by the same event handler
            EventQueue.invokeAndWait(() -> {
                second.get().dispose();
                first.get().setVisible(true);
            });
            flush();
            assertEquals(0, exits.get());
            // the last visible window closes
            EventQueue.invokeAndWait(() -> first.get().dispose());
            flush();
            assertEquals(1, exits.get());
        } finally {
            policy.remove();
            EventQueue.invokeAndWait(() -> {
                if (first.get() != null) {
                    first.get().dispose();
                }
                if (second.get() != null) {
                    second.get().dispose();
                }
            });
        }
    }

    private static Frame show(String title) throws Exception {
        AtomicReference<Frame> frame = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame f = new Frame(title);
            f.setSize(200, 100);
            f.setVisible(true);
            frame.set(f);
        });
        flush();
        return frame.get();
    }

    /**
     * Waits for the window events (posted by the native peers) and the checks they queue.
     */
    private static void flush() throws Exception {
        for (int i = 0; i < 5; i++) {
            Thread.sleep(200);
            EventQueue.invokeAndWait(() -> {
            });
        }
    }
}
