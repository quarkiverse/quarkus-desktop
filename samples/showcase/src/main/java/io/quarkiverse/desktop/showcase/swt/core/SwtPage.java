package io.quarkiverse.desktop.showcase.swt.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * A page of the SWT variant of the showcase, exercising one SWT feature area : the SWT counterpart of
 * {@code core/FeaturePage}.
 * <p>
 * Implementations are CDI beans ({@code @Singleton}) discovered through {@code Instance<SwtPage>}, in the packages
 * {@code io.quarkiverse.desktop.showcase.swt.pages.<category>} (only {@code io.quarkiverse.desktop.showcase.swt.**} is
 * compiled into the SWT variant, and never into the others). Every method is invoked on the user interface thread, the
 * thread of the {@code Display}. SWT widgets need their parent when they are created, and most widget classes cannot
 * be subclassed (only {@code Composite} and {@code Canvas}) : a page creates its widgets in {@link #build(Composite)},
 * never in a field initializer, a constructor or a static initializer.
 * <p>
 * In snapshot mode ({@link SwtMode#snapshot()}), the page frame is rendered ({@link SwtSnapshots#render} : copied
 * from the window on Windows, {@code Control.print} elsewhere) once the page is ready and settled (the event loop ran
 * {@code showcase.snapshot.settle-millis} and then until idle) and renders the same pixels 3 times in a row, and
 * compared between a JVM run and a native run, with the checks : a page must render the same pixels on every run. No
 * caret (no focused text field), no hover, no focus decoration, no running animation or timer captured at a random
 * frame, no clock, no random values, no value that depends on the run (a path, a time, a hash code, a temporary file
 * name). A secondary shell is opened without taking the focus ({@code Shell.setVisible(true)}, not {@code open()}),
 * away from the main window, on the screen (on Windows, the parts of a window off the screen are rendered with
 * {@code Control.print}), and disposed in {@link #dispose(Control)}.
 */
public interface SwtPage {

    /**
     * Unique, stable and file-name safe identifier : {@code swt-} then the area and the name, lower case, single dashes
     * ({@code swt-widgets-buttons}). Never {@code --} : it separates the page id from the name of an extra snapshot.
     */
    String id();

    String title();

    /**
     * One of {@link SwtCategories#ORDER}.
     */
    String category();

    /**
     * Order within the category.
     */
    default int order() {
        return 100;
    }

    /**
     * Builds a fresh content for this page : a {@code Composite} child of {@code parent} (the page frame), with a white
     * background ({@link SwtKit#page(Composite, int)}). Checks can be attached to any control of the content with
     * {@link SwtChecks#attach} or shown with {@link ChecksTable}.
     */
    Control build(Composite parent) throws Exception;

    /**
     * Completes once the content is ready to be rendered (images loaded, background work done, secondary shell shown,
     * checks computed) : on the user interface thread ({@link UiStages}). Called in snapshot mode only.
     */
    default CompletionStage<?> ready(Control content) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * How long the snapshot runs wait for {@link #ready(Control)}, in seconds, for a page whose background work has
     * time-outs of its own beyond {@code showcase.snapshot.ready-timeout-seconds} ; {@code 0} : that property.
     */
    default int readyTimeoutSeconds() {
        return 0;
    }

    /**
     * Additional images to compare, for content outside the page frame (secondary shells, offscreen images, rendered
     * print pages...), written as {@code <page-id>--<name>.png}. Invoked after {@link #ready(Control)} completed and
     * the page frame was rendered ; completes on the user interface thread. Keys must be file-name safe.
     */
    default CompletionStage<Map<String, ImageData>> extraSnapshots(Control content) {
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * {@code true} for a page that shows where the runtime legitimately makes a difference (JVM or native image) : its
     * images and check values are expected to differ between the runs, and are reported as {@code EXPECTED} by
     * tools/Compare.java. Failed checks and errors are still reported.
     */
    default boolean runtimeDependent() {
        return false;
    }

    /**
     * {@code false} for every SWT page : the SWT snapshot runner never takes the focus (no focus lock, no real input),
     * and records an error for a page that needs it.
     */
    default boolean needsFocus() {
        return false;
    }

    /**
     * Releases what the page holds besides its content (stops its timers, disposes its secondary shells and its images,
     * restores the clipboard...) when the page is left, before the content is disposed.
     */
    default void dispose(Control content) {
    }
}
