package io.quarkiverse.desktop.swt.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Run time support of the user interface, and of the native libraries of SWT in native executables.
 */
@Recorder
public class DesktopSwtRecorder {

    private static final Logger LOGGER = Logger.getLogger(DesktopSwtRecorder.class);

    /**
     * The system property where SWT looks for its native libraries before extracting them.
     */
    static final String SWT_LIBRARY_PATH = "swt.library.path";

    /**
     * The directory of the native executable when {@link #useLibrariesNextToExecutable} set {@code swt.library.path} to
     * it, {@code null} otherwise : the hint of a failed start does not take these libraries for a cache.
     */
    static volatile String librariesNextToExecutable;

    private final RuntimeValue<DesktopSwtRuntimeConfig> config;

    public DesktopSwtRecorder(RuntimeValue<DesktopSwtRuntimeConfig> config) {
        this.config = config;
    }

    /**
     * Configures the user interface ({@code SwtLifecycle.run()}, {@code UiThread}...), before the {@code StartupEvent}
     * observers : the tasks they queue for the user interface thread run once it started.
     *
     * @param lifecycleObserversDisabled {@code quarkus.arc.test.disable-application-lifecycle-observers}
     * @param applicationName {@code quarkus.application.name}
     * @param applicationVersion {@code quarkus.application.version}
     */
    public void configureUserInterface(ShutdownContext shutdown, LaunchMode launchMode,
            boolean lifecycleObserversDisabled, String applicationName, String applicationVersion) {
        DesktopSwtRuntimeConfig config = this.config.getValue();
        SwtUi ui = Arc.container().instance(SwtUi.class).get();
        boolean enabled = config.startupEvent().enabled().orElse(launchMode != LaunchMode.TEST);
        if (!enabled || launchMode == LaunchMode.TEST && lifecycleObserversDisabled) {
            LOGGER.debug("The user interface is disabled");
            ui.disable(launchMode, launchMode == LaunchMode.TEST
                    ? "The user interface does not run in tests (quarkus.desktop.swt.startup-event.enabled)"
                    : "The user interface is disabled (quarkus.desktop.swt.startup-event.enabled)");
            return;
        }
        // a main runs the application (Quarkus.run, @QuarkusMainTest), not a @QuarkusTest
        boolean mainRuns = ApplicationLifecycleManager.getCurrentApplication() != null;
        ui.configure(launchMode, mainRuns, config.exitOnLastShellClosed() && launchMode != LaunchMode.TEST,
                config.applicationName().or(() -> Optional.ofNullable(applicationName)),
                Optional.ofNullable(applicationVersion));
        // when the start fails before stopUserInterfaceFirst : the queued tasks are rejected
        shutdown.addShutdownTask(ui::stop);
    }

    /**
     * Ends the user interface when the application stops, before the {@code ShutdownEvent} observers : registered once
     * the application started, after the task firing {@code ShutdownEvent} (the shutdown tasks run in the reverse order).
     */
    public void stopUserInterfaceFirst(ShutdownContext shutdown) {
        SwtUi ui = Arc.container().instance(SwtUi.class).get();
        shutdown.addShutdownTask(ui::stop);
    }

    /**
     * Runs the user interface on a thread of the extension, once the application started : in the tests where no main
     * runs it ({@code @QuarkusTest} starts the application without {@code Quarkus.run}, a {@code @QuarkusMainTest} runs
     * the main). Not on macOS, where the {@code Display} needs the first thread of the process : the user interface is
     * disabled there.
     */
    public void runUserInterfaceInTests() {
        if (ApplicationLifecycleManager.getCurrentApplication() != null) {
            LOGGER.debug("The main of the application runs the user interface");
            return;
        }
        SwtUi ui = Arc.container().instance(SwtUi.class).get();
        if (!ui.isReady()) {
            // disabled (the default in tests), or already stopping
            return;
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            LOGGER.warn("The user interface of the tests does not run on macOS, where SWT needs the first thread of the"
                    + " process : @QuarkusTest tests run on another thread (use a @QuarkusMainTest, with"
                    + " -XstartOnFirstThread)");
            ui.disable(LaunchMode.TEST, "The user interface of @QuarkusTest tests does not run on macOS, where SWT needs"
                    + " the first thread of the process");
            return;
        }
        ui.runOnOwnThread();
    }

    /**
     * Native executables built with {@code quarkus.desktop.swt.native-libraries=next-to-executable} : SWT loads its native
     * libraries from the directory of the executable ({@code swt.library.path}), unless the property is set. When the
     * libraries are missing there, SWT would extract them into that directory : the property is not set then, and SWT
     * fails with a list of the places it looked into.
     *
     * @param library the file name of the main native library of SWT ({@code swt-win32-4974r10.dll}...)
     */
    public void useLibrariesNextToExecutable(String library) {
        if (System.getProperty(SWT_LIBRARY_PATH) != null) {
            return;
        }
        Optional<Path> directory = ProcessHandle.current().info().command().map(Path::of).map(Path::toAbsolutePath)
                .map(Path::getParent);
        if (directory.isPresent() && Files.isRegularFile(directory.get().resolve(library))) {
            System.setProperty(SWT_LIBRARY_PATH, directory.get().toString());
            librariesNextToExecutable = directory.get().toString();
        } else {
            LOGGER.warnf("The native library %s of SWT is not next to the native executable (%s) : SWT looks for it in"
                    + " java.library.path and in ~/.swt", library, directory.map(Path::toString).orElse("unknown"));
        }
    }
}
