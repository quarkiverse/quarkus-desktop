package io.quarkiverse.desktop.awt.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Desktop AWT run time configuration : the user interface of the application (an application observing
 * {@code io.quarkiverse.desktop.awt.DesktopStartupEvent}), in JVM mode and in native executables.
 */
@ConfigMapping(prefix = "quarkus.desktop.awt")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DesktopAwtRuntimeConfig {

    /**
     * The {@code DesktopStartupEvent} fired on the event dispatch thread to create the user interface.
     */
    StartupEvent startupEvent();

    /**
     * Whether the application stops ({@code Quarkus.asyncExit()}) when its last visible window is closed or hidden, once
     * a first window opened. Only for an application observing {@code DesktopStartupEvent}, never in tests, nor with
     * Quarkus FX. Disable it for an application that stays alive without windows (a tray icon, the macOS convention),
     * or that closes a window before showing the next one (a splash screen closed before the main window shows).
     */
    @WithDefault("true")
    boolean exitOnLastWindowClosed();

    /**
     * The {@code DesktopStartupEvent} fired on the event dispatch thread to create the user interface.
     */
    interface StartupEvent {

        /**
         * Whether the event is fired. By default it is, except in tests ({@code @QuarkusTest}, where it would open the
         * windows of the application) : set {@code %test.quarkus.desktop.awt.startup-event.enabled=true} for user
         * interface tests. In tests, {@code quarkus.arc.test.disable-application-lifecycle-observers=true} disables it
         * too.
         */
        Optional<Boolean> enabled();

        /**
         * When the event is fired : {@code auto} once the application started (after the {@code StartupEvent}
         * observers, before a {@code @QuarkusMain} runs), {@code manual} when the application calls
         * {@code DesktopLifecycle.start()}.
         */
        @WithDefault("auto")
        Mode mode();
    }

    /**
     * When the {@code DesktopStartupEvent} is fired.
     */
    enum Mode {
        /**
         * Once the application started.
         */
        AUTO,
        /**
         * When the application calls {@code DesktopLifecycle.start()}.
         */
        MANUAL
    }
}
