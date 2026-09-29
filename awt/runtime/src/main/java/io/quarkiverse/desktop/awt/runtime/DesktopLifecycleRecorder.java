package io.quarkiverse.desktop.awt.runtime;

import java.awt.GraphicsEnvironment;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.runtime.DesktopAwtRuntimeConfig.Mode;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;
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
     * @param desktopEvents the class names of the {@code java.awt.Desktop} events that the application observes
     */
    public void startUserInterface(ShutdownContext shutdown, LaunchMode launchMode, boolean lifecycleObserversDisabled,
            boolean quarkusFx, List<String> desktopEvents) {
        DesktopAwtRuntimeConfig config = this.config.getValue();
        boolean enabled = config.startupEvent().enabled().orElse(launchMode != LaunchMode.TEST);
        if (!enabled || launchMode == LaunchMode.TEST && lifecycleObserversDisabled) {
            LOGGER.debug("DesktopStartupEvent is disabled");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            if (launchMode == LaunchMode.NORMAL) {
                LOGGER.error("The application observes DesktopStartupEvent, but the JVM is headless (no display, or"
                        + " java.awt.headless=true) : the application stops");
                Quarkus.asyncExit(1);
            } else {
                LOGGER.warn("The application observes DesktopStartupEvent, but the JVM is headless (no display, or"
                        + " java.awt.headless=true) : the event is not fired");
            }
            return;
        }
        UncaughtExceptionLogger uncaughtExceptions = UncaughtExceptionLogger.install();
        if (uncaughtExceptions != null && launchMode != LaunchMode.NORMAL) {
            // after the other shutdown tasks, whose exceptions on the event dispatch thread are logged too
            shutdown.addLastShutdownTask(uncaughtExceptions::remove);
        }
        DesktopUi ui = Arc.container().instance(DesktopUi.class).get();
        ui.enable(launchMode, config.exitOnLastWindowClosed() && launchMode != LaunchMode.TEST && !quarkusFx, !quarkusFx,
                desktopEvents);
        shutdown.addShutdownTask(ui::stop);
        if (config.startupEvent().mode() == Mode.AUTO) {
            ui.start();
        }
    }
}
