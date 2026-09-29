package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopLifecycle;
import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code quarkus.desktop.awt.startup-event.mode=manual}, {@code DesktopStartupEvent} is fired when the application
 * calls {@code DesktopLifecycle.start()}, once. Needs a display.
 */
class ManualStartupEventTest {

    @Singleton
    public static class Observer {

        final AtomicInteger count = new AtomicInteger();

        void onDesktopStart(@Observes DesktopStartupEvent event) {
            count.incrementAndGet();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.mode", "manual");

    @Inject
    Observer observer;

    @Inject
    DesktopLifecycle lifecycle;

    @Test
    void firedByStart() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals(0, observer.count.get());
        lifecycle.start();
        lifecycle.start();
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals(1, observer.count.get());
    }
}
