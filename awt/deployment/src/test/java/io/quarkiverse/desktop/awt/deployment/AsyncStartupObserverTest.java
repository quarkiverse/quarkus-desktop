package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * An asynchronous observer of {@code DesktopStartupEvent} is never notified, and does not make a user interface
 * application : a build warning. Headless.
 */
class AsyncStartupObserverTest {

    @Singleton
    public static class Observer {

        void onDesktopStart(@ObservesAsync DesktopStartupEvent event) {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("DesktopStartupEvent asynchronously"))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), records.toString());
                String message = DesktopAwtTest.message(records.get(0));
                assertTrue(message.contains("onDesktopStart"), message);
            });

    @Test
    void warning() {
        // the log records are checked once the application stopped
    }
}
