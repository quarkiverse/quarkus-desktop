package io.quarkiverse.desktop.swt.it;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkiverse.desktop.swt.UiThread;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * The user interface of the integration tests : runs the checks of the scenario once the user interface started, then
 * stops the application with their exit status.
 */
@Singleton
public class ItUserInterface {

    @Inject
    UiThreadExecutor uiThread;

    /**
     * What happened, in order : the startup and shutdown of the user interface.
     */
    final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private volatile String scenario;
    private volatile ToIntFunction<Display> checks;
    private volatile Thread mainThread;
    private volatile Thread startupEventThread;
    private volatile boolean startupEventOnUiThread;
    private volatile Display display;
    private volatile int exitCode = 1;

    void start(String scenario, ToIntFunction<Display> checks) {
        this.scenario = scenario;
        this.checks = checks;
    }

    void mainThread(Thread thread) {
        this.mainThread = thread;
    }

    Thread mainThread() {
        return mainThread;
    }

    Thread startupEventThread() {
        return startupEventThread;
    }

    boolean startupEventOnUiThread() {
        return startupEventOnUiThread;
    }

    Display display() {
        return display;
    }

    int exitCode() {
        return exitCode;
    }

    /**
     * A task queued before the user interface starts : it runs after the {@code SwtStartupEvent} observers.
     */
    void beforeUserInterface(@Observes StartupEvent event) {
        uiThread.execute(() -> events.add("queued-by-startup-event"));
    }

    void open(@Observes SwtStartupEvent event) {
        display = event.display();
        startupEventThread = Thread.currentThread();
        startupEventOnUiThread = UiThread.isUiThread() && UiThread.display() == event.display();
        events.add("swt-startup-event");
        if (checks == null) {
            exitPolicy(event.display());
            return;
        }
        // Keeps the application running between the shells of the checks (the exit on last shell closed policy, in
        // production), disposed when the application stops
        Shell anchor = new Shell(event.display(), SWT.SHELL_TRIM);
        anchor.setText("Quarkus Desktop SWT IT (" + scenario + ")");
        anchor.setBounds(40, 600, 320, 80);
        anchor.addListener(SWT.Dispose, e -> events.add("anchor-disposed"));
        anchor.setVisible(true);
        // Queued : after the tasks queued before the user interface started
        uiThread.execute(() -> {
            int status;
            try {
                status = checks.applyAsInt(event.display());
            } catch (Throwable e) {
                System.out.println("RESULT checks FAILED " + e);
                e.printStackTrace(System.out);
                status = 1;
            }
            exitCode = status;
            Quarkus.asyncExit(status);
        });
    }

    /**
     * The {@code exit-policy} scenario : the only shell closes, the application stops by itself.
     */
    private void exitPolicy(Display display) {
        exitCode = 0;
        Shell shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText("Quarkus Desktop SWT IT (exit policy)");
        shell.setBounds(40, 40, 320, 120);
        shell.open();
        display.timerExec(500, shell::dispose);
        display.timerExec(20_000, () -> {
            System.out.println("RESULT exit-policy FAILED the application did not stop when its last shell closed");
            exitCode = 1;
            Quarkus.asyncExit(1);
        });
    }

    void stopped(@Observes ShutdownEvent event) {
        events.add("shutdown-event");
        if (!"exit-policy".equals(scenario)) {
            List<String> order = List.copyOf(events);
            boolean disposedFirst = order.indexOf("anchor-disposed") >= 0
                    && order.indexOf("anchor-disposed") < order.indexOf("shutdown-event");
            System.out.println("RESULT shutdown-order " + (disposedFirst ? "OK" : "FAILED") + " events=" + order);
        }
    }
}
