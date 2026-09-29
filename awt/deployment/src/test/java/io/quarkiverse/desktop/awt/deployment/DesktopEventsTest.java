package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.desktop.AboutEvent;
import java.awt.desktop.AppForegroundEvent;
import java.awt.desktop.OpenFilesEvent;
import java.awt.desktop.QuitEvent;
import java.util.List;
import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkiverse.desktop.awt.QuitRequest;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Warnings for the observers of the {@code java.awt.desktop} events that the extension does not fire, and for the
 * asynchronous observers. Headless.
 */
class DesktopEventsTest {

    @Singleton
    public static class Handlers {

        void open(@Observes DesktopStartupEvent event) {
        }

        void about(@Observes AboutEvent event) {
        }

        void openFiles(@ObservesAsync OpenFilesEvent event) {
        }

        void quit(@Observes QuitRequest request) {
        }

        void quitEvent(@Observes QuitEvent event) {
        }

        void foreground(@Observes AppForegroundEvent event) {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Handlers.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("java.awt.desktop."))
            .assertLogRecords(records -> {
                List<String> messages = records.stream().map(DesktopAwtTest::message).sorted().toList();
                assertEquals(3, messages.size(), messages.toString());
                assertTrue(messages.get(0).contains("foreground()")
                        && messages.get(0).contains("Desktop.addAppEventListener"), messages.get(0));
                assertTrue(messages.get(1).contains("openFiles()") && messages.get(1).contains("use @Observes"),
                        messages.get(1));
                assertTrue(messages.get(2).contains("quitEvent()") && messages.get(2).contains("QuitRequest"),
                        messages.get(2));
            });

    @Test
    void warnings() {
        // the log records are checked once the application stopped
    }
}
