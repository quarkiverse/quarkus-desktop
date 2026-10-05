package io.quarkiverse.desktop.swt;

import java.util.concurrent.Executor;

import jakarta.enterprise.inject.Typed;
import jakarta.inject.Singleton;

import io.quarkiverse.desktop.swt.runtime.SwtUi;

/**
 * An executor that runs the tasks on the user interface thread, always later ({@code Display.asyncExec}), even when
 * called on it : the tasks run in the order they are submitted. The tasks submitted before the user interface starts run
 * once it started, after the {@link SwtStartupEvent} observers ; once it stopped, the tasks are rejected
 * ({@code RejectedExecutionException}).
 * <p>
 * Inject it to continue on the user interface thread from {@code CompletableFuture}, Mutiny or asynchronous CDI events :
 *
 * <pre>
 * CompletableFuture.supplyAsync(repository::load, managedExecutor).thenAcceptAsync(view::setRows, ui);
 * repository.loadUni().emitOn(ui).subscribe().with(view::setRows, errors::show);
 * event.fireAsync(new BooksChanged(), NotificationOptions.ofExecutor(ui));
 * </pre>
 *
 * Its only bean type is {@code UiThreadExecutor} : {@code @Inject Executor} still gets the Quarkus worker pool.
 */
@Singleton
@Typed(UiThreadExecutor.class)
public class UiThreadExecutor implements Executor {

    @Override
    public void execute(Runnable command) {
        SwtUi.post(command);
    }
}
