package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkiverse.desktop.swt.UiThread;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The tasks queued for a user interface that never runs (enabled, but nothing calls {@code SwtLifecycle.run()}) are
 * rejected when the application stops : a {@code @RunOnUiThread} stage completes exceptionally, a waiting
 * {@code UiThread.call} throws, instead of waiting for ever (or until its timeout). Needs no display.
 */
class PendingTasksRejectedTest {

    /**
     * The result of the {@code ShutdownEvent} observer : the log records are collected before the application stops.
     */
    static final String RESULT = "PendingTasksRejectedTest.result";

    @ApplicationScoped
    public static class Presenter {

        @RunOnUiThread
        CompletionStage<String> name() {
            return CompletableFuture.completedFuture("ran");
        }
    }

    @Singleton
    public static class Checks {

        @Inject
        Presenter presenter;

        volatile CompletableFuture<String> queued;

        volatile String called = "pending";

        volatile Thread caller;

        void started(@Observes StartupEvent event) {
            queued = presenter.name().toCompletableFuture();
            caller = new Thread(() -> {
                try {
                    called = UiThread.call(() -> "ran", Duration.ofSeconds(60));
                } catch (RejectedExecutionException e) {
                    called = "rejected";
                } catch (Exception e) {
                    called = e.toString();
                }
            }, "caller");
            caller.start();
        }

        boolean queued() {
            return queued != null && !queued.isDone();
        }

        void stopped(@Observes ShutdownEvent event) throws InterruptedException {
            caller.join(10_000);
            String stage = queued.handle((value, error) -> error == null ? "completed:" + value
                    : error.getClass().getSimpleName() + ":" + error.getMessage()).getNow("pending");
            System.setProperty(RESULT, "stage=" + stage + " call=" + called);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Presenter.class, Checks.class))
            .overrideRuntimeConfigKey("quarkus.desktop.swt.startup-event.enabled", "true")
            .setAfterAllCustomizer(() -> assertEquals("stage=RejectedExecutionException:The application stopped before"
                    + " the user interface ran call=rejected", System.getProperty(RESULT)));

    @Inject
    Checks checks;

    @Test
    void queuedWhileTheApplicationRuns() {
        // rejected once the application stopped
        assertTrue(checks.queued(), "the task should be queued");
    }
}
