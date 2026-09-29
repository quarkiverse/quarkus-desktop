package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The windows disposed when an application stops in dev mode, while the event dispatch thread is blocked, as it is in
 * {@code System.exit} after a {@code JFrame.EXIT_ON_CLOSE} (it waits for the shutdown hooks, which stop the
 * application). Needs a display.
 */
class DisposeWindowsTest {

    @Test
    void disposeWhileEventDispatchThreadIsBlocked() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AtomicReference<Frame> frame = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame f = new Frame("DisposeWindowsTest");
            // displayable, not shown
            f.pack();
            frame.set(f);
        });
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        Thread shutdown = new Thread(DesktopAwtRecorder::disposeWindows, "shutdown");
        shutdown.setDaemon(true);
        shutdown.start();
        try {
            shutdown.join(3_000);
            // Window.dispose() waits for the event dispatch thread (EventQueue.invokeAndWait) : a deadlock
            assertTrue(shutdown.isAlive(), "the windows are disposed while the event dispatch thread is blocked");
        } finally {
            release.countDown();
        }
        shutdown.join(10_000);
        EventQueue.invokeAndWait(() -> assertTrue(!frame.get().isDisplayable()));
    }
}
