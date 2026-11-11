package io.quarkiverse.desktop.awt.runtime;

import java.awt.GraphicsEnvironment;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.runtime.DesktopAwtRuntimeConfig.Mode;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.core.StartContext;
import io.quarkus.runtime.LaunchMode;

/**
 * Run time support of the user interface of an application observing {@code DesktopStartupEvent}.
 */
public final class DesktopLifecycleSupport {

    private static final Logger LOGGER = Logger.getLogger(DesktopLifecycleSupport.class);

    private DesktopLifecycleSupport() {
    }

    /**
     * Starts the user interface once the application started (after the {@code StartupEvent} observers) : fires
     * {@code DesktopStartupEvent} on the event dispatch thread, or lets the application fire it
     * ({@code DesktopLifecycle.start()}). The service stops it before the {@code ShutdownEvent} observers.
     *
     * @param context the start context of the service
     * @param beans the CDI container
     * @param config the run time configuration of the extension
     * @param uncaughtExceptions the logging of the uncaught exceptions, which outlives the other services of the
     *        extension
     * @param lifecycleObserversDisabled {@code quarkus.arc.test.disable-application-lifecycle-observers}
     * @param quarkusFx whether Quarkus FX is present (JavaFX then decides when the application stops, and handles the
     *        events of the application menu)
     * @param auxiliary whether it is the test application of continuous testing, which runs next to the dev mode
     *        application : the handlers of {@code java.awt.Desktop} are those of the dev mode application
     * @param desktopEvents the class names of the {@code java.awt.Desktop} events that the application observes
     */
    public static void startUserInterface(StartContext context, BeanContainer beans, DesktopAwtRuntimeConfig config,
            UncaughtExceptionLogging uncaughtExceptions, LaunchMode launchMode, boolean lifecycleObserversDisabled,
            boolean quarkusFx, boolean auxiliary, List<String> desktopEvents) {
        DesktopUi ui = beans.beanInstance(DesktopUi.class);
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
        // removed after the other stop handlers, whose exceptions on the event dispatch thread are logged too
        uncaughtExceptions.install(launchMode != LaunchMode.NORMAL);
        ui.enable(launchMode, config.exitOnLastWindowClosed() && launchMode != LaunchMode.TEST && !quarkusFx,
                !quarkusFx && !auxiliary, desktopEvents);
        context.onStop(ui::stop);
        if (auto) {
            ui.start();
        }
    }
}
