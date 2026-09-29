package io.quarkiverse.desktop.awt;

/**
 * Fired on the event dispatch thread once the application started, to create its user interface.
 * <p>
 * The observers of this event run after the {@code StartupEvent} observers and after the look and feel of
 * {@code quarkus.desktop.swing.look-and-feel} is set. An application that observes it is a user interface
 * application : its last window closing stops it ({@code quarkus.desktop.awt.exit-on-last-window-closed}). The event is
 * fired automatically ({@code quarkus.desktop.awt.startup-event.mode=auto}), or when the application calls
 * {@link DesktopLifecycle#start()} ({@code manual}). It is not fired in tests unless
 * {@code quarkus.desktop.awt.startup-event.enabled} is {@code true}, nor when the JVM is headless.
 *
 * <pre>
 * &#64;Singleton
 * public class MainWindow extends JFrame {
 *
 *     void open(&#64;Observes DesktopStartupEvent event) {
 *         setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
 *         pack();
 *         setVisible(true);
 *     }
 * }
 * </pre>
 */
public final class DesktopStartupEvent {
}
