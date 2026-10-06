package io.quarkiverse.desktop.showcase.swt.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import io.quarkiverse.desktop.swt.UiThread;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkus.arc.Arc;

/**
 * Asynchronous waits for the SWT pages and the snapshot runner : the SWT counterpart of {@code core/Edt}.
 * <p>
 * Every stage returned here completes on the user interface thread (in a {@code Display.asyncExec} or
 * {@code Display.timerExec} runnable), so that the {@code thenApply}/{@code thenCompose}/{@code thenAccept}
 * continuations (non async variants) run on the user interface thread too, where the widgets can be used. Never use
 * the {@code *Async} variants without an executor : they run on the common {@code ForkJoinPool}, which fails in native
 * executables built with exact reachability metadata (and is the wrong thread for the widgets anyway) ; use
 * {@link #ui()} as their executor.
 * <p>
 * The page methods run on the user interface thread : the waits never block it, they let the event loop run.
 */
public final class UiStages {

    private static final int POLL_MILLIS = 20;

    /**
     * The running threads of {@link #background}.
     */
    private static final Set<Thread> BACKGROUND = ConcurrentHashMap.newKeySet();

    private UiStages() {
    }

    /**
     * The executor of the user interface thread ({@code io.quarkiverse.desktop.swt.UiThreadExecutor} :
     * {@code Display.asyncExec}, always later, in order), for the {@code *Async} variants of the stages.
     */
    public static Executor ui() {
        return Arc.container().instance(UiThreadExecutor.class).get();
    }

    /**
     * The {@code Display} of the application.
     */
    public static Display display() {
        return UiThread.display();
    }

    /**
     * Completes (on the user interface thread) after {@code count} chained {@code asyncExec} rounds : the events, the
     * layouts and the paints pending before are processed first (SWT runs an {@code asyncExec} runnable when no event
     * of the operating system is pending).
     */
    public static CompletionStage<Void> rounds(int count) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        round(Math.max(1, count), done);
        return done;
    }

    private static void round(int remaining, CompletableFuture<Void> done) {
        ui().execute(() -> {
            if (remaining <= 1) {
                done.complete(null);
            } else {
                round(remaining - 1, done);
            }
        });
    }

    /**
     * Completes (on the user interface thread) after {@code millis}, the event loop running meanwhile
     * ({@code Display.timerExec}).
     */
    public static CompletionStage<Void> delay(long millis) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        onUiThread(() -> display().timerExec(clamp(millis), () -> done.complete(null)));
        return done;
    }

    /**
     * Completes (on the user interface thread) when {@code stage} completes, whatever thread completes it, or
     * exceptionally with a {@link TimeoutException} ({@code "Timeout waiting for <what>"}) after {@code millis}.
     */
    public static <T> CompletionStage<T> timeout(CompletionStage<T> stage, long millis, String what) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable timer = () -> result.completeExceptionally(new TimeoutException("Timeout waiting for " + what));
        onUiThread(() -> {
            if (!result.isDone()) {
                display().timerExec(clamp(millis), timer);
            }
        });
        stage.whenComplete((value, error) -> ui().execute(() -> {
            display().timerExec(-1, timer);
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
        }));
        return result;
    }

    /**
     * Completes (on the user interface thread) once {@code condition} is true, evaluated on the user interface thread
     * every 20 ms, or exceptionally with a {@link TimeoutException} after {@code timeoutMillis}.
     */
    public static CompletionStage<Void> until(BooleanSupplier condition, long timeoutMillis, String what) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ui().execute(() -> poll(condition, deadline, what, done));
        return done;
    }

    private static void poll(BooleanSupplier condition, long deadline, String what, CompletableFuture<Void> done) {
        try {
            if (condition.getAsBoolean()) {
                done.complete(null);
            } else if (System.nanoTime() - deadline > 0) {
                done.completeExceptionally(new TimeoutException("Timeout waiting for " + what));
            } else {
                display().timerExec(POLL_MILLIS, () -> poll(condition, deadline, what, done));
            }
        } catch (Throwable t) {
            done.completeExceptionally(t);
        }
    }

    /**
     * Completes (on the user interface thread) once {@code value}, evaluated on the user interface thread every 20 ms,
     * kept an equal value for {@code quietMillis}, or exceptionally with a {@link TimeoutException} after
     * {@code timeoutMillis} : e.g. the bounds of a shell that the window manager configures in several steps.
     */
    public static CompletionStage<Void> untilStable(Supplier<?> value, long quietMillis, long timeoutMillis,
            String what) {
        Object[] last = { new Object() }; // equal to no value : the first evaluation starts the quiet period
        long[] since = { 0 };
        long quiet = TimeUnit.MILLISECONDS.toNanos(quietMillis);
        return until(() -> {
            Object current = value.get();
            long now = System.nanoTime();
            if (!Objects.equals(current, last[0])) {
                last[0] = current;
                since[0] = now;
                return false;
            }
            return now - since[0] > quiet;
        }, timeoutMillis, what);
    }

    /**
     * Completes (on the user interface thread) once {@code control} renders the same pixels 3 times in a row (rendered
     * with {@link SwtSnapshots#render} every 50 ms : {@link SwtSnapshots#renderStable}), or after
     * {@code timeoutMillis} (never exceptionally : the timeout or a failed render only ends the wait).
     */
    public static CompletionStage<Void> stable(Control control, long timeoutMillis) {
        return SwtSnapshots.renderStable(control, SwtSnapshots.STABLE_RENDERS, timeoutMillis)
                .handle((image, error) -> null);
    }

    /**
     * Runs {@code action} on a background thread (never block the user interface thread : file I/O, a lookup, waiting
     * for something) ; the returned stage completes on the user interface thread, through
     * {@code UiThreadExecutor}.
     */
    public static <T> CompletionStage<T> background(Callable<T> action) {
        Executor ui = ui();
        CompletableFuture<T> done = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                T value = action.call();
                ui.execute(() -> done.complete(value));
            } catch (Throwable t) {
                ui.execute(() -> done.completeExceptionally(t));
            } finally {
                BACKGROUND.remove(Thread.currentThread());
            }
        }, "showcase-background");
        thread.setDaemon(true);
        BACKGROUND.add(thread);
        thread.start();
        return done;
    }

    /**
     * Completes on the user interface thread once the threads of {@link #background} started so far have ended, with
     * the names of those still running, if any (without blocking the user interface thread : the threads may be waiting
     * for it). A thread still running after {@code graceMillis} is interrupted, and the wait gives up after the same
     * time again. The snapshot runner waits for them between two pages.
     */
    public static CompletionStage<List<String>> awaitBackground(long graceMillis) {
        List<Thread> threads = List.copyOf(BACKGROUND);
        if (threads.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        Executor ui = ui();
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        Thread joiner = new Thread(() -> {
            List<String> left = new ArrayList<>();
            try {
                if (!join(threads, graceMillis)) {
                    threads.forEach(Thread::interrupt);
                    join(threads, graceMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    String stack = Arrays.toString(thread.getStackTrace());
                    left.add(thread.getName() + " " + stack.substring(0, Math.min(stack.length(), 300)));
                }
            }
            ui.execute(() -> done.complete(left));
        }, "showcase-background-join");
        joiner.setDaemon(true);
        joiner.start();
        return done;
    }

    private static boolean join(List<Thread> threads, long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        for (Thread thread : threads) {
            long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (left > 0) {
                thread.join(left);
            }
        }
        return threads.stream().noneMatch(Thread::isAlive);
    }

    /**
     * Runs {@code task} now on the user interface thread, later from another thread.
     */
    private static void onUiThread(Runnable task) {
        if (UiThread.isUiThread()) {
            task.run();
        } else {
            ui().execute(task);
        }
    }

    private static int clamp(long millis) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, millis));
    }
}
