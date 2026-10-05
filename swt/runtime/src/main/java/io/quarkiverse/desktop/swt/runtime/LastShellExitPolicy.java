package io.quarkiverse.desktop.swt.runtime;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.jboss.logging.Logger;

/**
 * Stops the application ({@code Quarkus.asyncExit()}) when its last visible shell is closed or hidden, once a first
 * shell opened, as the Desktop AWT extension does with the windows.
 * <p>
 * Closing a shell disposes it by default ; a shell hidden ({@code setVisible(false)}) counts as closed too. The shells are
 * counted again in a later task ({@code Display.asyncExec}) : during its {@code SWT.Dispose} event, a shell is not
 * disposed yet, and a shell disposed and another one opened by the same listener does not stop the application.
 * <p>
 * It listens to the {@code SWT.Show} events of every widget (a filter of the {@code Display}), and to the
 * {@code SWT.Hide} and {@code SWT.Dispose} events of the shells only : a {@code SWT.Dispose} filter would be notified for
 * every widget and item disposed.
 */
final class LastShellExitPolicy {

    private static final Logger LOGGER = Logger.getLogger(LastShellExitPolicy.class);

    private final Display display;

    private final Runnable exit;

    private boolean armed;

    private boolean removed;

    private boolean exiting;

    private final Listener shown = this::shown;

    private final Listener closed = event -> recountLater();

    private LastShellExitPolicy(Display display, Runnable exit) {
        this.display = display;
        this.exit = exit;
    }

    /**
     * Installs the policy, on the user interface thread.
     *
     * @param exit stops the application
     */
    static LastShellExitPolicy install(Display display, Runnable exit) {
        LastShellExitPolicy policy = new LastShellExitPolicy(display, exit);
        display.addFilter(SWT.Show, policy.shown);
        // the shells opened before
        for (Shell shell : display.getShells()) {
            if (!shell.isDisposed() && shell.isVisible()) {
                policy.armed = true;
                policy.listen(shell);
            }
        }
        return policy;
    }

    /**
     * Removes the policy, on the user interface thread, before the shells are disposed when the application stops.
     */
    void remove() {
        removed = true;
        if (display.isDisposed()) {
            return;
        }
        display.removeFilter(SWT.Show, shown);
        for (Shell shell : display.getShells()) {
            if (!shell.isDisposed()) {
                shell.removeListener(SWT.Hide, closed);
                shell.removeListener(SWT.Dispose, closed);
            }
        }
    }

    private void shown(Event event) {
        if (!removed && event.widget instanceof Shell shell) {
            armed = true;
            listen(shell);
        }
    }

    private void listen(Shell shell) {
        shell.removeListener(SWT.Hide, closed);
        shell.removeListener(SWT.Dispose, closed);
        shell.addListener(SWT.Hide, closed);
        shell.addListener(SWT.Dispose, closed);
    }

    private void recountLater() {
        if (removed || !armed || display.isDisposed()) {
            return;
        }
        try {
            display.asyncExec(this::exitWithoutVisibleShell);
        } catch (SWTException e) {
            // disposed meanwhile
        }
    }

    private void exitWithoutVisibleShell() {
        if (removed || exiting || display.isDisposed()) {
            return;
        }
        for (Shell shell : display.getShells()) {
            if (!shell.isDisposed() && shell.isVisible()) {
                return;
            }
        }
        exiting = true;
        LOGGER.debug("The last visible shell closed : the application stops");
        exit.run();
    }
}
