package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * No build warning for the {@code @QuarkusMain} of a user interface application that calls {@code SwtLifecycle.run()},
 * directly or through the {@code QuarkusApplication} that it runs ({@code Quarkus.run(Application.class)}), even when
 * another {@code @QuarkusMain} does not ({@link QuarkusMainWithoutRunTest}). The mains do not run in this test : it needs
 * no display.
 */
class QuarkusMainWithRunTest {

    @Singleton
    public static class MainWindow {

        void open(@Observes SwtStartupEvent event) {
        }
    }

    @QuarkusMain
    public static class Main {

        public static void main(String... args) {
            Quarkus.run(Application.class, args);
        }
    }

    public static class Application implements QuarkusApplication {

        @Inject
        SwtLifecycle lifecycle;

        @Override
        public int run(String... args) {
            lifecycle.run();
            return 0;
        }
    }

    @QuarkusMain(name = "headless")
    public static class HeadlessMain implements QuarkusApplication {

        @Override
        public int run(String... args) {
            Quarkus.waitForExit();
            return 0;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class, Main.class, Application.class,
                    HeadlessMain.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopSwtTest.message(r).contains("SwtLifecycle.run()"))
            .assertLogRecords(records -> assertTrue(records.isEmpty(), records.toString()));

    @Test
    void noWarning() {
        // the log records are checked once the application stopped
    }
}
