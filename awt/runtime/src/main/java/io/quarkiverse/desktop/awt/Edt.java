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
     * Do not call it while holding a lock that the event dispatch thread may need (the AWT tree lock, a lock of the
     * application), nor from a shutdown hook or a {@code ShutdownEvent} observer, where the event dispatch thread may be
     * blocked : it then waits until the timeout.
     *
     * @return the result of the task
     * @throws TimeoutException when the task did not complete in time (it may still run later)
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
                // timed out
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
        } catch (TimeoutException e) {
            result.cancel(false);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
