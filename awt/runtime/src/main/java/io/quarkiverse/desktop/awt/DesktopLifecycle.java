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
     * the first call fires it. Does nothing when the event is disabled (tests, a headless JVM) or when the application
     * does not observe it.
     */
    void start();
}
