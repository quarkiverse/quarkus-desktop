package io.quarkiverse.desktop.awt.runtime;

import java.awt.Component;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.DesktopLifecycle;
import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkiverse.desktop.awt.OpenRequest;
import io.quarkiverse.desktop.awt.OpenRequest.Source;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.CommandLineArguments;

/**
 * The user interface of the application : fires {@link DesktopStartupEvent} on the event dispatch thread, with the exit
 * on last window closed policy and the handlers of {@code java.awt.Desktop}, then the {@link OpenRequest}s.
 */
@Singleton
public class DesktopUi implements DesktopLifecycle {

    private static final Logger LOGGER = Logger.getLogger(DesktopLifecycle.class);

    @Inject
    Event<DesktopStartupEvent> startupEvent;

    @Inject
    Event<Object> events;

    @Inject
    @CommandLineArguments
    String[] arguments;

    private enum State {
        /**
         * The application did not start yet (or does not observe {@code DesktopStartupEvent}).
         */
        NEW,
        /**
         * The event is disabled (tests).
         */
        DISABLED,
        /**
         * The JVM is headless.
         */
        HEADLESS,
        /**
         * The event can be fired.
         */
        READY,
        /**
         * The event is fired.
         */
        STARTED
    }

    /**
     * Guards the state, and makes {@link #fire()} and {@link #stop()} exclusive : an application stopping while the event
     * is fired does not keep the policy and the handlers installed.
     */
    private final Object lock = new Object();

    private State state = State.NEW;

    private boolean requested;

    private LaunchMode launchMode;

    private boolean exitOnLastWindowClosed;

    private List<String> desktopEvents = List.of();

    private DesktopHandlers handlers;

    private LastWindowExitPolicy exitPolicy;

    private volatile boolean stopped;

    /**
     * The program arguments to open, {@code null} when the application does not observe {@link OpenRequest}.
     */
    private OpenArguments openArguments;

    /**
     * Whether {@code DesktopStartupEvent} was fired : the {@link OpenRequest}s are queued until then.
     */
    private boolean opened;

    private final List<OpenRequest> openRequests = new ArrayList<>();

    /**
     * Whether this launch forwarded its request to the running instance of the application, and stops.
     */
    private volatile boolean forwarded;

    /**
     * Enables {@link #start()} : the application starts with a user interface. Fires the event when the application
     * called {@link #start()} before (from a {@code StartupEvent} observer).
     *
     * @param desktopHandlers whether to install the handlers of {@code java.awt.Desktop}
     * @param desktopEvents the class names of the {@code java.awt.Desktop} events that the application observes
     * @param openArguments the program arguments to open, {@code null} when the application does not observe
     *        {@link OpenRequest}
     */
    void enable(LaunchMode launchMode, boolean exitOnLastWindowClosed, boolean desktopHandlers, List<String> desktopEvents,
            OpenArguments openArguments) {
        boolean start;
        synchronized (lock) {
            this.launchMode = launchMode;
            this.exitOnLastWindowClosed = exitOnLastWindowClosed;
            this.desktopEvents = List.copyOf(desktopEvents);
            this.openArguments = openArguments;
            this.handlers = desktopHandlers
                    ? new DesktopHandlers(event -> events.fire(event), () -> stopped, () -> Quarkus.asyncExit(0),
                            launchMode == LaunchMode.NORMAL)
                    : null;
            state = State.READY;
            start = requested;
        }
        if (start) {
            start();
        }
    }

    /**
     * The event is disabled (tests) : {@link #start()} does nothing.
     */
    void disable() {
        synchronized (lock) {
            state = State.DISABLED;
        }
    }

    /**
     * The JVM is headless (manual mode) : {@link #start()} logs an error and, in production, stops the application.
     */
    void headless(LaunchMode launchMode) {
        boolean start;
        synchronized (lock) {
            this.launchMode = launchMode;
            state = State.HEADLESS;
            start = requested;
        }
        if (start) {
            headlessError(launchMode);
        }
    }

    /**
     * Logs that the JVM is headless : an error and {@code Quarkus.asyncExit(1)} in production, a warning otherwise.
     */
    static void headlessError(LaunchMode launchMode) {
        if (launchMode == LaunchMode.NORMAL) {
            LOGGER.error("The application observes DesktopStartupEvent, but the JVM is headless (no display, or"
                    + " java.awt.headless=true) : the application stops");
            Quarkus.asyncExit(1);
        } else {
            LOGGER.warn("The application observes DesktopStartupEvent, but the JVM is headless (no display, or"
                    + " java.awt.headless=true) : the event is not fired");
        }
    }

    @Override
    public void start() {
        LaunchMode headless = null;
        synchronized (lock) {
            switch (state) {
                case NEW -> {
                    requested = true;
                    LOGGER.debug("DesktopLifecycle.start() is called before the application started : DesktopStartupEvent"
                            + " is fired once it started, if the application observes it");
                    return;
                }
                case DISABLED -> {
                    LOGGER.debug("DesktopStartupEvent is not fired : it is disabled in tests");
                    return;
                }
                case HEADLESS -> {
                    if (requested) {
                        return;
                    }
                    requested = true;
                    headless = launchMode;
                }
                case STARTED -> {
                    LOGGER.debug("DesktopStartupEvent is already fired");
                    return;
                }
                case READY -> state = State.STARTED;
            }
        }
        if (headless != null) {
            headlessError(headless);
            return;
        }
        EventQueue.invokeLater(this::fire);
    }

    private void fire() {
        try {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                if (handlers != null) {
                    handlers.install(desktopEvents);
                }
                if (exitOnLastWindowClosed) {
                    exitPolicy = LastWindowExitPolicy.install(Quarkus::asyncExit);
                }
            }
            if (launchMode != LaunchMode.NORMAL) {
                warnComponentsCreatedBefore();
            }
            startupEvent.fire(new DesktopStartupEvent());
        } catch (Throwable e) {
            // Errors too : a native executable missing some metadata fails with a LinkageError or a
            // MissingReflectionRegistrationError, and the application would wait for ever without a window
            if (launchMode == LaunchMode.NORMAL) {
                LOGGER.error("A DesktopStartupEvent observer failed : the application stops", e);
                Quarkus.asyncExit(1);
                return;
            } else {
                LOGGER.error("A DesktopStartupEvent observer failed", e);
            }
        }
        fireOpenRequests();
    }

    /**
     * Once {@code DesktopStartupEvent} is fired : fires the {@link OpenRequest} of the program arguments, then those
     * queued meanwhile (the next launches of the application).
     */
    private void fireOpenRequests() {
        OpenArguments parser;
        synchronized (lock) {
            parser = openArguments;
        }
        OpenRequest launch = parser != null ? parser.request(arguments(), Source.LAUNCH) : null;
        List<OpenRequest> requests = new ArrayList<>();
        if (launch != null && !launch.isEmpty()) {
            requests.add(launch);
        }
        synchronized (lock) {
            opened = true;
            requests.addAll(openRequests);
            openRequests.clear();
        }
        requests.forEach(this::fireOpenRequest);
    }

    /**
     * Fires an {@link OpenRequest} on the event dispatch thread (from any thread) : queued until
     * {@code DesktopStartupEvent} is fired, ignored once the application stops.
     */
    void open(OpenRequest request) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            if (!opened) {
                openRequests.add(request);
                return;
            }
        }
        EventQueue.invokeLater(() -> fireOpenRequest(request));
    }

    private void fireOpenRequest(OpenRequest request) {
        if (stopped) {
            return;
        }
        try {
            LOGGER.debugf("Fires %s", request);
            events.fire(request);
        } catch (Throwable e) {
            LOGGER.error("An OpenRequest observer failed", e);
        }
    }

    /**
     * The program arguments of the application.
     */
    List<String> arguments() {
        return arguments != null ? List.of(arguments) : List.of();
    }

    /**
     * This launch forwarded its request to the running instance of the application, and stops : the user interface
     * does not start.
     */
    void forwarded() {
        forwarded = true;
        disable();
    }

    /**
     * Whether this launch forwarded its request to the running instance of the application.
     */
    boolean isForwarded() {
        return forwarded;
    }

    /**
     * Warns about the {@code @Singleton} component beans created before {@code DesktopStartupEvent} (dev and test modes) :
     * a component bean injected into a {@code @QuarkusMain}, a {@code StartupEvent} observer or a REST resource is
     * created on their thread, not on the event dispatch thread. ArC creates a bean while holding a lock that the
     * observers of the bean then wait for : a bean that waits for the event dispatch thread while it is created
     * ({@code SwingUtilities.invokeAndWait}, {@code Edt.call}) deadlocks with them.
     */
    private static void warnComponentsCreatedBefore() {
        try {
            InjectableContext singletons = Arc.container().getActiveContext(Singleton.class);
            if (singletons == null) {
                return;
            }
            for (InjectableBean<?> bean : singletons.getState().getContextualInstances().keySet()) {
                Class<?> type = bean.getImplementationClass();
                if (type != null && Component.class.isAssignableFrom(type)) {
                    LOGGER.warnf("The component bean %s is created before DesktopStartupEvent, maybe outside the event"
                            + " dispatch thread (injected into a @QuarkusMain, a StartupEvent observer, a REST"
                            + " resource...). Inject Instance<%s> there, and get it on the event dispatch thread.",
                            type.getName(), type.getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Unable to check the component beans created before DesktopStartupEvent", e);
        }
    }

    /**
     * When the application stops, before the {@code ShutdownEvent} observers and the dispose of the windows (dev and test
     * modes) : the windows closing then do not stop the application again. In dev and test modes, removes the handlers of
     * {@code java.awt.Desktop}, which would call a stopped application (they only ignore the events in production, where
     * the default quit handler would call {@code System.exit} during the shutdown).
     */
    void stop() {
        synchronized (lock) {
            stopped = true;
            if (exitPolicy != null) {
                exitPolicy.remove();
            }
            if (handlers != null && launchMode != LaunchMode.NORMAL) {
                handlers.remove();
            }
        }
    }
}
