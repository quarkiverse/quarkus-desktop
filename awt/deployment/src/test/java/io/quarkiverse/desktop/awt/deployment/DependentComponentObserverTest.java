package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Frame;
import java.util.logging.Level;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A warning for a {@code @Dependent} component bean observing {@code DesktopStartupEvent} (destroyed right after the
 * notification), not for a {@code @Singleton} one.
 */
class DependentComponentObserverTest {

    @Dependent
    public static class Editor extends Frame {

        void open(@Observes DesktopStartupEvent event) {
        }
    }

    @Singleton
    public static class MainWindow extends Frame {

        void open(@Observes DesktopStartupEvent event) {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Editor.class, MainWindow.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("DesktopStartupEvent"))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), records.toString());
                String message = DesktopAwtTest.message(records.get(0));
                assertTrue(message.contains(Editor.class.getName()), message);
            });

    @Test
    void warning() {
        // the log records are checked once the application stopped
    }
}
