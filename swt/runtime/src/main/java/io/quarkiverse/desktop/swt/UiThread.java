package io.quarkiverse.desktop.swt;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.widgets.Display;

import io.quarkiverse.desktop.swt.runtime.SwtUi;

/**
 * The user interface thread, where the SWT widgets are created and used : the thread running
 * {@link SwtLifecycle#run()}, which created the {@code Display} of the application.
 * <p>
 * {@link #run(Runnable)} runs a task inline when called on the user interface thread, while {@link UiThreadExecutor}
 * always queues it. {@link #call(Callable, Duration)} is the only call that waits for the user interface thread. The tasks
 * queued before the user interface starts run once it started, after the {@link SwtStartupEvent} observers. They are
 * rejected when the application stops before : the tasks of {@code call} and of the {@code @RunOnUiThread} methods
 * returning a {@code CompletionStage} then complete with a {@code RejectedExecutionException}, the others are dropped.
 * Once the user interface stopped (before the {@code ShutdownEvent} observers), the calls are rejected.
 */
public final class UiThread {

    private UiThread() {
    }

    /**
     * Whether the current thread is the user interface thread.
     */
    public static boolean isUiThread() {
        return SwtUi.isUiThread();
    }

    /**
     * The {@code Display} of the application.
     *
     * @throws IllegalStateException when the user interface is not running (before {@link SwtLifecycle#run()}, or once
     *         the application stopped)
     */
    public static Display display() {
        return SwtUi.runningDisplay();
    }

    /**
     * Runs a task on the user interface thread : now when called on it, later ({@code Display.asyncExec}) otherwise.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the user interface stopped
     */
    public static void run(Runnable task) {
        Objects.requireNonNull(task);
        if (SwtUi.isUiThread()) {
            task.run();
        } else {
            SwtUi.post(task);
        }
    }

    /**
     * Calls a task on the user interface thread and waits for its result, at most {@code timeout} : for background
     * threads and tests. Called on the user interface thread, calls the task now.
     * <p>
     * Do not call it while holding a lock that the user interface thread may need : a lock of the application, or the
     * lock of ArC creating a bean (from a constructor, a {@code @PostConstruct} method or a producer, when an observer of
     * the bean runs on the user interface thread at the same time). It then waits until the timeout. Nor on the thread
     * that will run the user interface, before it runs : a {@code StartupEvent} observer, a {@code @QuarkusMain} before
     * {@code SwtLifecycle.run()}.
     *
     * @return the result of the task
     * @throws java.util.concurrent.TimeoutException when the task did not complete in time : the task is skipped if it
     *         did not start yet, a task already running keeps running and its result is dropped
     * @throws java.util.concurrent.RejectedExecutionException when the user interface stopped, or stops before the task
     *         ran
     * @throws InterruptedException when the calling thread is interrupted : the task is skipped if it did not start yet
     * @throws Exception the exception of the task
     */
    public static <T> T call(Callable<T> task, Duration timeout) throws Exception {
        Objects.requireNonNull(task);
        if (SwtUi.isUiThread()) {
            return task.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        SwtUi.post(new SwtUi.Rejectable() {

            @Override
            public void run() {
                if (result.isDone()) {
                    // timed out, or the caller was interrupted
                    return;
                }
                try {
                    result.complete(task.call());
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            }

            @Override
            public void reject(RejectedExecutionException cause) {
                // the user interface stopped, or never started, before the task ran
                result.completeExceptionally(cause);
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
