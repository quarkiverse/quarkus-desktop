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
     * Whether the application runs once per user : a second launch, while the application runs, gives its files and
     * URIs to the running instance (an {@code io.quarkiverse.desktop.swt.OpenRequest} of source
     * {@code ANOTHER_LAUNCH}, maybe without files nor URIs, fired on its user interface thread) and exits with the code
     * 0 before its user interface starts (its {@code StartupEvent} observers still run). The running instance decides
     * what to do : open them, bring its shell to front. Only in production : not in dev mode, whose restarts would take
     * the launches of the installed application, nor in tests. When the running instance cannot be reached within a few
     * seconds, the second launch starts as usual.
     */
    @WithDefault("false")
    boolean singleInstance();

    /**
     * The name of the application, given to SWT ({@code Display.setAppName}) before the {@code Display} is created : the
     * name of the application menu and of the Dock on macOS, the program name and the {@code WM_CLASS} of the windows on
     * Linux (matched with the {@code .desktop} file of the application), the application identifier of the taskbar on
     * Windows. Defaults to the name of the packages of the Desktop Packaging extension
     * ({@code quarkus.desktop.packaging.name}) when it is set, else to {@code quarkus.application.name}. The packages
     * of that extension give the value of the build to their shortcuts and desktop entry : one set at run time only is
     * not theirs.
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
