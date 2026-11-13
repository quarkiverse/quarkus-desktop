package io.quarkiverse.desktop.awt.runtime;

import java.awt.GraphicsEnvironment;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.OpenRequest;
import io.quarkiverse.desktop.awt.OpenRequest.Source;
import io.quarkiverse.desktop.awt.runtime.DesktopAwtRuntimeConfig.Mode;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.core.StartContext;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;

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
     * @param desktopEvents the class names of the {@code java.awt.Desktop} events that the application observes (and
     *        {@code OpenRequest})
     * @param extensions the extensions of the files that the application opens (Desktop Packaging extension)
     * @param schemes the URL schemes that the application opens (Desktop Packaging extension)
     */
    public static void startUserInterface(StartContext context, BeanContainer beans, DesktopAwtRuntimeConfig config,
            UncaughtExceptionLogging uncaughtExceptions, LaunchMode launchMode, boolean lifecycleObserversDisabled,
            boolean quarkusFx, boolean auxiliary, List<String> desktopEvents, List<String> extensions,
            List<String> schemes) {
        DesktopUi ui = beans.beanInstance(DesktopUi.class);
        if (ui.isForwarded()) {
            return;
        }
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
        OpenArguments openArguments = desktopEvents.contains(OpenRequest.class.getName())
                ? new OpenArguments(extensions, schemes)
                : null;
        ui.enable(launchMode, config.exitOnLastWindowClosed() && launchMode != LaunchMode.TEST && !quarkusFx,
                !quarkusFx && !auxiliary, desktopEvents, openArguments);
        context.onStop(ui::stop);
        if (auto) {
            ui.start();
        }
    }

    /**
     * One instance of the application per user ({@code quarkus.desktop.awt.single-instance}), before the
     * {@code StartupEvent} observers : this launch becomes the running instance, which gives the requests of the next
     * launches to the user interface ({@code OpenRequest} of source {@code ANOTHER_LAUNCH}), or forwards its files and
     * URIs to the running instance and stops ({@code Quarkus.asyncExit(0)}) without starting its user interface. Only in
     * production : dev mode restarts the application in the same JVM, and would take the launches of the installed
     * application, and the tests start many applications.
     *
     * @param context the start context of the service, which stops listening when the application stops
     * @param beans the CDI container
     * @param config the run time configuration of the extension
     * @param identifier the identifier of the application, {@code null} when it has none
     * @param openRequests whether the application observes {@code OpenRequest} : the files and URIs of the program
     *        arguments are forwarded
     * @param extensions the extensions of the files that the application opens
     * @param schemes the URL schemes that the application opens
     */
    public static void singleInstance(StartContext context, BeanContainer beans, DesktopAwtRuntimeConfig config,
            LaunchMode launchMode, String identifier, boolean openRequests, List<String> extensions,
            List<String> schemes) {
        if (!config.singleInstance()) {
            return;
        }
        if (launchMode != LaunchMode.NORMAL) {
            LOGGER.debugf("quarkus.desktop.awt.single-instance is ignored in %s mode", launchMode.getDefaultProfile());
            return;
        }
        if (identifier == null || identifier.isBlank()) {
            LOGGER.warn("quarkus.desktop.awt.single-instance needs the name of the application (quarkus.application.name"
                    + " or the identifier of its packages) : another instance may run");
            return;
        }
        DesktopUi ui = beans.beanInstance(DesktopUi.class);
        OpenRequest request = openRequests
                ? new OpenArguments(extensions, schemes).request(ui.arguments(), Source.ANOTHER_LAUNCH)
                : new OpenRequest(List.of(), List.of(), Source.ANOTHER_LAUNCH);
        SingleInstance.Result result = SingleInstance.start(
                SingleInstance.directory(System.getenv(), System.getProperty("os.name", ""),
                        System.getProperty("java.io.tmpdir", ".")),
                SingleInstance.name(identifier, System.getProperty("user.name", "")), request, ui::open,
                SingleInstance.TIMEOUT);
        if (result.forwarded()) {
            LOGGER.debugf("The application runs already : %s is forwarded to it, and this launch stops", request);
            ui.forwarded();
            Quarkus.asyncExit(0);
        } else if (result.instance() != null) {
            context.onStop(result.instance()::close);
        }
    }
}
