package io.quarkiverse.desktop.swt;

/**
 * Runs the user interface of the application : creates the {@code Display}, fires {@link SwtStartupEvent} and runs the
 * event loop of SWT on the calling thread, which becomes the user interface thread.
 * <p>
 * SWT runs its event loop on the thread that created the {@code Display}, the first thread of the process on macOS.
 * Without a {@code @QuarkusMain}, an application observing {@code SwtStartupEvent} calls {@link #run()} on its main
 * thread. A {@code @QuarkusMain} calls it itself once its work before the user interface is done :
 *
 * <pre>
 * &#64;QuarkusMain
 * public class Main implements QuarkusApplication {
 *
 *     &#64;Inject
 *     SwtLifecycle lifecycle;
 *
 *     &#64;Override
 *     public int run(String... args) {
 *         // parse the arguments, check the single instance lock...
 *         lifecycle.run();
 *         return 0;
 *     }
 * }
 * </pre>
 */
public interface SwtLifecycle {

    /**
     * Creates the {@code Display} on the calling thread, fires {@link SwtStartupEvent}, then runs the event loop until
     * the application stops ({@code Quarkus.asyncExit()}, the last shell closed, a quit request, a signal), and disposes
     * the {@code Display} before it returns : its shells are disposed, and their {@code SWT.Dispose} listeners run, before
     * the {@code ShutdownEvent} observers.
     * <p>
     * Returns at once when the user interface is disabled (tests), and when the application is stopping (an exit
     * requested before, the user interface already ran) : the {@code Display} is not created then. In a
     * {@code @QuarkusTest}, where the user interface runs on a thread of the extension, waits until the application
     * stops. When the {@code Display} cannot be created (no display on Linux, not the first thread of the process on
     * macOS), logs an error and, in production and in a {@code @QuarkusMainTest}, stops the application with exit code
     * 1.
     *
     * @throws IllegalStateException when the user interface already runs
     */
    void run();
}
