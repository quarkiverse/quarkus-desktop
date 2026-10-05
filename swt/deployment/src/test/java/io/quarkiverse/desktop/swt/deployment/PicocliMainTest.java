package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.test.QuarkusExtensionTest;
import picocli.CommandLine.Command;

/**
 * A user interface application whose main comes from another extension (Picocli) : its main replaces the one of the
 * extension, which is overridable, and the command runs the user interface. The main does not run in this test : it
 * needs no display.
 */
class PicocliMainTest {

    @Singleton
    public static class MainWindow {

        void open(@Observes SwtStartupEvent event) {
        }
    }

    @TopCommand
    @Command(name = "app")
    public static class AppCommand implements Runnable {

        @Inject
        SwtLifecycle lifecycle;

        @Override
        public void run() {
            lifecycle.run();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class, AppCommand.class));

    @Inject
    SwtLifecycle lifecycle;

    @Test
    void builds() {
        assertNotNull(lifecycle);
    }
}
