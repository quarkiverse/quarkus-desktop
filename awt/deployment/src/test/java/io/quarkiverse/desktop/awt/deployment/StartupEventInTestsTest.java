package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.EventQueue;
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
 * {@code DesktopStartupEvent} is not fired in tests by default, even by {@code DesktopLifecycle.start()}.
 */
class StartupEventInTestsTest {

    @Singleton
    public static class Observer {

        final AtomicInteger count = new AtomicInteger();

        void onDesktopStart(@Observes DesktopStartupEvent event) {
            count.incrementAndGet();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class));

    @Inject
    Observer observer;

    @Inject
    DesktopLifecycle lifecycle;

    @Test
    void notFired() throws Exception {
        lifecycle.start();
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals(0, observer.count.get());
    }
}
