package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.GraphicsEnvironment;
import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A headless JVM does not fire {@code DesktopStartupEvent} : a warning in tests (an error, and the application stops, in
 * production).
 */
class HeadlessStartupEventTest {

    @Singleton
    public static class Observer {

        void onDesktopStart(@Observes DesktopStartupEvent event) {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true")
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("headless"))
            .assertLogRecords(records -> assertEquals(GraphicsEnvironment.isHeadless() ? 1 : 0, records.size(),
                    records.toString()));

    @Test
    void warning() {
        // the log records are checked once the application stopped
    }
}
