package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopLifecycle;
import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code DesktopStartupEvent} is fired once on the event dispatch thread, after the {@code StartupEvent} observers, when
 * enabled in tests. Needs a display.
 */
class DesktopStartupEventTest {

    @Singleton
    public static class Observer {

        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean onEventDispatchThread;

        void onStart(@Observes StartupEvent event) {
            events.add("StartupEvent");
        }

        void onDesktopStart(@Observes DesktopStartupEvent event) {
            onEventDispatchThread = EventQueue.isDispatchThread();
            events.add("DesktopStartupEvent");
            started.countDown();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true");

    @Inject
    Observer observer;

    @Inject
    DesktopLifecycle lifecycle;

    @Test
    void firedOnEventDispatchThread() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        assertTrue(observer.started.await(10, TimeUnit.SECONDS));
        assertTrue(observer.onEventDispatchThread);
        // only once
        lifecycle.start();
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals(List.of("StartupEvent", "DesktopStartupEvent"), observer.events);
    }
}
