package io.quarkiverse.desktop.showcase.swt.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.core.Json;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;

/**
 * Snapshot mode of the SWT variant : renders every page to a PNG file and writes a report (environment, checks and
 * errors), then exits. The SWT counterpart of {@code core/SnapshotRunner}, with the same output : the output of a JVM
 * run and of a native run are compared by tools/Compare.java.
 * <p>
 * Enabled by {@code -Dshowcase.snapshot.dir=<dir>} (tools/Snapshot.java sets it), with
 * {@code showcase.snapshot.exit}, {@code showcase.snapshot.settle-millis} and
 * {@code showcase.snapshot.ready-timeout-seconds}. Files : {@code _main-window.png} (the content of the main window,
 * without a page), {@code <page-id>.png} (the page frame), {@code <page-id>--<extra>.png}
 * ({@link SwtPage#extraSnapshots}), {@code report.json} (rewritten after every page : a run that crashes still has a
 * report).
 * <p>
 * The exceptions of the listeners, timers and tasks of the user interface thread (the handlers of the {@code Display},
 * during the run) and the uncaught exceptions of the other threads are recorded as errors of the current page. The run
 * never takes the focus : the pages that need it ({@link SwtPage#needsFocus()}) are reported as errors.
 * <p>
 * The clicks and the mouse wheel of the user are dropped during the run ({@link #DROPPED_INPUT} : filters of the
 * {@code Display} that cancel them) : the windows of the run are on the user's desktop, and a wheel turned over them
 * (Windows scrolls the window under the pointer, active or not) would scroll the main window or a control of a page.
 * The native controls still draw their hover state when the pointer is over them.
 */
@Singleton
public class SwtSnapshotRunner {

    private static final Logger LOG = Logger.getLogger(SwtSnapshotRunner.class);

    /**
     * How long the background threads of a page may still run after it : then interrupted, and waited for as long
     * again.
     */
    private static final long BACKGROUND_GRACE_MILLIS = 5_000;

    /**
     * The events of the real input that the run cancels ({@code doit = false}) : the clicks and the mouse wheel.
     */
    private static final int[] DROPPED_INPUT = { SWT.MouseDown, SWT.MouseUp, SWT.MouseDoubleClick, SWT.MouseWheel,
            SWT.MouseHorizontalWheel };

    /** Runs the stages of the run on the user interface thread ({@code whenCompleteAsync(..., ui)}). */
    @Inject
    UiThreadExecutor ui;

    @ConfigProperty(name = "showcase.snapshot.dir")
    Optional<String> dir;

    @ConfigProperty(name = "showcase.snapshot.exit", defaultValue = "true")
    boolean exit;

    @ConfigProperty(name = "showcase.snapshot.settle-millis", defaultValue = "400")
    int settleMillis;

    @ConfigProperty(name = "showcase.snapshot.ready-timeout-seconds", defaultValue = "30")
    int readyTimeoutSeconds;

    private final Map<String, List<String>> uncaught = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String currentPageId = "_startup";
    /** The environment of the intermediate reports. */
    private Map<String, Object> environment;
    private Consumer<RuntimeException> previousRuntimeHandler;
    private Consumer<Error> previousErrorHandler;
    private final Listener dropInput = event -> event.doit = false;

    void onStartup(@Observes StartupEvent event) {
        if (enabled()) {
            SwtMode.enableSnapshot();
            // uncaught exceptions of the other threads (those of the user interface thread go to the Display handlers)
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                record(thread, error);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    error.printStackTrace();
                }
            });
        }
    }

    public boolean enabled() {
        return dir.isPresent();
    }

    /**
     * Runs the snapshots of {@code pages} (on the user interface thread), then exits (with
     * {@code showcase.snapshot.exit}).
     */
    public void run(SwtMainWindow window, List<SwtPage> pages) {
        Path out = Path.of(dir.orElseThrow()).toAbsolutePath();
        LOG.infof("Snapshot run of %d pages into %s", pages.size(), out);
        Display display = window.shell().getDisplay();
        installHandlers(display);

        List<Map<String, Object>> results = new ArrayList<>();
        CompletionStage<Void> chain = UiStages.rounds(5)
                .thenCompose(v -> UiStages.delay(settleMillis))
                .thenCompose(v -> UiStages.rounds(2))
                .thenCompose(v -> SwtSnapshots.renderStable(window.rootContent(), SwtSnapshots.STABLE_RENDERS,
                        SwtSnapshots.STABLE_TIMEOUT_MILLIS))
                .handle((image, error) -> {
                    try {
                        if (error != null) {
                            throw unwrap(error);
                        }
                        writeImage(image, out.resolve("_main-window.png"));
                    } catch (Throwable t) {
                        LOG.error("Failed to render the main window", t);
                        uncaught.computeIfAbsent("_main-window", id -> Collections.synchronizedList(new ArrayList<>()))
                                .add("snapshot: " + SwtChecks.describe(t));
                    }
                    return null;
                });
        for (SwtPage page : pages) {
            chain = chain.thenCompose(v -> awaitBackground())
                    .thenCompose(v -> capture(window, page, out))
                    .thenAccept(result -> {
                        results.add(result);
                        // written after every page too : a run that crashes (a native executable) still has a report
                        writeReport(out, results, false);
                    });
        }
        chain.whenCompleteAsync((v, error) -> {
            if (error != null) {
                LOG.error("Snapshot run aborted", error);
            }
            currentPageId = "_end";
            try {
                window.clear();
            } catch (Throwable t) {
                LOG.error("Failed to dispose the last page", t);
            }
            writeReport(out, results, true);
            long failed = results.stream().filter(r -> !((List<?>) r.get("errors")).isEmpty()).count();
            LOG.infof("Snapshot run finished : %d pages, %d with errors", results.size(), failed);
            restoreHandlers(display);
            if (exit) {
                window.dispose();
                for (Shell shell : display.getShells()) {
                    if (!shell.isDisposed()) {
                        shell.dispose();
                    }
                }
                Quarkus.asyncExit();
            }
        }, ui);
    }

    /**
     * The showcase failed to start (e.g. the main window cannot be created) : writes a report with the error, so that
     * the run is still compared.
     */
    public void startFailed(Throwable error) {
        if (enabled()) {
            uncaught.computeIfAbsent("_startup", id -> Collections.synchronizedList(new ArrayList<>()))
                    .add("start: " + SwtChecks.describe(error));
            writeReport(Path.of(dir.orElseThrow()).toAbsolutePath(), List.of(), true);
        }
    }

    private CompletionStage<Map<String, Object>> capture(SwtMainWindow window, SwtPage page, Path out) {
        currentPageId = page.id();
        long start = System.nanoTime();
        List<String> errors = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", page.id());
        result.put("title", page.title());
        result.put("category", page.category());
        result.put("runtimeDependent", page.runtimeDependent());
        result.put("needsFocus", page.needsFocus());
        // the last page started before a crash is the one without an "ok" / "error(s)" line
        LOG.infof("%-40s started", page.id());
        if (page.needsFocus()) {
            errors.add("needsFocus: the SWT snapshot runner never takes the focus");
        }

        window.select(page);
        Control content = window.currentContent();
        Throwable buildError = window.currentError();
        if (buildError != null) {
            errors.add("build: " + SwtChecks.describe(buildError));
        }
        CompletionStage<?> ready;
        try {
            ready = buildError == null ? page.ready(content) : CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            ready = CompletableFuture.failedFuture(t);
        }

        return UiStages.timeout(ready, Math.max(readyTimeoutSeconds, page.readyTimeoutSeconds()) * 1000L, "ready")
                .handle((v, error) -> {
                    if (error != null) {
                        errors.add("ready: " + SwtChecks.describe(unwrap(error)));
                    }
                    return null;
                })
                .thenCompose(v -> SwtSnapshots.settle(settleMillis))
                .thenCompose(v -> {
                    CompletionStage<ImageData> rendered;
                    try {
                        rendered = window.renderPageFrame(settleMillis);
                    } catch (Throwable t) {
                        rendered = CompletableFuture.failedFuture(t);
                    }
                    return rendered.handle((image, error) -> {
                        if (error != null) {
                            errors.add("snapshot: " + SwtChecks.describe(unwrap(error)));
                            return null;
                        }
                        try {
                            String file = page.id() + ".png";
                            writeImage(image, out.resolve(file));
                            result.put("snapshot", file);
                            result.put("width", image.width);
                            result.put("height", image.height);
                        } catch (Throwable t) {
                            errors.add("snapshot: " + SwtChecks.describe(t));
                        }
                        return null;
                    });
                })
                .thenCompose(v -> {
                    CompletionStage<Map<String, ImageData>> extras;
                    try {
                        extras = buildError == null ? page.extraSnapshots(content)
                                : CompletableFuture.completedFuture(Map.of());
                    } catch (Throwable t) {
                        extras = CompletableFuture.failedFuture(t);
                    }
                    return UiStages.timeout(extras, readyTimeoutSeconds * 1000L, "extra snapshots");
                })
                .handle((extras, error) -> {
                    Map<String, ImageData> images = new TreeMap<>();
                    if (error != null) {
                        errors.add("extraSnapshots: " + SwtChecks.describe(unwrap(error)));
                    } else if (extras != null) {
                        images.putAll(extras);
                    }
                    List<String> extraFiles = new ArrayList<>();
                    images.forEach((name, image) -> {
                        String file = page.id() + "--" + name + ".png";
                        try {
                            writeImage(image, out.resolve(file));
                            extraFiles.add(file);
                        } catch (Throwable t) {
                            errors.add("extraSnapshots: " + name + ": " + SwtChecks.describe(t));
                        }
                    });
                    Collections.sort(extraFiles);
                    result.put("extras", extraFiles);
                    List<Map<String, Object>> checks = new ArrayList<>();
                    List<Check> collected = content == null || content.isDisposed() ? List.of()
                            : SwtChecks.collect(content);
                    for (Check check : collected) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("name", check.name());
                        c.put("value", check.value());
                        c.put("ok", check.ok());
                        checks.add(c);
                        if (Boolean.FALSE.equals(check.ok())) {
                            errors.add("check failed: " + check.name() + " = " + check.value());
                        }
                    }
                    List<String> pageUncaught = uncaught.getOrDefault(page.id(), List.of());
                    synchronized (pageUncaught) {
                        pageUncaught.forEach(e -> errors.add("uncaught: " + e));
                    }
                    result.put("checks", checks);
                    result.put("errors", errors);
                    result.put("millis", (System.nanoTime() - start) / 1_000_000);
                    LOG.infof("%-40s %s", page.id(), errors.isEmpty() ? "ok" : errors.size() + " error(s)");
                    return result;
                });
    }

    /**
     * The exceptions of the listeners, timers and tasks of the user interface thread are recorded during the run, then
     * passed to the previous handlers (quarkus-desktop-swt logs them, the event loop goes on). The clicks and the mouse
     * wheel are cancelled ({@link #DROPPED_INPUT}).
     */
    private void installHandlers(Display display) {
        for (int type : DROPPED_INPUT) {
            display.addFilter(type, dropInput);
        }
        previousRuntimeHandler = display.getRuntimeExceptionHandler();
        previousErrorHandler = display.getErrorHandler();
        Consumer<RuntimeException> runtimeHandler = previousRuntimeHandler;
        Consumer<Error> errorHandler = previousErrorHandler;
        display.setRuntimeExceptionHandler(e -> {
            record(Thread.currentThread(), e);
            runtimeHandler.accept(e);
        });
        display.setErrorHandler(e -> {
            record(Thread.currentThread(), e);
            errorHandler.accept(e);
        });
    }

    private void restoreHandlers(Display display) {
        if (display.isDisposed()) {
            return;
        }
        for (int type : DROPPED_INPUT) {
            display.removeFilter(type, dropInput);
        }
        if (previousRuntimeHandler != null) {
            display.setRuntimeExceptionHandler(previousRuntimeHandler);
            display.setErrorHandler(previousErrorHandler);
        }
    }

    private void record(Thread thread, Throwable error) {
        uncaught.computeIfAbsent(currentPageId, id -> Collections.synchronizedList(new ArrayList<>()))
                .add(thread.getName() + ": " + SwtChecks.describe(error));
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static void writeImage(ImageData image, Path file) {
        try {
            SwtPngWriter.write(image, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes report.json. The environment is described once for the intermediate reports (written after every page) and
     * again for the final one ({@code complete}), at the end of the run.
     */
    private void writeReport(Path out, List<Map<String, Object>> pages, boolean complete) {
        Map<String, Object> report = new LinkedHashMap<>();
        if (complete || environment == null) {
            Map<String, Object> env = new LinkedHashMap<>();
            try {
                env.putAll(SwtEnvironment.describe());
            } catch (Throwable t) {
                env.put("environmentError", SwtChecks.describe(t));
            }
            environment = env;
        }
        report.putAll(environment);
        report.put("pages", pages);
        Map<String, Object> other = new LinkedHashMap<>(uncaught);
        pages.forEach(p -> other.remove((String) p.get("id")));
        report.put("uncaughtOutsidePages", other);
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("report.json"), Json.write(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Failed to write report", e);
        }
    }

    /**
     * Waits for the background threads of the previous page ({@link UiStages#awaitBackground}) : a page never starts
     * while a thread of the previous one still runs.
     */
    private static CompletionStage<Void> awaitBackground() {
        return UiStages.awaitBackground(BACKGROUND_GRACE_MILLIS).thenAccept(left -> {
            if (!left.isEmpty()) {
                LOG.warnf("Background threads of the previous page still running : %s", left);
            }
        });
    }
}
