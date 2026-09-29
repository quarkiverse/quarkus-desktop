package io.quarkiverse.desktop.awt.runtime;

import java.awt.GraphicsEnvironment;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.runtime.DesktopAwtRuntimeConfig.Mode;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Run time support of the user interface of an application observing {@code DesktopStartupEvent}.
 */
@Recorder
public class DesktopLifecycleRecorder {

    private static final Logger LOGGER = Logger.getLogger(DesktopLifecycleRecorder.class);

    private final RuntimeValue<DesktopAwtRuntimeConfig> config;

    public DesktopLifecycleRecorder(RuntimeValue<DesktopAwtRuntimeConfig> config) {
        this.config = config;
    }

    /**
     * Starts the user interface once the application started (after the {@code StartupEvent} observers) : fires
     * {@code DesktopStartupEvent} on the event dispatch thread, or lets the application fire it
     * ({@code DesktopLifecycle.start()}).
     *
     * @param lifecycleObserversDisabled {@code quarkus.arc.test.disable-application-lifecycle-observers}
     * @param quarkusFx whether Quarkus FX is present (JavaFX then decides when the application stops, and handles the
     *        events of the application menu)
     * @param auxiliary whether it is the test application of continuous testing, which runs next to the dev mode
     *        application : the handlers of {@code java.awt.Desktop} are those of the dev mode application
     * @param desktopEvents the class names of the {@code java.awt.Desktop} events that the application observes
     */
    public void startUserInterface(ShutdownContext shutdown, LaunchMode launchMode, boolean lifecycleObserversDisabled,
            boolean quarkusFx, boolean auxiliary, List<String> desktopEvents) {
        DesktopAwtRuntimeConfig config = this.config.getValue();
        DesktopUi ui = Arc.container().instance(DesktopUi.class).get();
        boolean enabled = config.startupEvent().enabled().orElse(launchMode != LaunchMode.TEST);
        if (!enabled || launchMode == LaunchMode.TEST && lifecycleObserversDisabled) {
            LOGGER.debug("DesktopStartupEvent is disabled");
            ui.disable();
            return;
        }
        boolean auto = config.startupEvent().mode() == Mode.AUTO;
        if (GraphicsEnvironment.isHeadless()) {
            if (auto) {
                DesktopUi.headlessError(launchMode);
            } else {
                // the application may not start its user interface (a batch mode) : DesktopLifecycle.start() fails
                LOGGER.debug("The JVM is headless : DesktopLifecycle.start() stops the application");
                ui.headless(launchMode);
            }
            return;
        }
        UncaughtExceptionLogger uncaughtExceptions = UncaughtExceptionLogger.install();
        if (uncaughtExceptions != null && launchMode != LaunchMode.NORMAL) {
            // after the other shutdown tasks, whose exceptions on the event dispatch thread are logged too
            shutdown.addLastShutdownTask(uncaughtExceptions::remove);
        }
        ui.enable(launchMode, config.exitOnLastWindowClosed() && launchMode != LaunchMode.TEST && !quarkusFx,
                !quarkusFx && !auxiliary, desktopEvents);
        shutdown.addShutdownTask(ui::stop);
        if (auto) {
            ui.start();
        }
    }
}
