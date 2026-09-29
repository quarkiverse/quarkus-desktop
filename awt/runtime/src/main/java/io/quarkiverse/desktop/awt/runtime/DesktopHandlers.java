package io.quarkiverse.desktop.awt.runtime;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.awt.desktop.AboutEvent;
import java.awt.desktop.AppReopenedEvent;
import java.awt.desktop.AppReopenedListener;
import java.awt.desktop.OpenFilesEvent;
import java.awt.desktop.OpenURIEvent;
import java.awt.desktop.PreferencesEvent;
import java.awt.desktop.PrintFilesEvent;
import java.awt.desktop.QuitEvent;
import java.awt.desktop.QuitResponse;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.QuitRequest;
import io.smallrye.common.os.OS;

/**
 * The application handlers of {@code java.awt.Desktop} (the application menu, the Finder and the Dock of macOS) as CDI
 * events : the About and Preferences menu items, the files and URIs opened with the application, the Dock icon clicked,
 * and the quit requests ({@link QuitRequest}).
 * <p>
 * Only the handlers of the observed events are installed (an About handler replaces the default About window), and the
 * quit handler on macOS, which stops the application through Quarkus instead of {@code System.exit} on the event
 * dispatch thread. Nothing is installed on Linux, which supports none of these actions (and where
 * {@code java.awt.Desktop} loads GTK).
 */
public final class DesktopHandlers {

    private static final Logger LOGGER = Logger.getLogger(DesktopHandlers.class);

    /**
     * A handler of {@code java.awt.Desktop}, and the event it fires.
     */
    enum Handler {
        ABOUT(AboutEvent.class, Action.APP_ABOUT),
        PREFERENCES(PreferencesEvent.class, Action.APP_PREFERENCES),
        OPEN_FILES(OpenFilesEvent.class, Action.APP_OPEN_FILE),
        OPEN_URI(OpenURIEvent.class, Action.APP_OPEN_URI),
        PRINT_FILES(PrintFilesEvent.class, Action.APP_PRINT_FILE),
        APP_REOPENED(AppReopenedEvent.class, Action.APP_EVENT_REOPENED),
        QUIT(QuitRequest.class, Action.APP_QUIT_HANDLER);

        final Class<?> event;

        final Action action;

        Handler(Class<?> event, Action action) {
            this.event = event;
            this.action = action;
        }
    }

    private final Consumer<Object> events;

    private final BooleanSupplier stopped;

    private final Runnable exit;

    private final Set<Handler> installed = EnumSet.noneOf(Handler.class);

    private final AppReopenedListener reopened;

    /**
     * @param events fires a CDI event, synchronously
     * @param stopped whether the application stopped
     * @param exit stops the application
     */
    DesktopHandlers(Consumer<Object> events, BooleanSupplier stopped, Runnable exit) {
        this.events = events;
        this.stopped = stopped;
        this.exit = exit;
        this.reopened = this::fire;
    }

    /**
     * The class names of the events fired : the {@code java.awt.desktop} events and {@link QuitRequest}.
     */
    public static Set<String> eventTypes() {
        return Arrays.stream(Handler.values()).map(handler -> handler.event.getName()).collect(Collectors.toSet());
    }

    /**
     * The handlers to install : those of the observed events, and the quit handler on macOS, when
     * {@code java.awt.Desktop} supports them. None on Linux and the other platforms, where {@code java.awt.Desktop} is
     * not called.
     *
     * @param observedEvents the class names of the observed events
     * @param os the platform
     * @param supported whether {@code java.awt.Desktop} supports an action, only called for the handlers to install
     */
    static Set<Handler> handlers(Collection<String> observedEvents, OS os, Predicate<Action> supported) {
        Set<Handler> handlers = EnumSet.noneOf(Handler.class);
        if (os != OS.MAC && os != OS.WINDOWS) {
            if (!observedEvents.isEmpty()) {
                LOGGER.debugf("The application observes %s, which %s does not send", observedEvents, os);
            }
            return handlers;
        }
        for (Handler handler : Handler.values()) {
            if (handler == Handler.QUIT && os == OS.MAC || observedEvents.contains(handler.event.getName())) {
                if (supported.test(handler.action)) {
                    handlers.add(handler);
                } else {
                    LOGGER.debugf("The platform does not support %s : %s is never fired", handler.action,
                            handler.event.getSimpleName());
                }
            }
        }
        return handlers;
    }

    /**
     * Whether {@code java.awt.Desktop} supports an action.
     */
    static boolean isSupported(Action action) {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action);
    }

    /**
     * Installs the handlers, on the event dispatch thread before {@code DesktopStartupEvent} : the files opened with the
     * application, which the JDK queues until a handler is installed, are then fired after it.
     *
     * @param observedEvents the class names of the observed events
     */
    synchronized void install(Collection<String> observedEvents) {
        Set<Handler> handlers;
        try {
            handlers = handlers(observedEvents, OS.current(), DesktopHandlers::isSupported);
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to install the handlers of java.awt.Desktop", e);
            return;
        }
        if (handlers.isEmpty()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        for (Handler handler : handlers) {
            try {
                switch (handler) {
                    case ABOUT -> desktop.setAboutHandler(this::fire);
                    case PREFERENCES -> desktop.setPreferencesHandler(this::fire);
                    case OPEN_FILES -> desktop.setOpenFileHandler(this::fire);
                    case OPEN_URI -> desktop.setOpenURIHandler(this::fire);
                    case PRINT_FILES -> desktop.setPrintFileHandler(this::fire);
                    case APP_REOPENED -> desktop.addAppEventListener(reopened);
                    case QUIT -> desktop.setQuitHandler(this::quit);
                }
                installed.add(handler);
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Unable to install the %s handler of java.awt.Desktop", handler.action);
            }
        }
        LOGGER.debugf("Installed the java.awt.Desktop handlers %s", installed);
    }

    /**
     * Removes the handlers (from any thread : the setters of the JDK are synchronized), which restores the default ones.
     */
    synchronized void remove() {
        if (installed.isEmpty()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        for (Handler handler : installed) {
            try {
                switch (handler) {
                    case ABOUT -> desktop.setAboutHandler(null);
                    case PREFERENCES -> desktop.setPreferencesHandler(null);
                    case OPEN_FILES -> desktop.setOpenFileHandler(null);
                    case OPEN_URI -> desktop.setOpenURIHandler(null);
                    case PRINT_FILES -> desktop.setPrintFileHandler(null);
                    case APP_REOPENED -> desktop.removeAppEventListener(reopened);
                    case QUIT -> desktop.setQuitHandler(null);
                }
            } catch (RuntimeException e) {
                LOGGER.debugf(e, "Unable to remove the %s handler of java.awt.Desktop", handler.action);
            }
        }
        installed.clear();
    }

    private void fire(Object event) {
        if (!stopped.getAsBoolean()) {
            events.accept(event);
        }
    }

    /**
     * Fires {@link QuitRequest} (on the event dispatch thread), then stops the application unless an observer cancelled
     * it. The quit of macOS is always cancelled ({@code QuitResponse.cancelQuit()}, as the JDK does for
     * {@code QuitStrategy.CLOSE_ALL_WINDOWS}) : Quarkus stops the application, and {@code performQuit()} would call
     * {@code System.exit} on the event dispatch thread.
     */
    void quit(QuitEvent event, QuitResponse response) {
        if (stopped.getAsBoolean()) {
            // the application is stopping already
            response.cancelQuit();
            return;
        }
        QuitRequest request = new QuitRequest(event);
        try {
            events.accept(request);
        } catch (RuntimeException e) {
            LOGGER.error("A QuitRequest observer failed", e);
        }
        response.cancelQuit();
        if (request.isCancelled()) {
            LOGGER.debug("The quit request is cancelled");
        } else {
            LOGGER.debug("Quit requested : the application stops");
            exit.run();
        }
    }
}
