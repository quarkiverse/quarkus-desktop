package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.AWTEventListenerProxy;
import java.util.Arrays;
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

    @Test
    void exitsWhenLastVisibleWindowIsHidden() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AtomicInteger exits = new AtomicInteger();
        AtomicReference<Frame> opened = new AtomicReference<>();
        AtomicReference<Frame> frame = new AtomicReference<>();
        LastWindowExitPolicy policy = null;
        try {
            // a window opened before the policy is installed (a splash screen)
            opened.set(show("opened before"));
            policy = LastWindowExitPolicy.install(exits::incrementAndGet);
            // the component events of the windows only, not of every component of the toolkit
            assertTrue(isListening(policy, AWTEvent.WINDOW_EVENT_MASK));
            assertFalse(isListening(policy, AWTEvent.COMPONENT_EVENT_MASK));

            frame.set(show("main"));
            EventQueue.invokeAndWait(() -> opened.get().setVisible(false));
            flush();
            assertEquals(0, exits.get());
            // the last visible window hidden (HIDE_ON_CLOSE) : the application stops, it would run for ever without window
            EventQueue.invokeAndWait(() -> frame.get().setVisible(false));
            flush();
            assertEquals(1, exits.get());
        } finally {
            if (policy != null) {
                policy.remove();
            }
            EventQueue.invokeAndWait(() -> {
                for (Frame f : new Frame[] { opened.get(), frame.get() }) {
                    if (f != null) {
                        f.dispose();
                    }
                }
            });
        }
    }

    private static boolean isListening(LastWindowExitPolicy policy, long mask) {
        return Arrays.stream(Toolkit.getDefaultToolkit().getAWTEventListeners(mask))
                .anyMatch(listener -> listener == policy
                        || listener instanceof AWTEventListenerProxy proxy && proxy.getListener() == policy);
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
