package io.quarkiverse.desktop.awt.deployment;

/**
 * The names of the services that the services of the Desktop extensions order theirs against
 * ({@code ServiceRegistrar}) : a service starts after the services it declares with {@code after()}, and stops before
 * them, a service declaring {@code before()} the other way round.
 */
public final class DesktopAwtServices {

    /**
     * Prepares the AWT run time environment of a native executable when it starts (the {@code java.home} directory, the
     * font configuration of the JDK), before any AWT class is used. A service that uses AWT (fonts, look and feels,
     * windows...) starts after it : the font configuration is read once, when the fonts are first used. Only in native
     * executables : {@code after()} a service that does not exist is ignored.
     */
    public static final String RUNTIME_HOME = "io.quarkiverse.desktop.awt.runtime-home";

    /**
     * Dispatches the AWT events with the class loader of the application, until the other stop handlers ran (dev and
     * test modes).
     */
    static final String APPLICATION_EVENT_QUEUE = "io.quarkiverse.desktop.awt.application-event-queue";

    /**
     * Disposes the windows when the application stops, after the {@code ShutdownEvent} observers and before the CDI
     * container stops (dev and test modes).
     */
    static final String DISPOSE_WINDOWS = "io.quarkiverse.desktop.awt.dispose-windows";

    /**
     * Starts the user interface (fires {@code DesktopStartupEvent}) after the {@code StartupEvent} observers.
     */
    static final String USER_INTERFACE = "io.quarkiverse.desktop.awt.user-interface";

    /**
     * The ArC service firing {@code StartupEvent} when it starts, and {@code ShutdownEvent} when it stops : a service
     * started after it starts after the {@code StartupEvent} observers, and stops before the {@code ShutdownEvent} ones.
     * A build step producing a {@code ServiceStartBuildItem} has its services started before it.
     */
    static final String ARC_STARTUP_EVENT = "io.quarkus.arc.startup-event";

    /**
     * The ArC service shutting the CDI container down when it stops : a service that uses beans when it stops starts
     * after it, and so stops before it.
     */
    static final String ARC_SHUTDOWN = "io.quarkus.arc.shutdown-gateway";

    private DesktopAwtServices() {
    }
}
