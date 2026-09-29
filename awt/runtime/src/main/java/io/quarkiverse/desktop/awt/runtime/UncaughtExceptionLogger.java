package io.quarkiverse.desktop.awt.runtime;

import org.jboss.logging.Logger;

/**
 * Logs the exceptions that no code catches (those of the listeners on the event dispatch thread, which keeps running
 * after them) with the logging of Quarkus, in the category {@value #CATEGORY}, instead of printing them on the standard
 * error.
 * <p>
 * It is the default handler of every thread ({@code Thread.setDefaultUncaughtExceptionHandler}), not a handler of the
 * event dispatch thread : AWT replaces the event dispatch thread (for instance after a second without windows), and the
 * new one would not inherit it. It is only installed when the JVM has no default handler.
 */
final class UncaughtExceptionLogger implements Thread.UncaughtExceptionHandler {

    /**
     * The log category of the uncaught exceptions.
     */
    static final String CATEGORY = "io.quarkiverse.desktop.awt.edt";

    private static final Logger LOGGER = Logger.getLogger(CATEGORY);

    private UncaughtExceptionLogger() {
    }

    /**
     * Installs the logger as the default uncaught exception handler, unless there is one.
     *
     * @return the logger, or {@code null} when another handler is installed
     */
    static synchronized UncaughtExceptionLogger install() {
        if (Thread.getDefaultUncaughtExceptionHandler() != null) {
            LOGGER.debugf("The uncaught exceptions are handled by %s", Thread.getDefaultUncaughtExceptionHandler());
            return null;
        }
        UncaughtExceptionLogger logger = new UncaughtExceptionLogger();
        Thread.setDefaultUncaughtExceptionHandler(logger);
        return logger;
    }

    /**
     * Restores the previous default handler (none), unless another handler replaced this one.
     */
    void remove() {
        synchronized (UncaughtExceptionLogger.class) {
            if (Thread.getDefaultUncaughtExceptionHandler() == this) {
                Thread.setDefaultUncaughtExceptionHandler(null);
            }
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable e) {
        LOGGER.errorf(e, "Uncaught exception in the thread %s", thread.getName());
    }
}
