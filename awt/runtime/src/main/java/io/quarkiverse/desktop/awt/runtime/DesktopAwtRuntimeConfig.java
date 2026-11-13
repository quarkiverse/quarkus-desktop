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
     * Whether the application runs once per user : a second launch, while the application runs, gives its files and
     * URIs to the running instance (an {@code io.quarkiverse.desktop.awt.OpenRequest} of source
     * {@code ANOTHER_LAUNCH}, maybe without files nor URIs, fired on its event dispatch thread) and exits with the code
     * 0 before its user interface starts. The running instance decides what to do : open them, bring its window to
     * front. Only for an application observing {@code DesktopStartupEvent}, and only in production : not in dev mode,
     * whose restarts would take the launches of the installed application, nor in tests. When the running instance
     * cannot be reached within a few seconds, the second launch starts as usual.
     */
    @WithDefault("false")
    boolean singleInstance();

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
         * When the event is fired : {@code auto} during the startup of the application (queued on the event dispatch
         * thread after the {@code StartupEvent} observers : its observers may run while a {@code @QuarkusMain} runs),
         * {@code manual} when the application calls {@code DesktopLifecycle.start()} (from its {@code @QuarkusMain} once
         * its work before the user interface is done, or from a {@code StartupEvent} observer).
         */
        @WithDefault("auto")
        Mode mode();
    }

    /**
     * When the {@code DesktopStartupEvent} is fired.
     */
    enum Mode {
        /**
         * During the startup of the application, after the {@code StartupEvent} observers.
         */
        AUTO,
        /**
         * When the application calls {@code DesktopLifecycle.start()}.
         */
        MANUAL
    }
}
