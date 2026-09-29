package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
        Thread shutdown = new Thread(() -> DesktopAwtRecorder.disposeWindows(null), "shutdown");
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

    /**
     * The {@code WINDOW_CLOSED} listeners (the {@code @Dependent} window beans destroyed by {@code WindowBeans}) run
     * before the dispose returns, so before the CDI container stops. The test application of continuous testing only
     * disposes the windows it opened.
     */
    @Test
    void windowClosedListenersRunBeforeTheContainerStops() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        List<String> closed = new CopyOnWriteArrayList<>();
        AtomicReference<Frame> test = new AtomicReference<>();
        AtomicReference<Frame> dev = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            test.set(frame("test", closed));
            dev.set(frame("dev", closed));
        });
        try {
            DesktopAwtRecorder.disposeWindows(Set.of(test.get()));
            assertEquals(List.of("test"), closed);
            EventQueue.invokeAndWait(() -> assertTrue(dev.get().isDisplayable()));
            DesktopAwtRecorder.disposeWindows(null);
            assertEquals(List.of("test", "dev"), closed);
        } finally {
            EventQueue.invokeAndWait(() -> dev.get().dispose());
        }
    }

    private static Frame frame(String title, List<String> closed) {
        Frame frame = new Frame(title);
        // displayable, not shown
        frame.pack();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closed.add(title);
            }
        });
        return frame;
    }
}
