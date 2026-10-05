package io.quarkiverse.desktop.swt.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
        if (e instanceof UnsatisfiedLinkError) {
            return " (SWT could not load its native libraries : the SWT jar of the application must be the one of this"
                    + " operating system and architecture, " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch") + ", org.eclipse.platform:org.eclipse.swt.<ws>.<os>.<arch>)";
        }
        String os = System.getProperty("os.name", "").toLowerCase();
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
            try {
                if (!created.readAndDispatch()) {
                    created.sleep();
                }
            } catch (SWTException e) {
                if (created.isDisposed()) {
                    break;
                }
                UI_THREAD_LOGGER.error("Uncaught exception in the event loop", e);
            } catch (RuntimeException | Error e) {
                UI_THREAD_LOGGER.error("Uncaught exception in the event loop", e);
            }
        }
        if (!stopRequested) {
            // the application disposed the Display itself : without its user interface, it stops
            LOGGER.debug("The Display is disposed : the application stops");
            Quarkus.asyncExit();
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
        } catch (RuntimeException | Error e) {
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
            } catch (RuntimeException | Error e) {
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
            } catch (RuntimeException | Error e) {
                UI_THREAD_LOGGER.error("Uncaught exception in the event loop", e);
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
     * of the session goes on unless an observer cancels it : SWT disposes the {@code Display} at the end of the session.
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
        } else {
            LOGGER.debug("Quit requested : the application stops");
            Quarkus.asyncExit();
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
     * rejected. Called twice when the application started (see {@code DesktopSwtRecorder}).
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
        if (thread != null && thread != Thread.currentThread()) {
            if (isExiting(thread)) {
                // it waits for the shutdown, the event loop does not run : the Display is not disposed
                LOGGER.debugf("The user interface thread %s called System.exit() : the application stops without"
                        + " disposing the Display", thread.getName());
            } else {
                try {
                    if (!stopped.await(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        LOGGER.warnf("The user interface thread %s did not stop within %d ms : the application stops"
                                + " without disposing the Display", thread.getName(), STOP_TIMEOUT_MILLIS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (current == this && stopped.getCount() == 0) {
            // the previous application of dev mode is not kept until the next one starts
            current = null;
        }
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
