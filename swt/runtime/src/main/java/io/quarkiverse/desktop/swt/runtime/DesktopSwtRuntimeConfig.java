package io.quarkiverse.desktop.swt.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Desktop SWT run time configuration : the user interface of the application (an application observing
 * {@code io.quarkiverse.desktop.swt.SwtStartupEvent}, or calling {@code SwtLifecycle.run()}), in JVM mode and in native
 * executables.
 */
@ConfigMapping(prefix = "quarkus.desktop.swt")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DesktopSwtRuntimeConfig {

    /**
     * The {@code SwtStartupEvent} fired on the user interface thread to create the user interface.
     */
    StartupEvent startupEvent();

    /**
     * Whether the application stops ({@code Quarkus.asyncExit()}) when its last visible shell is closed or hidden, once a
     * first shell opened. Never in tests. Disable it for an application that stays alive without shells (a tray icon,
     * the macOS convention), or that closes a shell before opening the next one (a splash screen closed before the main
     * shell opens).
     */
    @WithDefault("true")
    boolean exitOnLastShellClosed();

    /**
     * The name of the application, given to SWT ({@code Display.setAppName}) before the {@code Display} is created : the
     * name of the application menu and of the Dock on macOS, the program name and the {@code WM_CLASS} of the windows on
     * Linux (matched with the {@code .desktop} file of the application), the application identifier of the taskbar on
     * Windows. Defaults to {@code quarkus.application.name}.
     */
    Optional<String> applicationName();

    /**
     * The {@code SwtStartupEvent} fired on the user interface thread to create the user interface.
     */
    interface StartupEvent {

        /**
         * Whether the user interface runs. By default it does, except in tests ({@code @QuarkusTest} and
         * {@code @QuarkusMainTest}, where it would open the shells of the application) : set
         * {@code %test.quarkus.desktop.swt.startup-event.enabled=true} for user interface tests. A {@code @QuarkusTest}
         * then runs it on a thread of the extension (except on macOS, where it needs the first thread of the process), a
         * {@code @QuarkusMainTest} on the thread running the main, as in production. In tests,
         * {@code quarkus.arc.test.disable-application-lifecycle-observers=true} disables it too.
         * When it does not run, {@code SwtLifecycle.run()} returns at once (an application without {@code @QuarkusMain}
         * then stops at once), and the tasks for the user interface thread are rejected.
         */
        Optional<Boolean> enabled();
    }
}
