package io.quarkiverse.desktop.awt;

import java.awt.EventQueue;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The event dispatch thread, where AWT and Swing components are created and used.
 * <p>
 * {@link #run(Runnable)} runs a task inline when called on the event dispatch thread, while {@link EdtExecutor} always
 * queues it. {@link #call(Callable, Duration)} is the only call that waits for the event dispatch thread.
 */
public final class Edt {

    private Edt() {
    }

    /**
     * Whether the current thread is the event dispatch thread.
     */
    public static boolean isEdt() {
        return EventQueue.isDispatchThread();
    }

    /**
     * Runs a task on the event dispatch thread : now when called on it, later ({@code EventQueue.invokeLater})
     * otherwise.
     */
    public static void run(Runnable task) {
        Objects.requireNonNull(task);
        if (EventQueue.isDispatchThread()) {
            task.run();
        } else {
            EventQueue.invokeLater(task);
        }
    }

    /**
     * Calls a task on the event dispatch thread and waits for its result, at most {@code timeout} : for background threads
     * and tests. Called on the event dispatch thread, calls the task now.
     * <p>
     * Do not call it while holding a lock that the event dispatch thread may need : the AWT tree lock, a lock of the
     * application, or the lock of ArC creating a bean (from a constructor, a {@code @PostConstruct} method or a producer,
     * when an observer of the bean runs on the event dispatch thread at the same time). Nor from a shutdown hook or a
     * {@code ShutdownEvent} observer, where the event dispatch thread may be blocked. It then waits until the timeout.
     *
     * @return the result of the task
     * @throws TimeoutException when the task did not complete in time : the task is skipped if it did not start yet, a
     *         task already running keeps running and its result is dropped
     * @throws InterruptedException when the calling thread is interrupted : the task is skipped if it did not start
     *         yet
     * @throws Exception the exception of the task
     */
    public static <T> T call(Callable<T> task, Duration timeout) throws Exception {
        Objects.requireNonNull(task);
        if (EventQueue.isDispatchThread()) {
            return task.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        EventQueue.invokeLater(() -> {
            if (result.isDone()) {
                // timed out, or the caller was interrupted
                return;
            }
            try {
                result.complete(task.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        } finally {
            // timed out, or interrupted : skip the task if it did not start yet
            result.cancel(false);
        }
    }
}
