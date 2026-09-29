package io.quarkiverse.desktop.awt;

import java.awt.desktop.QuitEvent;

/**
 * Fired on the event dispatch thread when macOS asks the application to quit (Quit in the application menu, Cmd-Q, Quit
 * in the Dock, a logout or a shutdown) : the application stops ({@code Quarkus.asyncExit(0)}) unless an observer cancels
 * the request.
 * <p>
 * Only macOS sends quit requests, and only to an application observing {@link DesktopStartupEvent} : on Windows and
 * Linux, closing the windows stops the application ({@code quarkus.desktop.awt.exit-on-last-window-closed}), observe
 * {@code WINDOW_CLOSING} to ask for confirmation there.
 *
 * <pre>
 * &#64;Singleton
 * public class UnsavedChanges {
 *
 *     void confirmQuit(&#64;Observes QuitRequest request) {
 *         if (documents.hasUnsavedChanges() &amp;&amp; JOptionPane.showConfirmDialog(null, "Quit without saving?", "Quit",
 *                 JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
 *             request.cancel();
 *         }
 *     }
 * }
 * </pre>
 */
public final class QuitRequest {

    private final QuitEvent event;

    private volatile boolean cancelled;

    /**
     * @param event the event of {@code java.awt.Desktop}
     */
    public QuitRequest(QuitEvent event) {
        this.event = event;
    }

    /**
     * The event of {@code java.awt.Desktop}.
     */
    public QuitEvent event() {
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
