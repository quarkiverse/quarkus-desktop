package io.quarkiverse.desktop.swt.runtime;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.QuitRequest;
import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.ImageMode;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;

/**
 * The user interface of the application : creates the {@code Display}, fires {@link SwtStartupEvent} and runs the event
 * loop on the thread calling {@link #run()}, with the exit on last shell closed policy and the quit requests.
 * <p>
 * The static methods serve {@code UiThread}, {@code UiThreadExecutor} and {@code @RunOnUiThread} : they reach the user
 * interface of the current application, set by {@link #configure} when it starts.
 */
@Singleton
public class SwtUi implements SwtLifecycle {

    private static final Logger LOGGER = Logger.getLogger(SwtLifecycle.class);

    /**
     * The log category of the exceptions thrown by the listeners, the timers and the tasks on the user interface thread,
     * which the event loop logs and survives.
     */
    static final String UI_THREAD_CATEGORY = "io.quarkiverse.desktop.swt.ui-thread";

    private static final Logger UI_THREAD_LOGGER = Logger.getLogger(UI_THREAD_CATEGORY);

    /**
     * How long the shutdown of the application waits for the user interface thread to dispose the {@code Display}.
     */
    static final long STOP_TIMEOUT_MILLIS = 10_000L;

    /**
     * How many events and tasks the user interface thread runs at most once the shells are disposed, before it disposes
     * the {@code Display} : a task queuing itself again would run for ever.
     */
    static final int DRAIN_LIMIT = 10_000;

    /**
     * Run time system property set to {@code true} by the Desktop AWT extension once it keeps the first thread of a
     * macOS native executable in the Cocoa event loop, and runs the application on a new thread named {@code main}
     * ({@code MacMainThread.PARKED}, of that extension, which this extension does not depend on).
     */
    static final String MAIN_THREAD_PARKED = "io.quarkiverse.desktop.main-thread-parked";

    /**
     * Every SWT jar has this class : SWT reads the manifest of the jar it comes from before it loads its native
     * libraries ({@code org.eclipse.swt.internal.Library.isLoadable()}).
     */
    static final String SWT_LIBRARY_CLASS = "org/eclipse/swt/internal/Library.class";

    /**
     * The attributes of the manifest of an SWT jar that SWT compares with {@code os.name} and {@code os.arch}.
     */
    static final String SWT_OS = "SWT-OS";

    static final String SWT_ARCH = "SWT-Arch";

    /**
     * The start of the message of the errors of {@link #checkSwtJar(String, String)} : the hint of the other
     * {@code UnsatisfiedLinkError}s also names the native libraries that SWT extracted before.
     */
    static final String SWT_JAR_ERROR = "The SWT jar ";

    /**
     * A task for the user interface thread that is told when it will not run, once queued : the user interface stopped,
     * or never started.
     */
    public interface Rejectable extends Runnable {

        /**
         * The task will not run.
         */
        void reject(RejectedExecutionException cause);
    }

    /**
     * The user interface of the current application : the one of the application that started last (dev mode restarts
     * it).
     */
    private static volatile SwtUi current;

    @Inject
    Event<SwtStartupEvent> startupEvent;

    @Inject
    Event<QuitRequest> quitRequests;

    private enum State {
        /**
         * Not configured yet : the application did not start.
         */
        NEW,
        /**
         * The user interface is disabled (tests) : {@link #run()} returns at once, and the tasks are rejected.
         */
        DISABLED,
        /**
         * Configured, {@link #run()} not called yet : the tasks are queued.
         */
        READY,
        /**
         * {@link #run()} creates the {@code Display} and fires the event : the tasks are still queued.
         */
        STARTING,
        /**
         * The event loop runs.
         */
        RUNNING,
        /**
         * The user interface stopped, or could not start : the tasks are rejected.
         */
        STOPPED
    }

    /**
     * Guards the state, the queued tasks and the {@code Display} : a task is queued, or given to the {@code Display},
     * in the order of the calls.
     */
    private final Object lock = new Object();

    private State state = State.NEW;

    private final List<Runnable> pending = new ArrayList<>();

    private Display display;

    private volatile Thread uiThread;

    /**
     * Whether the user interface runs on a thread of the extension (tests) rather than on the thread calling
     * {@link #run()}.
     */
    private boolean ownThread;

    /**
     * Whether a main runs the application ({@code Quarkus.run}, a {@code @QuarkusMainTest}), rather than a
     * {@code @QuarkusTest}.
     */
    private boolean mainRuns;

    private String disabledReason;

    private volatile boolean stopRequested;

    private final CountDownLatch stopped = new CountDownLatch(1);

    /**
     * How long {@link #stop()} waits for the user interface thread : {@link #STOP_TIMEOUT_MILLIS}, shorter in tests.
     */
    long stopTimeoutMillis = STOP_TIMEOUT_MILLIS;

    /**
     * Whether a {@link #stop()} already waited for the user interface thread, or found it exiting the JVM : the next
     * one does not wait again.
     */
    private volatile boolean stopWaited;

    private LaunchMode launchMode = LaunchMode.NORMAL;

    private boolean exitOnLastShellClosed;

    private Optional<String> applicationName = Optional.empty();

    private Optional<String> applicationVersion = Optional.empty();

    // ---------------------------------------------------------------------------------------------- configuration

    /**
     * Enables {@link #run()}, and makes this user interface the current one.
     *
     * @param mainRuns whether a main runs the application, which calls {@link #run()} : the user interface then stops
     *        when the application exits ({@code Quarkus.asyncExit()})
     * @param applicationName the name of the application, given to {@code Display.setAppName} before the
     *        {@code Display} is created
     * @param applicationVersion given to {@code Display.setAppVersion}
     */
    void configure(LaunchMode launchMode, boolean mainRuns, boolean exitOnLastShellClosed,
            Optional<String> applicationName, Optional<String> applicationVersion) {
        synchronized (lock) {
            this.launchMode = launchMode;
            this.mainRuns = mainRuns;
            this.exitOnLastShellClosed = exitOnLastShellClosed;
            this.applicationName = applicationName;
            this.applicationVersion = applicationVersion;
            if (state == State.NEW) {
                state = State.READY;
            }
        }
        current = this;
        if (mainRuns) {
            // Waiting before the StartupEvent observers : the exit that they, or the main before run(), request stops the
            // user interface before it starts. The window left is the time the watcher takes to wake up.
            watchExit();
        }
    }

    /**
     * Whether the user interface is enabled and did not start yet.
     */
    boolean isReady() {
        synchronized (lock) {
            return state == State.READY;
        }
    }

    /**
     * The user interface does not run (tests) : {@link #run()} returns at once, and the tasks are rejected.
     *
     * @param reason the message of the {@code RejectedExecutionException} of the tasks
     */
    void disable(LaunchMode launchMode, String reason) {
        List<Runnable> rejected;
        synchronized (lock) {
            this.launchMode = launchMode;
            disabledReason = reason;
            state = State.DISABLED;
            rejected = drainPending();
        }
        current = this;
        reject(rejected, reason);
    }

    // ----------------------------------------------------------------------------------------------- user interface

    @Override
    public void run() {
        boolean onOwnThread = false;
        List<Runnable> rejected = null;
        synchronized (lock) {
            switch (state) {
                case NEW, READY -> {
                    if (stopRequested) {
                        state = State.STOPPED;
                        rejected = drainPending();
                    } else {
                        state = State.STARTING;
                        uiThread = Thread.currentThread();
                    }
                }
                case DISABLED -> {
                    LOGGER.debugf("The user interface does not start : %s", disabledReason);
                    return;
                }
                case STARTING, RUNNING -> {
                    if (!ownThread) {
                        throw new IllegalStateException(
                                "The user interface already runs in the thread " + uiThread.getName());
                    }
                    onOwnThread = true;
                }
                case STOPPED -> {
                    LOGGER.debug("The user interface already ran : the application is stopping");
                    return;
                }
            }
        }
        if (rejected != null) {
            stopped.countDown();
            LOGGER.debug("The application is stopping : the user interface does not start");
            reject(rejected, "The application stopped before the user interface ran");
            return;
        }
        if (onOwnThread) {
            // a @QuarkusTest : the user interface runs on the thread of the extension until the application stops
            awaitStopped();
            return;
        }
        runHere();
    }

    /**
     * Runs the user interface on a thread of its own : tests only (a {@code @QuarkusTest} runs no main), and never on
     * macOS, where the {@code Display} needs the first thread of the process.
     */
    void runOnOwnThread() {
        synchronized (lock) {
            if (state != State.READY) {
                return;
            }
            state = State.STARTING;
            ownThread = true;
            Thread thread = new Thread(this::runHere, "quarkus-desktop-swt-ui");
            thread.setDaemon(true);
            uiThread = thread;
            thread.start();
        }
    }

    private void awaitStopped() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runHere() {
        Display created = createDisplay();
        if (created == null) {
            return;
        }
        LastShellExitPolicy exitPolicy = null;
        try {
            created.setRuntimeExceptionHandler(e -> UI_THREAD_LOGGER.errorf(e, "Uncaught exception in the user"
                    + " interface thread %s", Thread.currentThread().getName()));
            created.setErrorHandler(e -> UI_THREAD_LOGGER.errorf(e, "Uncaught error in the user interface thread %s",
                    Thread.currentThread().getName()));
            created.addListener(SWT.Close, this::quitRequested);
            if (exitOnLastShellClosed) {
                exitPolicy = LastShellExitPolicy.install(created, Quarkus::asyncExit);
            }
            if (!stopRequested) {
                fire(created);
            }
            start(created);
            loop(created);
        } finally {
            if (exitPolicy != null) {
                try {
                    exitPolicy.remove();
                } catch (RuntimeException | Error e) {
                    UI_THREAD_LOGGER.error("Unable to remove the exit on last shell closed policy", e);
                }
            }
            dispose(created);
        }
    }

    /**
     * Gives the tasks queued before the start to the {@code Display} : after the {@code SwtStartupEvent} observers, before
     * the next tasks. Rejects them when the application is stopping (the observers may not have run) or when an observer
     * disposed the {@code Display}.
     */
    private void start(Display created) {
        List<Runnable> rejected;
        synchronized (lock) {
            if (!stopRequested && !created.isDisposed()) {
                state = State.RUNNING;
                for (Runnable task : pending) {
                    created.asyncExec(task);
                }
                pending.clear();
                return;
            }
            rejected = drainPending();
        }
        reject(rejected, "The application stopped before the user interface ran");
    }

    /**
     * Creates the {@code Display} on the current thread.
     *
     * @return the {@code Display}, or {@code null} when it cannot be created : the user interface is stopped
     */
    private Display createDisplay() {
        try {
            if (ImageMode.current() != ImageMode.NATIVE_RUN) {
                // before the first use of Display, whose static initializer loads the native libraries of SWT
                checkSwtJar(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
            }
            applicationName.filter(name -> !name.isBlank()).ifPresent(Display::setAppName);
            applicationVersion.filter(version -> !version.isBlank()).ifPresent(Display::setAppVersion);
            Display created = new Display();
            synchronized (lock) {
                display = created;
            }
            LOGGER.debugf("The user interface runs in the thread %s (SWT %s %d)", Thread.currentThread().getName(),
                    SWT.getPlatform(), SWT.getVersion());
            return created;
        } catch (Throwable e) {
            // Errors too : SWTError (no display), UnsatisfiedLinkError (the SWT library of another platform), and in a
            // native executable missing some metadata a LinkageError
            List<Runnable> rejected;
            synchronized (lock) {
                state = State.STOPPED;
                uiThread = null;
                rejected = drainPending();
            }
            stopped.countDown();
            reject(rejected, "The user interface could not start");
            String hint = hint(e);
            if (exitOnFailure()) {
                LOGGER.errorf(e, "Unable to create the SWT Display in the thread %s%s : the application stops",
                        Thread.currentThread().getName(), hint);
                Quarkus.asyncExit(1);
            } else {
                LOGGER.errorf(e, "Unable to create the SWT Display in the thread %s%s",
                        Thread.currentThread().getName(), hint);
            }
            return null;
        }
    }

    private static String hint(Throwable e) {
        return hint(e, System.getProperty("os.name", ""), ImageMode.current() == ImageMode.NATIVE_RUN,
                System.getProperty(MAIN_THREAD_PARKED), System.getProperty(DesktopSwtRecorder.SWT_LIBRARY_PATH));
    }

    static String hint(Throwable e, String osName, boolean nativeExecutable, String mainThreadParked) {
        return hint(e, osName, nativeExecutable, mainThreadParked, null);
    }

    /**
     * The hint of the error logged when the {@code Display} cannot be created.
     *
     * @param osName the name of the operating system ({@code os.name})
     * @param nativeExecutable whether the application runs as a native executable, where the options of the JVM do not
     *        apply
     * @param mainThreadParked the value of {@link #MAIN_THREAD_PARKED} : {@code true} when the Desktop AWT extension
     *        keeps the first thread of the native executable in the Cocoa event loop, {@code null} otherwise
     * @param libraryPath the value of {@code swt.library.path} : where SWT extracts its native libraries instead of
     *        {@code ~/.swt/lib}, {@code null} when it is not set
     */
    static String hint(Throwable e, String osName, boolean nativeExecutable, String mainThreadParked,
            String libraryPath) {
        if (e instanceof UnsatisfiedLinkError) {
            String osArch = System.getProperty("os.arch", "");
            String message = e.getMessage() == null ? "" : e.getMessage();
            String jar = "the SWT jar of the application must be the one of this operating system and architecture, "
                    + osName + " " + osArch + ", " + swtJar(osName, osArch);
            String cache = "delete " + (libraryPath != null && !libraryPath.isBlank()
                    ? "the SWT libraries of " + libraryPath + " (swt.library.path)"
                    : "~/.swt/lib/" + swtOs(osName) + "/" + swtArch(osArch))
                    + ", where an executable of another architecture may have left its libraries (SWT never replaces"
                    + " them)";
            String missing = missingSystemLibrary(message);
            if (missing != null && osName.toLowerCase().contains("linux")) {
                // a library that the native libraries of SWT link against : GTK 3 is not installed, mostly
                return " (SWT could not load its native libraries : " + missing + " is missing, on Linux SWT needs GTK 3"
                        + " and its libraries, libgtk-3-0t64 on Ubuntu 24.04 or gtk3 on Fedora, and a display)";
            }
            if (message.startsWith(SWT_JAR_ERROR) && message.contains(" has no ")) {
                // checkSwtJar : the manifest of the SWT jar is gone, not the jar
                return " (SWT could not load its native libraries : the manifest of the SWT jar is missing, use the"
                        + " default fast-jar packaging)";
            }
            if (message.startsWith(SWT_JAR_ERROR)) {
                // checkSwtJar : the SWT jar of another platform
                return " (SWT could not load its native libraries : " + jar + ")";
            }
            if (!nativeExecutable) {
                // checkSwtJar passed : the jar is right, but SWT extracts its native libraries once per version and
                // loads the ones it finds
                return " (SWT could not load its native libraries : " + cache + ")";
            }
            return " (SWT could not load its native libraries : " + jar + ", or " + cache + ")";
        }
        String os = osName.toLowerCase();
        if (os.contains("mac") && nativeExecutable && "true".equals(mainThreadParked)) {
            // the Desktop AWT extension parked the first thread, and the main of another extension (Picocli...) runs
            // the application on a new thread, named main too
            return " (on macOS, the Display is created on the first thread of the process : the Desktop AWT extension"
                    + " keeps that thread for the Cocoa event loop and runs the application on another thread, build"
                    + " the native executable with quarkus.desktop.awt.macos.park-main-thread=false, and"
                    + " SwtLifecycle.run() must run on the first thread of the process)";
        }
        if (os.contains("mac") && nativeExecutable) {
            // main runs on the first thread of a native executable, unless it is built with -H:+RunMainInNewThread,
            // which runs it on a new thread, named main too
            return " (on macOS, the Display is created on the first thread of the process : the native executable must"
                    + " not be built with -H:+RunMainInNewThread, which runs main on another thread, and"
                    + " SwtLifecycle.run() must run on the first thread of the process)";
        }
        if (os.contains("mac")) {
            return " (on macOS, the Display is created on the first thread of the process : start the JVM with"
                    + " -XstartOnFirstThread, run SwtLifecycle.run() on the main thread, and use the application, not"
                    + " the dev mode, which runs it on another thread)";
        }
        if (os.contains("linux")) {
            return " (on Linux, SWT needs GTK 3 and a display : X11, XWayland or Wayland, DISPLAY or WAYLAND_DISPLAY)";
        }
        return "";
    }

    /**
     * The system library that a native library of SWT could not load, from the message of the
     * {@code UnsatisfiedLinkError} of SWT : {@code libgtk-3.so.0} in
     * {@code .../libswt-pi3-gtk-4971r15.so: libgtk-3.so.0: cannot open shared object file: No such file or directory}.
     *
     * @return the name of the library, {@code null} when the message names none, or names a library of SWT
     */
    static String missingSystemLibrary(String message) {
        int end = message.indexOf(": cannot open shared object file");
        if (end < 0) {
            return null;
        }
        int start = message.lastIndexOf(": ", end - 1);
        String library = message.substring(start < 0 ? 0 : start + 2, end).trim();
        int slash = library.lastIndexOf('/');
        library = library.substring(slash + 1);
        return library.isEmpty() || library.startsWith("libswt") ? null : library;
    }

    /**
     * Checks the SWT jar before SWT does : when the jar is the one of another operating system or architecture
     * ({@link #isLoadable}), SWT exits the JVM ({@code System.exit(1)}) while the {@code Display} is created, with its
     * own message only. JVM mode only : in a native executable, whose {@code Library.class} is not in a jar, SWT loads
     * the native libraries of the executable, and fails with an {@code UnsatisfiedLinkError} when they are of another
     * platform.
     *
     * @param osName {@code os.name}
     * @param osArch {@code os.arch}
     * @throws UnsatisfiedLinkError when SWT would exit : the native libraries of SWT do not load, logged with the hint
     */
    static void checkSwtJar(String osName, String osArch) {
        // the manifest that SWT reads (Library.isLoadable())
        URL library = Display.class.getResource("/" + SWT_LIBRARY_CLASS);
        if (library == null || !library.getProtocol().equals("jar")) {
            // SWT does not check its jar either (a development environment of SWT)
            return;
        }
        Attributes attributes;
        URL jar;
        try {
            if (!(library.openConnection() instanceof JarURLConnection connection)) {
                return;
            }
            attributes = connection.getMainAttributes();
            jar = connection.getJarFileURL();
        } catch (IOException e) {
            // SWT cannot read it either : it exits with its own message
            return;
        }
        if (attributes != null) {
            checkSwtJar(jar, attributes, osName, osArch);
        }
    }

    /**
     * Checks the main attributes of the manifest of the SWT jar ({@link #checkSwtJar(String, String)}).
     *
     * @param jar the SWT jar
     * @throws UnsatisfiedLinkError when SWT would exit : the jar is the one of another operating system or
     *         architecture, or its manifest has no {@code SWT-OS} or no {@code SWT-Arch} attribute (an uber-jar keeps its
     *         own manifest only)
     */
    static void checkSwtJar(URL jar, Attributes attributes, String osName, String osArch) {
        if (isLoadable(attributes, osName, osArch)) {
            return;
        }
        String os = attributes.getValue(SWT_OS);
        String arch = attributes.getValue(SWT_ARCH);
        if (os == null || arch == null) {
            boolean neither = os == null && arch == null;
            String missing = neither ? SWT_OS + " and " + SWT_ARCH + " attributes"
                    : (os == null ? SWT_OS : SWT_ARCH) + " attribute";
            throw new UnsatisfiedLinkError(SWT_JAR_ERROR + jar + " has no " + missing + " in its manifest : SWT would"
                    + " exit the JVM (System.exit(1)) without " + (neither ? "them" : "it") + " (an uber-jar drops the"
                    + " manifests of its dependencies : use the default fast-jar packaging)");
        }
        throw new UnsatisfiedLinkError(SWT_JAR_ERROR + jar + " declares, in its manifest, " + SWT_OS + " " + os + " and "
                + SWT_ARCH + " " + arch + ", not " + swtOs(osName) + " and " + swtArch(osArch) + " : SWT would exit"
                + " the JVM (System.exit(1)) instead of loading its native libraries");
    }

    /**
     * Whether SWT loads its native libraries from its jar in JVM mode : {@code org.eclipse.swt.internal.Library}
     * compares the {@code SWT-OS} and {@code SWT-Arch} attributes of the manifest of the jar with the operating system
     * and the architecture of the JVM, as it names them, and exits the JVM when they differ
     * ({@code Platform.exitIfNotLoadable()}). It does not compare {@code SWT-WS}, which follows from the operating
     * system.
     *
     * @param attributes the main attributes of the manifest of the SWT jar
     * @param osName {@code os.name}
     * @param osArch {@code os.arch}
     */
    static boolean isLoadable(Attributes attributes, String osName, String osArch) {
        return swtOs(osName).equals(attributes.getValue(SWT_OS))
                && swtArch(osArch).equals(attributes.getValue(SWT_ARCH));
    }

    /**
     * The SWT jar of an operating system and an architecture ({@code os.name}, {@code os.arch}) :
     * {@code org.eclipse.platform:org.eclipse.swt.cocoa.macosx.aarch64}...
     */
    static String swtJar(String osName, String osArch) {
        String os = swtOs(osName);
        String ws = switch (os) {
            case "win32" -> "win32";
            case "macosx" -> "cocoa";
            case "linux" -> "gtk";
            default -> null;
        };
        return "org.eclipse.platform:org.eclipse.swt."
                + (ws != null ? ws + "." + os + "." + swtArch(osArch) : "<ws>.<os>.<arch>");
    }

    /**
     * The operating system as SWT names it ({@code SWT-OS}, {@code org.eclipse.swt.internal.Library.os()}) :
     * {@code win32}, {@code macosx}, {@code linux}, or {@code os.name}.
     */
    static String swtOs(String osName) {
        if (osName.equals("Linux")) {
            return "linux";
        }
        if (osName.equals("Mac OS X")) {
            return "macosx";
        }
        if (osName.startsWith("Win")) {
            return "win32";
        }
        return osName;
    }

    /**
     * The architecture as SWT names it ({@code SWT-Arch}, {@code org.eclipse.swt.internal.Library.arch()}) :
     * {@code x86_64} for {@code amd64}, {@code os.arch} otherwise ({@code aarch64}, {@code x86_64}...).
     */
    static String swtArch(String osArch) {
        return osArch.equals("amd64") ? "x86_64" : osArch;
    }

    /**
     * Whether a failure of the start stops the application ({@code Quarkus.asyncExit(1)}) : in production, and in the
     * tests running the main ({@code @QuarkusMainTest}), which would otherwise wait for a shell that never opens.
     */
    private boolean exitOnFailure() {
        return launchMode == LaunchMode.NORMAL || launchMode == LaunchMode.TEST && mainRuns;
    }

    /**
     * Fires {@code SwtStartupEvent}. An observer failing stops the application in production.
     */
    private void fire(Display created) {
        try {
            startupEvent.fire(new SwtStartupEvent(created));
        } catch (Throwable e) {
            // Errors too : a native executable missing some metadata fails with a LinkageError or a
            // MissingReflectionRegistrationError, and the application would wait for ever without a shell
            if (exitOnFailure()) {
                LOGGER.error("A SwtStartupEvent observer failed : the application stops", e);
                Quarkus.asyncExit(1);
            } else {
                LOGGER.error("A SwtStartupEvent observer failed", e);
            }
        }
    }

    /**
     * The event loop, until a stop is requested or the {@code Display} is disposed. The exceptions of the listeners and
     * of the tasks go to the handlers of the {@code Display}, which log them.
     */
    private void loop(Display created) {
        while (!stopRequested && !created.isDisposed()) {
            dispatch(created);
        }
        if (!stopRequested) {
            // the application disposed the Display itself : without its user interface, it stops
            LOGGER.debug("The Display is disposed : the application stops");
            Quarkus.asyncExit();
        }
    }

    /**
     * Runs the next event or task of the event loop, or sleeps until there is one. An exception or an error that
     * escapes {@code readAndDispatch()} goes to the handlers of the {@code Display} ({@link #uncaught}).
     */
    static void dispatch(Display created) {
        try {
            if (!created.readAndDispatch()) {
                created.sleep();
            }
        } catch (SWTException e) {
            // the Display disposed by the application ends the event loop
            if (!created.isDisposed()) {
                uncaught(created, e);
            }
        } catch (Throwable e) {
            if (created.isDisposed() && thrownBySwt(e)) {
                // SWT itself, once the Display is disposed during readAndDispatch() : on Windows, the WM_ENDSESSION of
                // the end of the session disposes it inside PeekMessage, and Display.filterMessage then throws a
                // NullPointerException (plain SWT does too) ; on macOS, Display.runTimers() goes on with the timers
                // that the disposal released. The event loop ends anyway.
                UI_THREAD_LOGGER.debug("Exception of SWT once the Display is disposed", e);
            } else {
                // the exceptions and errors, and the checked exceptions thrown without being declared (Kotlin, Groovy,
                // Lombok @SneakyThrows...), which SWT lets escape from the listeners and the timers : also once the
                // application disposed the Display in a listener or a timer, whose handlers still work
                uncaught(created, e);
            }
        }
    }

    /**
     * Whether SWT threw it itself : the top frame of its stack trace is in SWT. An exception of the application keeps
     * the frame where the application threw it, also when SWT rethrows it ({@code ExceptionStash}).
     */
    static boolean thrownBySwt(Throwable e) {
        StackTraceElement[] stack = e.getStackTrace();
        return stack.length > 0 && stack[0].getClassName().startsWith("org.eclipse.swt.");
    }

    /**
     * An exception or an error that escaped {@code readAndDispatch()} : given to the runtime exception handler or the
     * error handler of the {@code Display} (the ones of the extension, which log it, or the ones of the application),
     * as SWT does with the exceptions of the listeners and of the tasks, so that every uncaught exception of the user
     * interface thread is reported the same way. SWT runs some code without them : on macOS, a timer that fired while
     * the event loop slept runs in {@code readAndDispatch()} ({@code Display.runTimers()}).
     * <p>
     * A checked exception thrown without being declared (Kotlin, Groovy, Lombok {@code @SneakyThrows}...), which SWT
     * lets escape from the listeners and the timers, goes to the runtime exception handler wrapped in an
     * {@code UndeclaredThrowableException}.
     * <p>
     * A handler that throws does not end the event loop : the exception is logged. A handler rethrowing the exceptions
     * (as the default ones of SWT) is called twice for those of the listeners and of the tasks : by SWT, then here.
     */
    static void uncaught(Display created, Throwable e) {
        Throwable reported = e instanceof RuntimeException || e instanceof Error ? e
                : new UndeclaredThrowableException(e, "A checked exception escaped the event loop : " + e);
        try {
            if (reported instanceof Error error) {
                created.getErrorHandler().accept(error);
            } else {
                created.getRuntimeExceptionHandler().accept((RuntimeException) reported);
            }
        } catch (Throwable failure) {
            UI_THREAD_LOGGER.error("Uncaught exception in the event loop", reported);
            if (failure != reported) {
                UI_THREAD_LOGGER.error("The handler of the uncaught exceptions of the Display failed", failure);
            }
        }
    }

    /**
     * Disposes the {@code Display} on the user interface thread, once the event loop ended. The shells first, then the
     * events and tasks left : their listeners run without the lock that SWT holds while it disposes the {@code Display}
     * ({@code Device.class}), which the threads queuing tasks for the user interface thread need too
     * ({@code Display.asyncExec}). A {@code SWT.Dispose} listener, or a {@code @PreDestroy} method of a widget bean, may
     * then wait for such a thread.
     */
    private void dispose(Display created) {
        try {
            if (!created.isDisposed()) {
                disposeShells(created);
                drain(created);
                created.dispose();
            }
        } catch (Throwable e) {
            UI_THREAD_LOGGER.error("Unable to dispose the Display", e);
        } finally {
            List<Runnable> rejected;
            synchronized (lock) {
                state = State.STOPPED;
                display = null;
                uiThread = null;
                rejected = drainPending();
            }
            stopped.countDown();
            reject(rejected, "The user interface stopped");
            LOGGER.debug("The user interface stopped");
        }
    }

    private static void disposeShells(Display created) {
        for (Shell shell : created.getShells()) {
            try {
                if (!shell.isDisposed()) {
                    shell.dispose();
                }
            } catch (Throwable e) {
                UI_THREAD_LOGGER.error("Unable to dispose a shell", e);
            }
        }
    }

    private static void drain(Display created) {
        for (int i = 0; i < DRAIN_LIMIT && !created.isDisposed(); i++) {
            try {
                if (!created.readAndDispatch()) {
                    return;
                }
            } catch (Throwable e) {
                uncaught(created, e);
            }
        }
    }

    /**
     * {@code Quarkus.asyncExit()} only signals the thread running the application, which runs the event loop : a daemon
     * thread waits for the exit and ends the event loop then. It waits uninterruptibly, until the application stops.
     */
    private void watchExit() {
        Thread watcher = new Thread(() -> {
            Quarkus.waitForExit();
            requestStop();
        }, "quarkus-desktop-swt-exit");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * The {@code SWT.Close} event of the {@code Display} : fires {@code QuitRequest}, and stops the application unless an
     * observer cancels it.
     * <p>
     * On macOS, the request is always vetoed for SWT ({@code doit = false}) : SWT would dispose the {@code Display} and let
     * AppKit exit the process ({@code NSTerminateNow}) without the shutdown of Quarkus, which disposes the
     * {@code Display} once the event loop ended. A logout, a restart or a shutdown of macOS is then cancelled (the
     * application "canceled logout") while the application stops : the user repeats it. On Windows and Linux, the end
     * of the session goes on unless an observer cancels it, and SWT disposes the {@code Display} once the session ends
     * (another application may still refuse it after this request) : the event loop then stops the application, as for
     * {@code Display.close()}, which disposes it at once.
     */
    private void quitRequested(org.eclipse.swt.widgets.Event event) {
        boolean mac = "cocoa".equals(SWT.getPlatform());
        if (stopRequested) {
            if (mac) {
                event.doit = false;
            }
            return;
        }
        QuitRequest request = new QuitRequest(event);
        try {
            quitRequests.fire(request);
        } catch (RuntimeException | Error e) {
            LOGGER.error("A QuitRequest observer failed : the request is cancelled", e);
            request.cancel();
        }
        if (mac || request.isCancelled()) {
            event.doit = false;
        }
        if (request.isCancelled()) {
            LOGGER.debug("The quit request is cancelled");
        } else if (mac) {
            LOGGER.debug("Quit requested : the application stops");
            Quarkus.asyncExit();
        } else {
            // Windows and Linux : SWT disposes the Display, at once for Display.close(), and at the end of the session
            // only once it ends (WM_ENDSESSION, the EndSession of the session manager), not when it is queried
            // (WM_QUERYENDSESSION, QueryEndSession) : another application may still refuse it. The event loop stops
            // the application when the Display is disposed.
            LOGGER.debug("Quit requested : the application stops when SWT disposes the Display");
        }
    }

    /**
     * Asks the event loop to end, from any thread. Disposes the shells on the user interface thread : the nested event
     * loops (modal dialogs) end too.
     */
    void requestStop() {
        Display running;
        synchronized (lock) {
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            running = display;
        }
        if (running == null) {
            return;
        }
        try {
            running.asyncExec(() -> {
                for (Shell shell : running.getShells()) {
                    if (!shell.isDisposed()) {
                        shell.dispose();
                    }
                }
            });
            running.wake();
        } catch (SWTException e) {
            // disposed meanwhile
        }
    }

    /**
     * When the application stops, before the {@code ShutdownEvent} observers : ends the event loop and waits for the user
     * interface thread to dispose the {@code Display} (the shutdown of a signal, of dev mode or of a test runs on another
     * thread while the event loop runs). When the user interface did not start, it never will : the queued tasks are
     * rejected. Called twice when the application started (see {@code DesktopSwtRecorder}) : it waits once, the second
     * call does not wait again for a user interface thread that did not stop within the time-out.
     */
    void stop() {
        List<Runnable> rejected = List.of();
        synchronized (lock) {
            if (state == State.NEW || state == State.READY) {
                state = State.STOPPED;
                rejected = drainPending();
                stopped.countDown();
            }
        }
        reject(rejected, "The application stopped before the user interface ran");
        requestStop();
        Thread thread = uiThread;
        if (thread != null && thread != Thread.currentThread() && !stopWaited) {
            stopWaited = true;
            boolean gaveUp = false;
            if (isExiting(thread)) {
                // it waits for the shutdown, the event loop does not run : the Display is not disposed
                LOGGER.debugf("The user interface thread %s called System.exit() : the application stops without"
                        + " disposing the Display", thread.getName());
                gaveUp = true;
            } else {
                try {
                    if (!stopped.await(stopTimeoutMillis, TimeUnit.MILLISECONDS)) {
                        LOGGER.warnf("The user interface thread %s did not stop within %d ms : the application stops"
                                + " without disposing the Display", thread.getName(), stopTimeoutMillis);
                        gaveUp = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    gaveUp = true;
                }
            }
            if (gaveUp) {
                giveUp();
            }
        }
        if (current == this && stopped.getCount() == 0) {
            // the previous application of dev mode is not kept until the next one starts
            current = null;
        }
    }

    /**
     * The stop does not wait for the user interface thread any longer (it called {@code System.exit()}, or the stop timed
     * out) : the calls that come next (the {@code ShutdownEvent} observers) are rejected at once, as once the user
     * interface stopped, instead of being queued for a thread that may never run them. The user interface thread may
     * still return to the event loop : it rejects the tasks queued meanwhile, and disposes the {@code Display}.
     */
    private void giveUp() {
        List<Runnable> rejected = List.of();
        synchronized (lock) {
            if (state == State.STARTING || state == State.RUNNING) {
                state = State.STOPPED;
                rejected = drainPending();
            }
        }
        reject(rejected, "The user interface thread did not stop");
    }

    /**
     * Whether the thread exits the JVM ({@code System.exit()}) : it waits for the shutdown hooks, which stop the
     * application.
     */
    private static boolean isExiting(Thread thread) {
        if (!ApplicationLifecycleManager.isVmShuttingDown()) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            String className = frame.getClassName();
            if (className.equals("java.lang.Shutdown") || (className.equals("java.lang.Runtime")
                    || className.equals("java.lang.System")) && frame.getMethodName().equals("exit")) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------------------- UiThread, executor, @RunOnUiThread

    /**
     * Whether the current thread is the user interface thread of the current application.
     */
    public static boolean isUiThread() {
        SwtUi ui = current;
        return ui != null && ui.uiThread == Thread.currentThread();
    }

    /**
     * The {@code Display} of the current application.
     *
     * @throws IllegalStateException when the user interface does not run
     */
    public static Display runningDisplay() {
        SwtUi ui = current;
        if (ui != null) {
            synchronized (ui.lock) {
                if (ui.state == State.RUNNING || ui.state == State.STARTING && ui.display != null) {
                    return ui.display;
                }
            }
        }
        throw new IllegalStateException("The user interface does not run : SwtLifecycle.run() creates the Display, and"
                + " disposes it when the application stops");
    }

    /**
     * Runs a task later on the user interface thread : queued before the user interface starts, rejected once it
     * stopped.
     *
     * @throws RejectedExecutionException when the user interface stopped or is disabled (tests)
     */
    public static void post(Runnable task) {
        Objects.requireNonNull(task, "task");
        SwtUi ui = current;
        if (ui == null) {
            throw new RejectedExecutionException("The user interface is not running");
        }
        ui.enqueue(task);
    }

    private void enqueue(Runnable task) {
        Display target;
        synchronized (lock) {
            switch (state) {
                case NEW, READY, STARTING -> {
                    pending.add(task);
                    return;
                }
                case RUNNING -> target = display;
                case DISABLED -> throw new RejectedExecutionException(disabledReason);
                default -> throw new RejectedExecutionException("The user interface stopped");
            }
        }
        // Not holding the lock : SWT holds a lock of its own while it disposes the Display and dispatches the Dispose
        // events, whose listeners may queue tasks too. The tasks queued before the start are given to the Display
        // under the lock, before the state is RUNNING : they still run first.
        try {
            target.asyncExec(task);
        } catch (SWTException e) {
            throw new RejectedExecutionException("The user interface stopped", e);
        }
    }

    /**
     * The queued tasks, removed from the queue : called holding the lock.
     */
    private List<Runnable> drainPending() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Runnable> drained = new ArrayList<>(pending);
        pending.clear();
        return drained;
    }

    /**
     * Tells the rejected tasks that they will not run, not holding the lock : they may complete futures, whose callbacks
     * may queue other tasks.
     */
    private static void reject(List<Runnable> tasks, String reason) {
        if (tasks.isEmpty()) {
            return;
        }
        LOGGER.debugf("%d tasks for the user interface thread are rejected : %s", tasks.size(), reason);
        for (Runnable task : tasks) {
            if (task instanceof Rejectable rejectable) {
                try {
                    rejectable.reject(new RejectedExecutionException(reason));
                } catch (RuntimeException e) {
                    LOGGER.error("A rejected task failed", e);
                }
            }
        }
    }
}
