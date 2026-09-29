package io.quarkiverse.desktop.awt.runtime;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.DesktopLifecycle;
import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;

/**
 * The user interface of the application : fires {@link DesktopStartupEvent} on the event dispatch thread, with the exit
 * on last window closed policy.
 */
@Singleton
public class DesktopUi implements DesktopLifecycle {

    private static final Logger LOGGER = Logger.getLogger(DesktopLifecycle.class);

    @Inject
    Event<DesktopStartupEvent> startupEvent;

    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * {@code null} until the application starts with a user interface.
     */
    private volatile LaunchMode launchMode;

    private volatile boolean exitOnLastWindowClosed;

    private volatile boolean stopped;

    private volatile LastWindowExitPolicy exitPolicy;

    /**
     * Enables {@link #start()} : the application starts with a user interface.
     */
    void enable(LaunchMode launchMode, boolean exitOnLastWindowClosed) {
        this.exitOnLastWindowClosed = exitOnLastWindowClosed;
        this.launchMode = launchMode;
    }

    @Override
    public void start() {
        if (launchMode == null) {
            LOGGER.debug("DesktopStartupEvent is not fired : it is disabled, or the JVM is headless, or the application"
                    + " does not observe it");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            LOGGER.debug("DesktopStartupEvent is already fired");
            return;
        }
        EventQueue.invokeLater(this::fire);
    }

    private void fire() {
        if (stopped) {
            return;
        }
        if (exitOnLastWindowClosed) {
            exitPolicy = LastWindowExitPolicy.install(Quarkus::asyncExit);
        }
        try {
            startupEvent.fire(new DesktopStartupEvent());
        } catch (RuntimeException e) {
            if (launchMode == LaunchMode.NORMAL) {
                LOGGER.error("A DesktopStartupEvent observer failed : the application stops", e);
                Quarkus.asyncExit(1);
            } else {
                LOGGER.error("A DesktopStartupEvent observer failed", e);
            }
        }
    }

    /**
     * When the application stops, before the {@code ShutdownEvent} observers and the dispose of the windows (dev and test
     * modes) : the windows closing then do not stop the application again.
     */
    void stop() {
        stopped = true;
        LastWindowExitPolicy policy = exitPolicy;
        if (policy != null) {
            policy.remove();
        }
    }
}
