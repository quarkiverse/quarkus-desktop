package io.quarkiverse.desktop.swt.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.OpenRequest;
import io.quarkiverse.desktop.swt.OpenRequest.Source;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.core.StartContext;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;

/**
 * Run time support of the user interface, and of the native libraries of SWT in native executables and snaps : what the
 * services of the extension do when the application starts and stops.
 */
public final class DesktopSwtSupport {

    private static final Logger LOGGER = Logger.getLogger(DesktopSwtSupport.class);

    /**
     * The system property where SWT looks for its native libraries before extracting them.
     */
    static final String SWT_LIBRARY_PATH = "swt.library.path";

    /**
     * The directory of the native executable when {@link #useLibrariesNextToExecutable} set {@code swt.library.path} to
     * it, {@code null} otherwise : the hint of a failed start does not take these libraries for a cache.
     */
    static volatile String librariesNextToExecutable;

    /**
     * The directory of a snap that {@link #useSnapLibraryPath} gave to {@code swt.library.path}, {@code null}
     * otherwise : the libraries next to the native executable take precedence over it.
     */
    static String snapLibraryPath;

    private DesktopSwtSupport() {
    }

    /**
     * Configures the user interface ({@code SwtLifecycle.run()}, {@code UiThread}...), before the {@code StartupEvent}
     * observers : the tasks they queue for the user interface thread run once it started. The service stops the user
     * interface after the {@code ShutdownEvent} observers, when the start fails before the user interface started : the
     * queued tasks are rejected. In a snap, it first chooses where SWT extracts its native libraries
     * ({@link #useSnapLibraryPath}), before the observers, which may load them.
     *
     * @param context the start context of the service
     * @param beans the CDI container
     * @param config the run time configuration of the extension
     * @param lifecycleObserversDisabled {@code quarkus.arc.test.disable-application-lifecycle-observers}
     * @param applicationName the default name of the application : the name of its packages
     *        ({@code quarkus.desktop.packaging.name}) with the Desktop Packaging extension, else
     *        {@code quarkus.application.name}
     * @param applicationVersion {@code quarkus.application.version}
     * @param identifier the identifier of the application for {@code quarkus.desktop.swt.single-instance} : the
     *        identifier of its packages, else {@code quarkus.application.name}, {@code null} when it has none
     * @param openRequests whether the application observes {@code OpenRequest}
     * @param extensions the extensions of the files that the application opens (Desktop Packaging extension)
     * @param schemes the URL schemes that the application opens (Desktop Packaging extension)
     */
    public static void configureUserInterface(StartContext context, BeanContainer beans, DesktopSwtRuntimeConfig config,
            LaunchMode launchMode, boolean lifecycleObserversDisabled, String applicationName,
            String applicationVersion, String identifier, boolean openRequests, List<String> extensions,
            List<String> schemes) {
        useSnapLibraryPath(System.getenv());
        SwtUi ui = beans.beanInstance(SwtUi.class);
        boolean enabled = config.startupEvent().enabled().orElse(launchMode != LaunchMode.TEST);
        if (!enabled || launchMode == LaunchMode.TEST && lifecycleObserversDisabled) {
            LOGGER.debug("The user interface is disabled");
            ui.disable(launchMode, launchMode == LaunchMode.TEST
                    ? "The user interface does not run in tests (quarkus.desktop.swt.startup-event.enabled)"
                    : "The user interface is disabled (quarkus.desktop.swt.startup-event.enabled)");
            return;
        }
        SingleInstance.Result instance = singleInstance(ui, config, launchMode, identifier, openRequests, extensions,
                schemes);
        if (instance != null && instance.forwarded()) {
            ui.disable(launchMode, "The application runs already : this launch gave its files and URIs to it, and stops");
            Quarkus.asyncExit(0);
            return;
        }
        if (openRequests) {
            ui.observeOpenRequests(new OpenArguments(extensions, schemes));
        }
        // a main runs the application (Quarkus.run, @QuarkusMainTest), not a @QuarkusTest
        boolean mainRuns = ApplicationLifecycleManager.getCurrentApplication() != null;
        ui.configure(launchMode, mainRuns, config.exitOnLastShellClosed() && launchMode != LaunchMode.TEST,
                config.applicationName().or(() -> Optional.ofNullable(applicationName)),
                Optional.ofNullable(applicationVersion));
        // when the start fails before the user interface service : the queued tasks are rejected. One stop handler per
        // service : the one of the single instance too, which stops listening once the user interface stopped
        SingleInstance running = instance != null ? instance.instance() : null;
        context.onStop(() -> {
            try {
                ui.stop();
            } finally {
                if (running != null) {
                    running.close();
                }
            }
        });
    }

    /**
     * One instance of the application per user ({@code quarkus.desktop.swt.single-instance}) : this launch becomes the
     * running instance, which gives the requests of the next launches to the user interface ({@code OpenRequest} of
     * source {@code ANOTHER_LAUNCH}) until the application stops, or forwards its files and URIs to the running
     * instance. Only in production : dev mode restarts the application in the same JVM, and would take the launches of
     * the installed application, and the tests start many applications.
     *
     * @return the result of the single instance, {@code null} when the application does not run once per user : whether
     *         this launch forwarded its request to the running instance (it stops), else the instance that it runs,
     *         which the caller closes when the application stops
     */
    private static SingleInstance.Result singleInstance(SwtUi ui, DesktopSwtRuntimeConfig config,
            LaunchMode launchMode, String identifier, boolean openRequests, List<String> extensions,
            List<String> schemes) {
        if (!config.singleInstance()) {
            return null;
        }
        if (launchMode != LaunchMode.NORMAL) {
            LOGGER.debugf("quarkus.desktop.swt.single-instance is ignored in %s mode", launchMode.getDefaultProfile());
            return null;
        }
        if (identifier == null || identifier.isBlank()) {
            LOGGER.warn("quarkus.desktop.swt.single-instance needs the name of the application (quarkus.application.name"
                    + " or the identifier of its packages) : another instance may run");
            return null;
        }
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
        }
        return result;
    }

    /**
     * Once the application started (after the {@code StartupEvent} observers) : the service ends the user interface when
     * the application stops, before the {@code ShutdownEvent} observers, and in tests, runs it on a thread of the
     * extension.
     *
     * @param context the start context of the service
     * @param beans the CDI container
     * @param runInTests whether the application has a user interface and runs in a test
     */
    public static void startUserInterface(StartContext context, BeanContainer beans, boolean runInTests) {
        SwtUi ui = beans.beanInstance(SwtUi.class);
        context.onStop(ui::stop);
        if (runInTests) {
            runUserInterfaceInTests(ui);
        }
    }

    /**
     * Runs the user interface on a thread of the extension, once the application started : in the tests where no main
     * runs it ({@code @QuarkusTest} starts the application without {@code Quarkus.run}, a {@code @QuarkusMainTest} runs
     * the main). Not on macOS, where the {@code Display} needs the first thread of the process : the user interface is
     * disabled there.
     */
    private static void runUserInterfaceInTests(SwtUi ui) {
        if (ApplicationLifecycleManager.getCurrentApplication() != null) {
            LOGGER.debug("The main of the application runs the user interface");
            return;
        }
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
     * libraries from the directory of the executable ({@code swt.library.path}), unless the property is set, other than
     * to the directory of a snap ({@link #useSnapLibraryPath}). When the libraries are missing there, SWT would extract
     * them into that directory : the property is not set to it then. SWT looks for them in {@code java.library.path} and
     * in {@code ~/.swt/lib/<os>/<arch>}, or in the directory of {@link #useSnapLibraryPath} in a strictly confined snap
     * (this method applies it first), and fails with a list of the places it looked into.
     *
     * @param library the file name of the main native library of SWT ({@code swt-win32-4974r10.dll}...)
     */
    public static void useLibrariesNextToExecutable(String library) {
        useLibrariesNextToExecutable(library, System.getenv());
    }

    /**
     * {@link #useLibrariesNextToExecutable(String)} in an environment.
     *
     * @param library the file name of the main native library of SWT
     * @param environment the environment variables of the process
     */
    static synchronized void useLibrariesNextToExecutable(String library, Map<String, String> environment) {
        String libraryPath = System.getProperty(SWT_LIBRARY_PATH);
        // the directory of a snap is for the libraries that SWT extracts, which this native executable does not have
        if (libraryPath != null && !libraryPath.equals(snapLibraryPath)) {
            return;
        }
        Optional<Path> directory = ProcessHandle.current().info().command().map(Path::of).map(Path::toAbsolutePath)
                .map(Path::getParent);
        if (directory.isPresent() && Files.isRegularFile(directory.get().resolve(library))) {
            System.setProperty(SWT_LIBRARY_PATH, directory.get().toString());
            librariesNextToExecutable = directory.get().toString();
        } else {
            // the directory of a snap, where SWT looks for them : this service runs before the one of the user interface,
            // which applies it too
            useSnapLibraryPath(environment);
            LOGGER.warn(missingLibraryMessage(library, directory.map(Path::toString).orElse("unknown"),
                    System.getProperty(SWT_LIBRARY_PATH), System.getProperty("os.name", ""),
                    System.getProperty("os.arch", "")));
        }
    }

    /**
     * The warning of a main native library of SWT that is missing next to the native executable : the places where SWT
     * looks for it, in its order.
     *
     * @param library the file name of the library
     * @param directory the directory of the executable
     * @param libraryPath {@code swt.library.path}, the directory of a strictly confined snap
     *        ({@link #useSnapLibraryPath}) : {@code null} when it is not set
     * @param osName {@code os.name}
     * @param osArch {@code os.arch}
     */
    static String missingLibraryMessage(String library, String directory, String libraryPath, String osName,
            String osArch) {
        String places = libraryPath != null ? libraryPath + " (swt.library.path) and in java.library.path"
                : "java.library.path and in ~/.swt/lib/" + SwtUi.swtOs(osName) + "/" + SwtUi.swtArch(osArch);
        return "The native library " + library + " of SWT is not next to the native executable (" + directory
                + ") : SWT looks for it in " + places;
    }

    /**
     * In a strictly confined snap, SWT extracts its native libraries into
     * {@code $SNAP_USER_COMMON/.swt/lib/<os>/<arch>} ({@code swt.library.path}), unless the property is set : the
     * {@code home} interface of the snap denies the hidden directories of the home directory of the user, such as
     * {@code ~/.swt}, where SWT extracts them otherwise. The directory is created : SWT does not create the directory of
     * {@code swt.library.path}.
     *
     * @param environment the environment variables of the process
     */
    static synchronized void useSnapLibraryPath(Map<String, String> environment) {
        if (System.getProperty(SWT_LIBRARY_PATH) != null) {
            return;
        }
        Optional<Path> directory = snapLibraryPath(environment, System.getProperty("user.home", ""),
                System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        if (directory.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(directory.get());
        } catch (IOException | RuntimeException e) {
            LOGGER.warnf("Unable to create %s, where SWT extracts its native libraries in a snap : %s", directory.get(),
                    e.toString());
            return;
        }
        snapLibraryPath = directory.get().toString();
        System.setProperty(SWT_LIBRARY_PATH, snapLibraryPath);
        LOGGER.debugf("SWT extracts its native libraries into %s (swt.library.path)", snapLibraryPath);
    }

    /**
     * The directory where SWT extracts its native libraries in a strictly confined snap :
     * {@code $SNAP_USER_COMMON/.swt/lib/<os>/<arch>}, rather than in the home directory of the user
     * ({@code user.home}, which the JDK takes from the user database). snapd gives such a snap a home directory of its
     * own ({@code HOME}) and the directory {@code SNAP_USER_COMMON}, which its revisions share. Empty elsewhere :
     * outside a snap, in a classic snap, whose {@code HOME} is the home directory of the user, and in the applications
     * that a classic snap starts, which inherit its environment variables.
     *
     * @param environment the environment variables of the process
     * @param userHome {@code user.home}
     * @param osName {@code os.name}
     * @param osArch {@code os.arch}
     */
    static Optional<Path> snapLibraryPath(Map<String, String> environment, String userHome, String osName,
            String osArch) {
        String common = environment.get("SNAP_USER_COMMON");
        String home = environment.get("HOME");
        if (common == null || common.isBlank() || home == null || home.isBlank()
                || Path.of(home).equals(Path.of(userHome))) {
            return Optional.empty();
        }
        return Optional.of(Path.of(common, ".swt", "lib", SwtUi.swtOs(osName), SwtUi.swtArch(osArch)));
    }
}
