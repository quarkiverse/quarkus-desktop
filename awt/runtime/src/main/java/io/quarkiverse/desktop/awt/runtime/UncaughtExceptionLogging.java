package io.quarkiverse.desktop.awt.runtime;

import io.quarkus.core.StartContext;

/**
 * The logging of the uncaught exceptions of an application with a user interface ({@link UncaughtExceptionLogger}) :
 * installed once the user interface starts (after the {@code StartupEvent} observers), and removed in dev and test modes
 * once the other stop handlers of the extension ran (the {@code ShutdownEvent} observers, the dispose of the windows),
 * whose exceptions on the event dispatch thread are logged too.
 * <p>
 * The value of a service that starts before the {@code StartupEvent} observers, and so stops after the
 * {@code ShutdownEvent} ones, required by the service of the user interface, which starts after them : a service cannot
 * start after a service and stop after it too.
 */
public final class UncaughtExceptionLogging {

    private UncaughtExceptionLogger installed;

    private UncaughtExceptionLogging() {
    }

    /**
     * The logging of the uncaught exceptions of the application, which removes the logger it installed when the service
     * stops.
     *
     * @param context the start context of the service
     */
    public static UncaughtExceptionLogging create(StartContext context) {
        UncaughtExceptionLogging logging = new UncaughtExceptionLogging();
        context.onStop(logging::remove);
        return logging;
    }

    /**
     * Installs the logger as the default uncaught exception handler, unless there is one.
     *
     * @param removed whether to remove it when the application stops (dev and test modes : the JVM goes on)
     */
    synchronized void install(boolean removed) {
        UncaughtExceptionLogger logger = UncaughtExceptionLogger.install();
        if (removed) {
            installed = logger;
        }
    }

    private synchronized void remove() {
        if (installed != null) {
            installed.remove();
            installed = null;
        }
    }
}
