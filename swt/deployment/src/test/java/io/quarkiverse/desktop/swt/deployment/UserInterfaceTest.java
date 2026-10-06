package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkiverse.desktop.swt.UiThread;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The user interface in tests, when enabled : {@code QuarkusExtensionTest} starts the application without a main, the
 * extension runs the user interface on a thread of its own, where {@code SwtStartupEvent} is fired, and where
 * {@code UiThread}, {@code UiThreadExecutor} and {@code @RunOnUiThread} run their tasks. Needs a display. Not on macOS,
 * where the {@code Display} needs the first thread of the process.
 */
@DisabledOnOs(OS.MAC)
class UserInterfaceTest {

    static final String UI_THREAD = "quarkus-desktop-swt-ui";

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Singleton
    public static class MainWindow {

        static final CountDownLatch OPENED = new CountDownLatch(1);
        static volatile String thread;
        static volatile boolean uiThread;
        static volatile boolean currentDisplay;
        static volatile boolean shellOpened;

        void open(@Observes SwtStartupEvent event) {
            thread = Thread.currentThread().getName();
            uiThread = UiThread.isUiThread();
            currentDisplay = event.display() == Display.getCurrent() && UiThread.display() == event.display();
            Shell shell = new Shell(event.display());
            shell.setText("UserInterfaceTest");
            shell.setSize(200, 100);
            shell.open();
            shellOpened = shell.isVisible();
            shell.dispose();
            OPENED.countDown();
        }
    }

    @ApplicationScoped
    public static class Presenter {

        final CountDownLatch shown = new CountDownLatch(1);
        volatile String shownIn;

        /**
         * Through a method : the fields of the client proxy are not the ones of the bean.
         */
        String awaitShown() throws InterruptedException {
            return shown.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) ? shownIn : null;
        }

        @RunOnUiThread
        void show() {
            shownIn = Thread.currentThread().getName();
            shown.countDown();
        }

        @RunOnUiThread
        CompletionStage<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }

        @RunOnUiThread
        CompletableFuture<String> fail() {
            throw new IllegalStateException("failed on the user interface thread");
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class, Presenter.class))
            .overrideRuntimeConfigKey("quarkus.desktop.swt.startup-event.enabled", "true");

    @Inject
    Presenter presenter;

    @Inject
    UiThreadExecutor ui;

    @Test
    void startupEventOnTheUserInterfaceThread() throws Exception {
        assertTrue(MainWindow.OPENED.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "SwtStartupEvent not fired");
        assertEquals(UI_THREAD, MainWindow.thread);
        assertTrue(MainWindow.uiThread);
        assertTrue(MainWindow.currentDisplay);
        assertTrue(MainWindow.shellOpened);
    }

    @Test
    void uiThread() throws Exception {
        awaitUserInterface();
        assertFalse(UiThread.isUiThread());
        assertEquals(UI_THREAD, UiThread.call(() -> Thread.currentThread().getName(), TIMEOUT));
        assertTrue(UiThread.call(UiThread::isUiThread, TIMEOUT));
        assertTrue(UiThread.call(() -> UiThread.display() == Display.getCurrent(), TIMEOUT));
        CountDownLatch ran = new CountDownLatch(1);
        UiThread.run(ran::countDown);
        assertTrue(ran.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        // the exception of the task
        assertThrows(UnsupportedOperationException.class, () -> UiThread.call(() -> {
            throw new UnsupportedOperationException();
        }, TIMEOUT));
    }

    @Test
    void executor() throws Exception {
        awaitUserInterface();
        assertEquals(UI_THREAD, CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), ui)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        // always later, even on the user interface thread
        StringBuffer order = new StringBuffer();
        UiThread.call(() -> {
            ui.execute(() -> order.append("later"));
            return order.append("now,");
        }, TIMEOUT);
        UiThread.call(() -> null, TIMEOUT);
        assertEquals("now,later", order.toString());
    }

    @Test
    void runOnUiThread() throws Exception {
        awaitUserInterface();
        assertEquals(UI_THREAD, presenter.threadName().toCompletableFuture().get(TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS));
        presenter.show();
        assertEquals(UI_THREAD, presenter.awaitShown());
        CompletableFuture<String> failed = presenter.fail();
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> failed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        // called on the user interface thread : runs now, completed when it returns
        assertTrue(UiThread.call(() -> presenter.threadName().toCompletableFuture().isDone(), TIMEOUT));
        assertTrue(UiThread.call(() -> presenter.fail().isCompletedExceptionally(), TIMEOUT));
    }

    @Test
    void callTimeout() throws Exception {
        awaitUserInterface();
        CountDownLatch release = new CountDownLatch(1);
        UiThread.run(() -> {
            try {
                release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean ran = new AtomicBoolean();
        try {
            Duration timeout = Duration.ofMillis(200);
            assertThrows(TimeoutException.class, () -> UiThread.call(() -> ran.getAndSet(true), timeout));
        } finally {
            release.countDown();
        }
        // the task that timed out before it started is skipped
        UiThread.call(() -> null, TIMEOUT);
        assertFalse(ran.get());
    }

    private static void awaitUserInterface() throws InterruptedException {
        assertTrue(MainWindow.OPENED.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "SwtStartupEvent not fired");
    }
}
