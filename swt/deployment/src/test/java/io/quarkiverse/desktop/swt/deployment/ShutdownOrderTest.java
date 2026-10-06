package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkiverse.desktop.swt.UiThread;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The user interface stops before the {@code ShutdownEvent} observers when the application stops without
 * {@code Quarkus.asyncExit()} (the end of a test here, a signal in production) : the shells are disposed on the user
 * interface thread first, then the tasks for the user interface thread are rejected. Needs a display. Not on macOS, where
 * the {@code Display} needs the first thread of the process.
 */
@DisabledOnOs(OS.MAC)
class ShutdownOrderTest {

    /**
     * The result of the {@code ShutdownEvent} observer : the log records are collected before the application stops.
     */
    static final String RESULT = "ShutdownOrderTest.result";

    @Singleton
    public static class MainWindow {

        final CountDownLatch opened = new CountDownLatch(1);

        final List<String> events = new CopyOnWriteArrayList<>();

        void open(@Observes SwtStartupEvent event) {
            Shell shell = new Shell(event.display());
            shell.setText("ShutdownOrderTest");
            shell.setSize(200, 100);
            shell.addListener(SWT.Dispose, e -> events.add("dispose:" + Thread.currentThread().getName()));
            shell.open();
            opened.countDown();
        }

        void stopped(@Observes ShutdownEvent event) {
            events.add("shutdown-event");
            String rejected;
            try {
                UiThread.run(() -> events.add("task"));
                rejected = "no";
            } catch (RejectedExecutionException e) {
                rejected = "yes";
            }
            System.setProperty(RESULT, "events=" + events + " rejected=" + rejected);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class))
            .overrideRuntimeConfigKey("quarkus.desktop.swt.startup-event.enabled", "true")
            .setAfterAllCustomizer(() -> assertEquals("events=[dispose:quarkus-desktop-swt-ui, shutdown-event]"
                    + " rejected=yes", System.getProperty(RESULT)));

    @Inject
    MainWindow window;

    @Test
    void shellOpened() throws InterruptedException {
        // the order is checked once the application stopped
        assertTrue(window.opened.await(10, TimeUnit.SECONDS));
    }
}
