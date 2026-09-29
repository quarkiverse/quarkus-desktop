package io.quarkiverse.desktop.awt.runtime;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;

import org.jboss.logging.Logger;

/**
 * Stops the application ({@code Quarkus.asyncExit()}) when its last visible window is closed or hidden, once a first
 * window opened, as JavaFX does ({@code Platform.setImplicitExit}).
 * <p>
 * Hidden windows count as closed : a {@code JFrame} hides on close by default ({@code HIDE_ON_CLOSE}), and a
 * {@code java.awt.Frame} does nothing. The windows are counted again in a later event, so that a window disposed and
 * another one shown by the same event handler does not stop the application.
 */
final class LastWindowExitPolicy implements AWTEventListener {

    private static final Logger LOGGER = Logger.getLogger(LastWindowExitPolicy.class);

    private volatile boolean armed;

    private volatile boolean removed;

    private boolean exiting;

    private final Runnable exit;

    private LastWindowExitPolicy(Runnable exit) {
        this.exit = exit;
    }

    /**
     * Installs the policy (from any thread).
     *
     * @param exit stops the application
     */
    static LastWindowExitPolicy install(Runnable exit) {
        LastWindowExitPolicy policy = new LastWindowExitPolicy(exit);
        Toolkit.getDefaultToolkit().addAWTEventListener(policy, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
        return policy;
    }

    /**
     * Removes the policy (from any thread).
     */
    void remove() {
        removed = true;
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (removed || !(event.getSource() instanceof Window)) {
            return;
        }
        int id = event.getID();
        if (id == WindowEvent.WINDOW_OPENED) {
            armed = true;
        } else if (armed && (id == WindowEvent.WINDOW_CLOSED || id == ComponentEvent.COMPONENT_HIDDEN)) {
            EventQueue.invokeLater(this::exitWithoutVisibleWindow);
        }
    }

    private void exitWithoutVisibleWindow() {
        if (removed || exiting) {
            return;
        }
        for (Window window : Window.getWindows()) {
            if (window.isVisible()) {
                return;
            }
        }
        exiting = true;
        LOGGER.debug("The last visible window closed : the application stops");
        exit.run();
    }
}
