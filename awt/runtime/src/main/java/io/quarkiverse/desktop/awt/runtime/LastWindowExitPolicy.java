package io.quarkiverse.desktop.awt.runtime;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.WindowEvent;

import org.jboss.logging.Logger;

/**
 * Stops the application ({@code Quarkus.asyncExit()}) when its last visible window is closed or hidden, once a first
 * window opened, as JavaFX does ({@code Platform.setImplicitExit}).
 * <p>
 * Hidden windows count as closed : a {@code JFrame} hides on close by default ({@code HIDE_ON_CLOSE}), and a
 * {@code java.awt.Frame} does nothing. The windows are counted again in a later event, so that a window disposed and
 * another one shown by the same event handler does not stop the application.
 * <p>
 * It listens to the window events of the toolkit, and to the component events of the open windows only : a component
 * listener of the toolkit would make every component post an event each time it moves or is resized (the cell
 * renderers of tables and lists, for each painted cell).
 */
final class LastWindowExitPolicy implements AWTEventListener {

    private static final Logger LOGGER = Logger.getLogger(LastWindowExitPolicy.class);

    private volatile boolean armed;

    private volatile boolean removed;

    private boolean exiting;

    private final Runnable exit;

    private final ComponentListener hidden = new ComponentAdapter() {
        @Override
        public void componentHidden(ComponentEvent e) {
            if (armed && !removed) {
                EventQueue.invokeLater(LastWindowExitPolicy.this::exitWithoutVisibleWindow);
            }
        }
    };

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
        Toolkit.getDefaultToolkit().addAWTEventListener(policy, AWTEvent.WINDOW_EVENT_MASK);
        // the windows opened before (a splash screen)
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) {
                policy.listen(window);
            }
        }
        return policy;
    }

    /**
     * Removes the policy (from any thread).
     */
    void remove() {
        removed = true;
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        for (Window window : Window.getWindows()) {
            window.removeComponentListener(hidden);
        }
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (removed || !(event.getSource() instanceof Window window)) {
            return;
        }
        int id = event.getID();
        if (id == WindowEvent.WINDOW_OPENED) {
            armed = true;
            listen(window);
        } else if (id == WindowEvent.WINDOW_CLOSED) {
            window.removeComponentListener(hidden);
            if (armed) {
                EventQueue.invokeLater(this::exitWithoutVisibleWindow);
            }
        }
    }

    private void listen(Window window) {
        window.removeComponentListener(hidden);
        window.addComponentListener(hidden);
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
