package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The windows disposed when an application stops in dev and test modes, while the event dispatch thread is blocked, as
 * it is in {@code System.exit} after a {@code JFrame.EXIT_ON_CLOSE} (it waits for the shutdown hooks, which stop the
 * application) : the shutdown does not wait for ever, the windows are disposed once the event dispatch thread is free.
 * Needs a display.
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
            shutdown.join((DesktopAwtRecorder.DISPOSE_TIMEOUT_SECONDS + 5) * 1000);
            assertFalse(shutdown.isAlive(), "the shutdown waits for the blocked event dispatch thread");
        } finally {
            release.countDown();
        }
        EventQueue.invokeAndWait(() -> {
            // the dispose task queued before this one ran
            assertFalse(frame.get().isDisplayable());
        });
    }
}
