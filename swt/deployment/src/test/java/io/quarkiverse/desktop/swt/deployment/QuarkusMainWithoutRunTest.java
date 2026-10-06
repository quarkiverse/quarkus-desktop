package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A build warning for the {@code @QuarkusMain} of a user interface application that does not call
 * {@code SwtLifecycle.run()} : the user interface would never start ({@link QuarkusMainWithRunTest} : none when it calls
 * it). The mains do not run in this test : it needs no display.
 */
class QuarkusMainWithoutRunTest {

    @Singleton
    public static class MainWindow {

        void open(@Observes SwtStartupEvent event) {
        }
    }

    @QuarkusMain
    public static class Main implements QuarkusApplication {

        @Override
        public int run(String... args) {
            Quarkus.waitForExit();
            return 0;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class, Main.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopSwtTest.message(r).contains("SwtLifecycle.run()"))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), records.toString());
                String message = DesktopSwtTest.message(records.get(0));
                assertTrue(message.contains("No @QuarkusMain calls SwtLifecycle.run() (" + Main.class.getName() + ")"),
                        message);
            });

    @Test
    void warning() {
        // the log records are checked once the application stopped
    }
}
