package io.quarkiverse.desktop.swt;

import org.eclipse.swt.widgets.Event;

/**
 * Fired on the user interface thread when the {@code Display} is asked to close (its {@code SWT.Close} event) : Quit in
 * the application menu of macOS, Cmd-Q, Quit in the Dock, a logout or a shutdown, and {@code Display.close()} on every
 * platform. The application stops unless an observer cancels the request : on Windows and Linux, once SWT disposes the
 * {@code Display}, at once for {@code Display.close()}, and when the session ends for a logout or a shutdown (not when
 * it is only queried : another application may still refuse it) ; on macOS, at once ({@code Quarkus.asyncExit()}).
 * <p>
 * On macOS, the extension always answers "not now" to the system, and stops the application itself : Quarkus disposes
 * the {@code Display} and runs its shutdown, AppKit would end the process at once. A logout, a restart or a shutdown of
 * macOS is then cancelled (macOS reports that the application canceled it) : the user repeats it once the application
 * exited.
 * <p>
 * On macOS, a {@code SIGTERM} may fire it too : AppKit asks the application to terminate while the JVM stops it on other
 * threads, and the request is fired when the event loop handles it first. The application stops whatever the observers
 * answer, and an observer blocking the user interface thread (a modal dialog) delays the exit by the stop time-out.
 * <p>
 * Closing the shells is not a quit request : the last shell closing stops the application
 * ({@code quarkus.desktop.swt.exit-on-last-shell-closed}), add a {@code SWT.Close} listener to a shell to ask for
 * confirmation there.
 *
 * <pre>
 * &#64;Singleton
 * public class UnsavedChanges {
 *
 *     void confirmQuit(&#64;Observes QuitRequest request) {
 *         MessageBox box = new MessageBox(mainShell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
 *         box.setMessage("Quit without saving?");
 *         if (documents.hasUnsavedChanges() &amp;&amp; box.open() != SWT.YES) {
 *             request.cancel();
 *         }
 *     }
 * }
 * </pre>
 */
public final class QuitRequest {

    private final Event event;

    private volatile boolean cancelled;

    /**
     * @param event the {@code SWT.Close} event of the {@code Display}
     */
    public QuitRequest(Event event) {
        this.event = event;
    }

    /**
     * The {@code SWT.Close} event of the {@code Display}.
     */
    public Event event() {
        return event;
    }

    /**
     * Cancels the request : the application keeps running.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Whether an observer cancelled the request.
     */
    public boolean isCancelled() {
        return cancelled;
    }
}
