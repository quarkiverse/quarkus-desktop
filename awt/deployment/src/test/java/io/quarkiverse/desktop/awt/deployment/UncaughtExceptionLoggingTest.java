package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * An application with a user interface logs the uncaught exceptions of the event dispatch thread in the category
 * {@code io.quarkiverse.desktop.awt.edt}, and restores the previous default handler when it stops. Needs a display.
 */
class UncaughtExceptionLoggingTest {

    static final String CATEGORY = "io.quarkiverse.desktop.awt.edt";

    static volatile Thread.UncaughtExceptionHandler before;

    @Singleton
    public static class Observer {

        final CountDownLatch started = new CountDownLatch(1);

        void onDesktopStart(@Observes DesktopStartupEvent event) {
            started.countDown();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true")
            .setBeforeAllCustomizer(() -> before = Thread.getDefaultUncaughtExceptionHandler())
            .setAfterAllCustomizer(() -> assertSame(before, Thread.getDefaultUncaughtExceptionHandler()))
            .setLogRecordPredicate(r -> CATEGORY.equals(r.getLoggerName()))
            .assertLogRecords(records -> {
                if (GraphicsEnvironment.isHeadless()) {
                    assertEquals(0, records.size(), records.toString());
                    return;
                }
                assertEquals(1, records.size(), records.toString());
                String message = DesktopAwtTest.message(records.get(0));
                assertTrue(message.contains("AWT-EventQueue"), message);
                assertEquals("listener failure", records.get(0).getThrown().getMessage());
            });

    @Inject
    Observer observer;

    @Test
    void logged() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        assertTrue(observer.started.await(10, TimeUnit.SECONDS));
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        assertNotNull(handler);
        if (before == null) {
            assertEquals("io.quarkiverse.desktop.awt.runtime.UncaughtExceptionLogger", handler.getClass().getName());
        }
        EventQueue.invokeLater(() -> {
            throw new IllegalStateException("listener failure");
        });
        // the event dispatch thread keeps running
        EventQueue.invokeAndWait(() -> {
        });
    }
}
