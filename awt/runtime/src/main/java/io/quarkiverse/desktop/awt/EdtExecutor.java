package io.quarkiverse.desktop.awt;

import java.awt.EventQueue;
import java.util.concurrent.Executor;

import jakarta.enterprise.inject.Typed;
import jakarta.inject.Singleton;

/**
 * An executor that runs the tasks on the event dispatch thread, always later ({@code EventQueue.invokeLater}), even when
 * called on it : the tasks run in the order they are submitted.
 * <p>
 * Inject it to continue on the event dispatch thread from {@code CompletableFuture}, Mutiny or asynchronous CDI events :
 *
 * <pre>
 * CompletableFuture.supplyAsync(repository::load, managedExecutor).thenAcceptAsync(table::setRows, edt);
 * repository.loadUni().emitOn(edt).subscribe().with(table::setRows, errors::show);
 * event.fireAsync(new BooksChanged(), NotificationOptions.ofExecutor(edt));
 * </pre>
 *
 * Its only bean type is {@code EdtExecutor} : {@code @Inject Executor} still gets the Quarkus worker pool. In static
 * code, {@code EventQueue::invokeLater} is the same executor.
 */
@Singleton
@Typed(EdtExecutor.class)
public class EdtExecutor implements Executor {

    @Override
    public void execute(Runnable command) {
        EventQueue.invokeLater(command);
    }
}
