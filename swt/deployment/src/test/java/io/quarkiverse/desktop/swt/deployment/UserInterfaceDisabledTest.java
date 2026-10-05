package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkiverse.desktop.swt.UiThread;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The user interface does not run in tests by default : {@code SwtStartupEvent} is not fired, even by
 * {@code SwtLifecycle.run()}, which returns at once, and the tasks for the user interface thread are rejected. Needs no
 * display.
 */
class UserInterfaceDisabledTest {

    @Singleton
    public static class MainWindow {

        static final AtomicInteger OPENED = new AtomicInteger();

        void open(@Observes SwtStartupEvent event) {
            OPENED.incrementAndGet();
        }
    }

    @ApplicationScoped
    public static class Presenter {

        @RunOnUiThread
        CompletionStage<String> title() {
            return CompletableFuture.completedFuture("title");
        }

        @RunOnUiThread
        void show() {
            MainWindow.OPENED.addAndGet(100);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class, Presenter.class));

    @Inject
    SwtLifecycle lifecycle;

    @Inject
    UiThreadExecutor ui;

    @Inject
    Presenter presenter;

    @Test
    void runReturnsAtOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), lifecycle::run);
        assertEquals(0, MainWindow.OPENED.get());
        assertFalse(UiThread.isUiThread());
        assertThrows(IllegalStateException.class, UiThread::display);
    }

    @Test
    void tasksAreRejected() throws Exception {
        assertThrows(RejectedExecutionException.class, () -> ui.execute(() -> {
        }));
        assertThrows(RejectedExecutionException.class, () -> UiThread.run(() -> {
        }));
        assertThrows(RejectedExecutionException.class, () -> UiThread.call(() -> 1, Duration.ofSeconds(10)));
        // a CompletionStage completes exceptionally, a void method is skipped
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> presenter.title().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, e.getCause());
        presenter.show();
        assertEquals(0, MainWindow.OPENED.get());
    }
}
