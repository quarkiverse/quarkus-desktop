package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.Edt;
import io.quarkiverse.desktop.awt.EdtExecutor;
import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code @RunOnEdt}, {@code EdtExecutor} and {@code Edt} (the event dispatch thread of a headless JVM works too).
 */
class RunOnEdtTest {

    @ApplicationScoped
    public static class Presenter {

        final CountDownLatch shown = new CountDownLatch(1);
        volatile boolean shownOnEdt;

        boolean awaitShownOnEdt() throws InterruptedException {
            return shown.await(10, TimeUnit.SECONDS) && shownOnEdt;
        }

        @RunOnEdt
        void show() {
            shownOnEdt = EventQueue.isDispatchThread();
            shown.countDown();
        }

        @RunOnEdt
        CompletionStage<Boolean> onEdt() {
            return CompletableFuture.completedFuture(EventQueue.isDispatchThread());
        }

        @RunOnEdt
        CompletableFuture<String> fail() {
            throw new IllegalStateException("failed on the event dispatch thread");
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Presenter.class));

    @Inject
    Presenter presenter;

    @Inject
    EdtExecutor edt;

    @Inject
    Executor executor;

    @Test
    void voidMethodRunsLater() throws Exception {
        presenter.show();
        // through a method : the fields of the client proxy are not the ones of the bean
        assertTrue(presenter.awaitShownOnEdt());
    }

    @Test
    void completionStage() throws Exception {
        assertTrue(presenter.onEdt().toCompletableFuture().get(10, TimeUnit.SECONDS));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> presenter.fail().get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void inlineOnEdt() throws Exception {
        // called on the event dispatch thread : completed when it returns
        assertTrue(Edt.call(() -> presenter.onEdt().toCompletableFuture().getNow(false), Duration.ofSeconds(10)));
        assertTrue(Edt.call(() -> {
            CompletableFuture<String> failed = presenter.fail();
            return failed.isCompletedExceptionally();
        }, Duration.ofSeconds(10)));
    }

    @Test
    void executor() throws Exception {
        // the worker pool of Quarkus, not the event dispatch thread
        assertNotSame(edt, executor);
        assertTrue(CompletableFuture.supplyAsync(EventQueue::isDispatchThread, edt).get(10, TimeUnit.SECONDS));
        // always later, even on the event dispatch thread
        StringBuffer order = new StringBuffer();
        Edt.call(() -> {
            edt.execute(() -> order.append("later"));
            return order.append("now,");
        }, Duration.ofSeconds(10));
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals("now,later", order.toString());
    }

    @Test
    void edtRunAndCall() throws Exception {
        assertFalse(Edt.isEdt());
        assertTrue(Edt.call(Edt::isEdt, Duration.ofSeconds(10)));
        CountDownLatch ran = new CountDownLatch(1);
        Edt.run(ran::countDown);
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        assertThrows(UnsupportedOperationException.class, () -> Edt.call(() -> {
            throw new UnsupportedOperationException();
        }, Duration.ofSeconds(10)));
    }

    @Test
    void edtCallTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThrows(TimeoutException.class, () -> Edt.call(() -> 1, Duration.ofMillis(200)));
        } finally {
            release.countDown();
        }
    }
}
