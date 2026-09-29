package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.GraphicsEnvironment;
import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopLifecycle;
import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * In manual mode, the application decides whether it starts its user interface (a batch mode on a server) : a headless
 * JVM is only reported by {@code DesktopLifecycle.start()}, once (an error, and the application stops, in production).
 */
class HeadlessManualStartTest {

    @Singleton
    public static class Observer {

        void onDesktopStart(@Observes DesktopStartupEvent event) {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.mode", "manual")
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("headless"))
            .assertLogRecords(records -> assertEquals(GraphicsEnvironment.isHeadless() ? 1 : 0, records.size(),
                    records.toString()));

    @Inject
    DesktopLifecycle lifecycle;

    @Test
    void warningOnStart() {
        lifecycle.start();
        lifecycle.start();
    }
}
