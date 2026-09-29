package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * An {@code Error} thrown by a {@code DesktopStartupEvent} observer (a native executable missing some metadata fails
 * with a {@code LinkageError}) is logged as the extension's error, not left to the uncaught exception handler (in
 * production, the application then stops with exit code 1 instead of waiting for ever without a window). Needs a
 * display.
 */
class StartupObserverErrorTest {

    @Singleton
    public static class Observer {

        void onDesktopStart(@Observes DesktopStartupEvent event) {
            throw new NoClassDefFoundError("MainWindow");
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true")
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.SEVERE))
            .assertLogRecords(records -> {
                if (GraphicsEnvironment.isHeadless()) {
                    return;
                }
                assertEquals(1, records.size(), records.toString());
                assertTrue(DesktopAwtTest.message(records.get(0)).contains("A DesktopStartupEvent observer failed"),
                        records.toString());
                assertTrue(records.get(0).getThrown() instanceof NoClassDefFoundError, records.toString());
            });

    @Test
    void logged() throws Exception {
        EventQueue.invokeAndWait(() -> {
        });
    }
}
