package io.quarkiverse.desktop.swt.it;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import io.quarkiverse.desktop.swt.UiThread;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkiverse.desktop.swt.WidgetBeans;

/**
 * Checks of the application model of the extension : the startup event, the user interface thread, the executor,
 * {@code @RunOnUiThread}, {@code UiThread}, the widget beans and the quit requests. Runs on the user interface thread,
 * and pumps the event loop while it waits for other threads.
 * <p>
 * Prints one {@code RESULT <check> OK|FAILED} line per check and a {@code SUMMARY} line.
 */
@Singleton
public class LifecycleChecks {

    private static final long TIMEOUT_MILLIS = 20_000;

    @Inject
    UiThreadExecutor executor;

    @Inject
    ItBeans.Presenter presenter;

    @Inject
    Instance<ItBeans.DialogController> dialogs;

    @Inject
    ItBeans.QuitRequests quitRequests;

    /**
     * The worker threads of the checks : not the common pool, whose thread factory Quarkus loads by name (a lookup
     * missing from the metadata of a native executable built with exact reachability metadata).
     */
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "lifecycle-checks-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final PrintStream out = System.out;
    private final List<String> failures = new ArrayList<>();
    private int ok;

    /**
     * Runs the checks on the user interface thread.
     *
     * @return the exit status : 0 when every check passed, 1 otherwise
     */
    int run(Display display, ItUserInterface userInterface) {
        check("startup-event", () -> {
            require(userInterface.startupEventThread() == userInterface.mainThread(),
                    "SwtStartupEvent observed in " + userInterface.startupEventThread() + ", not the main thread");
            require(userInterface.startupEventOnUiThread(), "SwtStartupEvent observed outside the user interface thread");
            require(UiThread.display() == display, "UiThread.display() " + UiThread.display());
            return "thread=" + userInterface.startupEventThread().getName();
        });
        check("queued-before-start", () -> {
            List<String> events = List.copyOf(userInterface.events);
            require(events.indexOf("swt-startup-event") == 0 && events.indexOf("queued-by-startup-event") == 1,
                    "events " + events);
            return "events=" + events;
        });
        check("executor-order", () -> {
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean everyOnUiThread = new AtomicBoolean(true);
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 50; i++) {
                    int index = i;
                    executor.execute(() -> {
                        everyOnUiThread.compareAndSet(true, UiThread.isUiThread());
                        order.add(index);
                    });
                }
            }, workers);
            pump(display, () -> order.size() == 50);
            for (int i = 0; i < 50; i++) {
                require(order.get(i) == i, "order " + order);
            }
            require(everyOnUiThread.get(), "a task ran outside the user interface thread");
            return "tasks=" + order.size();
        });
        check("executor-later", () -> {
            AtomicBoolean ran = new AtomicBoolean();
            executor.execute(() -> ran.set(true));
            require(!ran.get(), "the executor ran the task inline");
            pump(display, ran::get);
            AtomicBoolean inline = new AtomicBoolean();
            UiThread.run(() -> inline.set(true));
            require(inline.get(), "UiThread.run did not run the task inline on the user interface thread");
            return "later=true inline=true";
        });
        check("run-on-ui-thread", () -> {
            CompletableFuture<String> described = CompletableFuture.supplyAsync(() -> {
                presenter.show("from a worker");
                return presenter.describe(21).toCompletableFuture();
            }, workers).thenCompose(stage -> stage);
            pump(display, () -> described.isDone() && presenter.shown() != null);
            require(presenter.shownOnUiThread(), "show() ran outside the user interface thread");
            require(described.join().equals("21 on the ui thread"), "describe() " + described.join());
            CompletableFuture<String> failed = CompletableFuture
                    .supplyAsync(() -> presenter.fail().toCompletableFuture(), workers)
                    .thenCompose(stage -> stage);
            pump(display, failed::isDone);
            try {
                failed.join();
                throw new IllegalStateException("fail() completed normally");
            } catch (CompletionException e) {
                require(e.getCause() instanceof IllegalStateException, "fail() failed with " + e.getCause());
            }
            return "shown=" + presenter.shown() + " described=" + described.join();
        });
        check("ui-thread-call", () -> {
            CompletableFuture<Object> called = CompletableFuture.supplyAsync(() -> {
                try {
                    boolean onUiThread = UiThread.call(UiThread::isUiThread, Duration.ofSeconds(10));
                    try {
                        UiThread.call(() -> {
                            throw new java.io.IOException("thrown on purpose");
                        }, Duration.ofSeconds(10));
                        return "no exception";
                    } catch (java.io.IOException e) {
                        return onUiThread + " " + e.getMessage();
                    }
                } catch (Exception e) {
                    return e.toString();
                }
            }, workers);
            pump(display, called::isDone);
            require(called.join().equals("true thrown on purpose"), "UiThread.call " + called.join());
            return called.join();
        });
        check("widget-beans", () -> {
            int created = ItBeans.DialogController.CREATED.get();
            int destroyed = ItBeans.DialogController.DESTROYED.get();
            Shell shell = WidgetBeans.create(dialogs, dialog -> dialog.open(display));
            require(ItBeans.DialogController.CREATED.get() == created + 1, "the controller was not created");
            require(ItBeans.DialogController.DESTROYED.get() == destroyed, "the controller was destroyed too early");
            shell.dispose();
            require(ItBeans.DialogController.DESTROYED.get() == destroyed + 1,
                    "the controller was not destroyed with its shell");
            CompletableFuture<String> elsewhere = CompletableFuture.supplyAsync(() -> {
                try {
                    WidgetBeans.create(dialogs, dialog -> dialog.open(display));
                    return "created outside the user interface thread";
                } catch (IllegalStateException e) {
                    return "rejected";
                }
            }, workers);
            pump(display, elsewhere::isDone);
            require(elsewhere.join().equals("rejected"), elsewhere.join());
            return "destroyed=true elsewhere=" + elsewhere.join();
        });
        check("quit-request", () -> {
            quitRequests.cancel = true;
            display.close();
            require(quitRequests.requests.get() == 1, "quit requests " + quitRequests.requests.get());
            require(quitRequests.onUiThread, "the quit request was observed outside the user interface thread");
            require(!display.isDisposed(), "the Display is disposed after a cancelled quit request");
            // the application keeps running : the event loop still runs the tasks
            AtomicBoolean ran = new AtomicBoolean();
            executor.execute(() -> ran.set(true));
            pump(display, ran::get);
            return "cancelled=true running=true";
        });
        out.println("SUMMARY ok=" + ok + " failed=" + failures.size() + (failures.isEmpty() ? "" : " " + failures));
        return failures.isEmpty() ? 0 : 1;
    }

    private void check(String name, Callable<Object> check) {
        try {
            Object value = check.call();
            ok++;
            out.println("RESULT " + name + " OK " + value);
        } catch (Throwable e) {
            failures.add(name);
            out.println("RESULT " + name + " FAILED " + e);
            e.printStackTrace(out);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Runs the event loop until the condition holds, at most {@link #TIMEOUT_MILLIS}.
     */
    static void pump(Display display, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        // wakes the event loop up regularly : the other threads do not wake it
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!display.isDisposed()) {
                display.timerExec(10, tick[0]);
            }
        };
        display.timerExec(10, tick[0]);
        try {
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new ExecutionException("timed out", null);
                }
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        } finally {
            display.timerExec(-1, tick[0]);
        }
    }
}
