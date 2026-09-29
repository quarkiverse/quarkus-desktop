package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.desktop.AboutEvent;
import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * A warning for an application observing a {@code java.awt.desktop} event without observing
 * {@code DesktopStartupEvent} : the handlers are only installed for a user interface. Headless.
 */
class DesktopEventsWithoutUserInterfaceTest {

    @Singleton
    public static class Handlers {

        void about(@Observes AboutEvent event) {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Handlers.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("DesktopStartupEvent"))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), records.toString());
                String message = DesktopAwtTest.message(records.get(0));
                assertTrue(message.contains(AboutEvent.class.getName()), message);
            });

    @Test
    void warning() {
        // the log records are checked once the application stopped
    }
}
