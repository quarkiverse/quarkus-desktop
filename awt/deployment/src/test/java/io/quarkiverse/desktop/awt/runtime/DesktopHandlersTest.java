package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Desktop.Action;
import java.awt.desktop.AboutEvent;
import java.awt.desktop.AppForegroundEvent;
import java.awt.desktop.AppReopenedEvent;
import java.awt.desktop.OpenFilesEvent;
import java.awt.desktop.OpenURIEvent;
import java.awt.desktop.PreferencesEvent;
import java.awt.desktop.PrintFilesEvent;
import java.awt.desktop.QuitResponse;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.quarkiverse.desktop.awt.QuitRequest;
import io.quarkiverse.desktop.awt.runtime.DesktopHandlers.Handler;
import io.smallrye.common.os.OS;

/**
 * The handlers of {@code java.awt.Desktop} installed from the observed events and the platform, and the quit request.
 * Headless : {@code java.awt.Desktop} is not called.
 */
class DesktopHandlersTest {

    private static final List<String> ALL = List.of(AboutEvent.class.getName(), PreferencesEvent.class.getName(),
            OpenFilesEvent.class.getName(), OpenURIEvent.class.getName(), PrintFilesEvent.class.getName(),
            AppReopenedEvent.class.getName(), QuitRequest.class.getName());

    @Test
    void eventTypes() {
        assertEquals(Set.copyOf(ALL), DesktopHandlers.eventTypes());
    }

    @Test
    void macOsAlwaysHasQuitHandler() {
        List<Action> probed = new ArrayList<>();
        assertEquals(EnumSet.of(Handler.QUIT), DesktopHandlers.handlers(List.of(), OS.MAC, probed::add));
        assertEquals(List.of(Action.APP_QUIT_HANDLER), probed);
    }

    @Test
    void macOsObservedEvents() {
        assertEquals(EnumSet.of(Handler.ABOUT, Handler.OPEN_FILES, Handler.APP_REOPENED, Handler.QUIT),
                DesktopHandlers.handlers(List.of(AboutEvent.class.getName(), OpenFilesEvent.class.getName(),
                        AppReopenedEvent.class.getName(), AppForegroundEvent.class.getName()), OS.MAC, action -> true));
        assertEquals(EnumSet.allOf(Handler.class), DesktopHandlers.handlers(ALL, OS.MAC, action -> true));
    }

    @Test
    void unsupportedActionsAreSkipped() {
        assertEquals(EnumSet.of(Handler.ABOUT),
                DesktopHandlers.handlers(ALL, OS.MAC, action -> action == Action.APP_ABOUT));
    }

    @Test
    void windowsOnlyObservedEvents() {
        List<Action> probed = new ArrayList<>();
        // nothing observed : java.awt.Desktop is not called
        assertEquals(EnumSet.noneOf(Handler.class), DesktopHandlers.handlers(List.of(), OS.WINDOWS, probed::add));
        assertEquals(List.of(), probed);
        // Windows supports none of them
        assertEquals(EnumSet.noneOf(Handler.class),
                DesktopHandlers.handlers(List.of(AboutEvent.class.getName(), QuitRequest.class.getName()), OS.WINDOWS,
                        action -> !probed.add(action)));
        assertEquals(List.of(Action.APP_ABOUT, Action.APP_QUIT_HANDLER), probed);
    }

    @Test
    void linuxNeverProbesDesktop() {
        for (OS os : List.of(OS.LINUX, OS.OTHER)) {
            assertEquals(EnumSet.noneOf(Handler.class), DesktopHandlers.handlers(ALL, os, action -> {
                throw new AssertionError("java.awt.Desktop probed on " + os);
            }));
        }
    }

    /**
     * The constants of the enum are in the image heap of native executables, where {@code java.awt.Desktop.Action},
     * initialized at run time, cannot be.
     */
    @Test
    void handlersDoNotHoldDesktopActions() {
        for (Field field : Handler.class.getDeclaredFields()) {
            assertNotEquals(Action.class, field.getType(), field.toString());
        }
        for (Handler handler : Handler.values()) {
            assertEquals(handler.actionName, handler.action().name());
        }
    }

    @Test
    void quitStopsApplication() {
        // production : the reply stays pending until the process exits (a logout goes on)
        Quit quit = new Quit(request -> {
        }, true);
        quit.handlers.quit(null, quit.response);
        assertEquals(1, quit.fired.size());
        assertInstanceOf(QuitRequest.class, quit.fired.get(0));
        assertEquals(0, quit.response.cancelled.get());
        assertEquals(0, quit.response.performed.get());
        assertEquals(1, quit.exits.get());
        // dev and test modes : the JVM outlives the application, the quit is cancelled
        Quit dev = new Quit(request -> {
        }, false);
        dev.handlers.quit(null, dev.response);
        assertEquals(1, dev.response.cancelled.get());
        assertEquals(0, dev.response.performed.get());
        assertEquals(1, dev.exits.get());
    }

    @Test
    void quitCancelled() {
        for (boolean exitsJvm : new boolean[] { true, false }) {
            Quit quit = new Quit(QuitRequest::cancel, exitsJvm);
            quit.handlers.quit(null, quit.response);
            assertTrue(((QuitRequest) quit.fired.get(0)).isCancelled());
            assertEquals(1, quit.response.cancelled.get());
            assertEquals(0, quit.response.performed.get());
            assertEquals(0, quit.exits.get());
        }
    }

    @Test
    void quitObserverFails() {
        // a failure is a veto (an observer guarding unsaved changes), and macOS always gets a reply, errors included
        Quit quit = new Quit(request -> {
            throw new IllegalStateException("observer failure");
        }, true);
        quit.handlers.quit(null, quit.response);
        assertEquals(1, quit.response.cancelled.get());
        assertEquals(0, quit.exits.get());
        Quit error = new Quit(request -> {
            throw new NoClassDefFoundError("ConfirmationDialog");
        }, true);
        error.handlers.quit(null, error.response);
        assertEquals(1, error.response.cancelled.get());
        assertEquals(0, error.exits.get());
    }

    @Test
    void quitWhileStopping() {
        Quit quit = new Quit(request -> {
        }, true);
        quit.stopped.set(true);
        quit.handlers.quit(null, quit.response);
        assertEquals(List.of(), quit.fired);
        // production : the process is exiting
        assertEquals(0, quit.response.cancelled.get());
        assertEquals(0, quit.response.performed.get());
        assertEquals(0, quit.exits.get());
        Quit dev = new Quit(request -> {
        }, false);
        dev.stopped.set(true);
        dev.handlers.quit(null, dev.response);
        assertEquals(1, dev.response.cancelled.get());
        assertEquals(0, dev.exits.get());
    }

    @Test
    void removeWithoutHandlers() {
        // nothing installed : java.awt.Desktop is not called (headless)
        new Quit(request -> {
        }, false).handlers.remove();
    }

    private static final class Quit {

        final List<Object> fired = new ArrayList<>();
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicInteger exits = new AtomicInteger();
        final Response response = new Response();
        final DesktopHandlers handlers;

        Quit(Consumer<QuitRequest> observer, boolean exitsJvm) {
            handlers = new DesktopHandlers(event -> {
                fired.add(event);
                observer.accept((QuitRequest) event);
            }, stopped::get, exits::incrementAndGet, exitsJvm);
        }
    }

    private static final class Response implements QuitResponse {

        final AtomicInteger cancelled = new AtomicInteger();
        final AtomicInteger performed = new AtomicInteger();

        @Override
        public void performQuit() {
            performed.incrementAndGet();
        }

        @Override
        public void cancelQuit() {
            cancelled.incrementAndGet();
        }
    }

    @Test
    void quitRequestDefaults() {
        QuitRequest request = new QuitRequest(null);
        assertFalse(request.isCancelled());
        request.cancel();
        assertTrue(request.isCancelled());
    }
}
