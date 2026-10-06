package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

/**
 * The shutdown waits once for a blocked user interface thread : {@code SwtUi.stop()} is called twice when the
 * application started (before and after the {@code ShutdownEvent} observers, see {@code DesktopSwtRecorder}), and the
 * second call does not wait again. The user interface runs on the thread of the test : the first thread of the process
 * on macOS, where the tests run with {@code -XstartOnFirstThread}. Needs a display.
 */
class SwtUiStopTest {

    static final long STOP_TIMEOUT_MILLIS = 1_000;

    static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    @Test
    void aSecondStopDoesNotWaitForABlockedUserInterfaceThread() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SwtUi ui = new SwtUi();
        ui.stopTimeoutMillis = STOP_TIMEOUT_MILLIS;
        // a SwtStartupEvent observer blocking the user interface thread until both stops returned
        ui.startupEvent = new Observer<>(event -> {
            blocked.countDown();
            await(release);
        });
        ui.configure(LaunchMode.TEST, false, false, Optional.empty(), Optional.empty());
        long[] stops = { -1, -1 };
        String[] between = { "not run" };
        Thread shutdown = new Thread(() -> {
            try {
                if (await(blocked)) {
                    for (int i = 0; i < stops.length; i++) {
                        long start = System.nanoTime();
                        ui.stop();
                        stops[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                        if (i == 0) {
                            // the ShutdownEvent observers, after a stop that gave up : rejected at once, not queued for
                            // a user interface thread that may never run them
                            try {
                                SwtUi.post(() -> {
                                });
                                between[0] = "queued";
                            } catch (RejectedExecutionException e) {
                                between[0] = "rejected";
                            }
                        }
                    }
                }
            } finally {
                release.countDown();
            }
        }, "shutdown");
        shutdown.start();

        ui.run();

        if (blocked.getCount() > 0) {
            // the Display could not be created
            shutdown.interrupt();
        }
        shutdown.join(TIMEOUT_MILLIS);
        assertEquals(0, blocked.getCount(), "SwtStartupEvent not fired");
        assertTrue(stops[0] >= STOP_TIMEOUT_MILLIS, "the first stop waited " + stops[0] + " ms");
        assertTrue(stops[1] >= 0 && stops[1] < STOP_TIMEOUT_MILLIS, "the second stop waited " + stops[1] + " ms");
        assertEquals("rejected", between[0], "a call between the stops");
        // the user interface thread disposed the Display once released
        assertNull(Display.getCurrent());
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The {@code SwtStartupEvent} of a user interface created without CDI : notifies a single observer.
     */
    static final class Observer<T> implements Event<T> {

        private final Consumer<T> observer;

        Observer(Consumer<T> observer) {
            this.observer = observer;
        }

        @Override
        public void fire(T event) {
            observer.accept(event);
        }

        @Override
        public <U extends T> CompletionStage<U> fireAsync(U event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Event<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
    }
}
