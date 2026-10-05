package io.quarkiverse.desktop.swt.it;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import io.quarkiverse.desktop.swt.QuitRequest;
import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkiverse.desktop.swt.UiThread;

/**
 * The beans of the lifecycle checks.
 */
public final class ItBeans {

    private ItBeans() {
    }

    /**
     * {@code @RunOnUiThread} methods, called from worker threads.
     */
    @ApplicationScoped
    public static class Presenter {

        private volatile String shown;
        private volatile boolean shownOnUiThread;

        @RunOnUiThread
        void show(String text) {
            shownOnUiThread = UiThread.isUiThread();
            shown = text;
        }

        /**
         * Not intercepted : read through the client proxy of the bean.
         */
        String shown() {
            return shown;
        }

        boolean shownOnUiThread() {
            return shownOnUiThread;
        }

        @RunOnUiThread
        CompletionStage<String> describe(int value) {
            return CompletableFuture.completedFuture(value + (UiThread.isUiThread() ? " on the ui thread" : " elsewhere"));
        }

        @RunOnUiThread
        CompletionStage<String> fail() {
            throw new IllegalStateException("failed on purpose");
        }
    }

    /**
     * A dialog controller, destroyed with its shell ({@code WidgetBeans}).
     */
    @Dependent
    public static class DialogController {

        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        DialogController() {
            CREATED.incrementAndGet();
        }

        Shell open(Display display) {
            Shell shell = new Shell(display, SWT.DIALOG_TRIM);
            shell.setText("Dialog");
            shell.setBounds(400, 600, 240, 100);
            shell.setVisible(true);
            return shell;
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    /**
     * The quit requests ({@code Display.close()}), cancelled on demand.
     */
    @Singleton
    public static class QuitRequests {

        final AtomicInteger requests = new AtomicInteger();
        volatile boolean cancel;
        volatile boolean onUiThread;

        void quit(@Observes QuitRequest request) {
            requests.incrementAndGet();
            onUiThread = UiThread.isUiThread();
            if (cancel) {
                request.cancel();
            }
        }
    }
}
