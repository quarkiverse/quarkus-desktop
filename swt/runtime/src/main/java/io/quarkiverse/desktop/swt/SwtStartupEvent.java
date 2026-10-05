package io.quarkiverse.desktop.swt;

import org.eclipse.swt.widgets.Display;

/**
 * Fired on the user interface thread once the {@code Display} of the application is created, to create its user
 * interface.
 * <p>
 * {@link SwtLifecycle#run()} fires it : the application started (after the {@code StartupEvent} observers), and the event
 * loop starts once the observers return, so that the listeners they add to the {@code Display}
 * ({@code SWT.OpenDocument}, {@code SWT.OpenUrl}...) get the first events. Without a {@code @QuarkusMain}, the application
 * runs {@code SwtLifecycle.run()} on its main thread : an application that observes this event needs no {@code main}
 * method. An application that observes it is a user interface application : its last shell closing stops it
 * ({@code quarkus.desktop.swt.exit-on-last-shell-closed}). It is not fired in tests unless
 * {@code quarkus.desktop.swt.startup-event.enabled} is {@code true}.
 *
 * <pre>
 * &#64;Singleton
 * public class MainWindow {
 *
 *     &#64;Inject
 *     Library library;
 *
 *     void open(&#64;Observes SwtStartupEvent event) {
 *         Shell shell = new Shell(event.display());
 *         shell.setText("Library");
 *         shell.setLayout(new FillLayout());
 *         org.eclipse.swt.widgets.List titles = new org.eclipse.swt.widgets.List(shell, SWT.V_SCROLL);
 *         library.titles().forEach(titles::add);
 *         shell.pack();
 *         shell.open();
 *     }
 * }
 * </pre>
 */
public final class SwtStartupEvent {

    private final Display display;

    /**
     * @param display the {@code Display} of the application
     */
    public SwtStartupEvent(Display display) {
        this.display = display;
    }

    /**
     * The {@code Display} of the application, created on the current thread : the user interface thread.
     */
    public Display display() {
        return display;
    }
}
