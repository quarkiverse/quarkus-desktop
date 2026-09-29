package io.quarkiverse.desktop.awt;

/**
 * Starts the user interface of the application : fires {@link DesktopStartupEvent} on the event dispatch thread.
 * <p>
 * With {@code quarkus.desktop.awt.startup-event.mode=manual}, the application calls {@link #start()} once its work
 * before the user interface is done, for instance from its {@code @QuarkusMain} :
 *
 * <pre>
 * &#64;QuarkusMain
 * public class Main implements QuarkusApplication {
 *
 *     &#64;Inject
 *     DesktopLifecycle lifecycle;
 *
 *     &#64;Override
 *     public int run(String... args) {
 *         // parse the arguments, check the single instance lock...
 *         lifecycle.start();
 *         Quarkus.waitForExit();
 *         return 0;
 *     }
 * }
 * </pre>
 */
public interface DesktopLifecycle {

    /**
     * Fires {@link DesktopStartupEvent} on the event dispatch thread ({@code EventQueue.invokeLater}) and returns. Only
     * the first call fires it. Called before the application started (from a {@code StartupEvent} observer), it fires the
     * event once the application started. Does nothing when the event is disabled (tests) or when the application does
     * not observe it. In a headless JVM, logs an error and, in production, stops the application with exit code 1.
     */
    void start();
}
