package io.quarkiverse.desktop.showcase.swt.core;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.jboss.logging.Logger;

/**
 * Renders SWT controls and drawings into images for the pixel comparison : the SWT counterparts of the capture methods
 * of {@code core/Snapshots}. Call them on the user interface thread.
 * <ul>
 * <li>{@link #render(Control)} : the pixels of a control and its children, in an image of its size. On Windows, they
 * are copied from the window ({@link #copy} : {@code GC.copyArea}, a {@code BitBlt} from the surface the window paints
 * into) when the control is shown whole on a monitor ({@link #onScreen}). Elsewhere, and for a control that is not
 * shown or not on the screen, they are rendered with {@code Control.print}, on a white background (GTK : the widgets
 * draw themselves into a Cairo context ; Cocoa : the view draws itself into a bitmap). The control must be shown, and
 * settled ({@link #settle}) : a shell just shown has not painted itself yet. The control must also be visible whole
 * inside its shell : the part of a control that a parent hides (the viewport of a scrolled composite) is rendered with
 * what the window shows there ; the main window moves the page frame into a shell for its capture
 * ({@link SwtMainWindow#renderPageFrame}). {@code Control.print} of a {@link Shell} is not supported by SWT : render
 * its content composite.</li>
 * <li>{@link #renderStable(Control, int, long)} : {@link #render} until the same pixels come several times in a row,
 * for the page frames, the main window and the secondary shells.</li>
 * <li>{@link #offscreen(int, int, Consumer)} : drawing with a {@code GC} into an image, independent of the screen.</li>
 * </ul>
 * Why not {@code Control.print} on Windows : it calls {@code PrintWindow} with {@code PW_RENDERFULLCONTENT}, which
 * takes the pixels from what the desktop window manager composed. That copy misses parts of the window now and then,
 * at random, even when nothing changes in the window : the last lines of a wrapped label, the last mark of a table of
 * checks, a child control, or every child control below some height in a tall window (black rectangles). The surface of
 * the window gives the same pixels from one copy to the next, also where another window covers it (the desktop window
 * manager composes the windows : a covered window still paints itself), but it holds the parts of the window on the
 * screen only (off the screen : black, never painted).
 * <p>
 * {@link #render} takes the images at the zoom SWT draws with ({@link #deviceZoom()}) : 100 % (one pixel per point)
 * when the tools pin {@code -Dswt.autoScale=100}, the zoom SWT derives from the monitor otherwise ({@code --hidpi}).
 * {@link #offscreen} returns images at 100 %, whose pixels are the points of the drawing.
 */
public final class SwtSnapshots {

    private static final Logger LOG = Logger.getLogger(SwtSnapshots.class);

    /**
     * How long a control just shown is left to paint itself (and the desktop window manager to compose it) before it is
     * rendered : the default of {@code showcase.snapshot.settle-millis}.
     */
    public static final int SETTLE_MILLIS = 400;

    /**
     * How many renders in a row must give the same pixels in {@link #renderSettled} and in the captures of the main
     * window.
     */
    public static final int STABLE_RENDERS = 3;

    /**
     * How long {@link #renderSettled} and the captures of the main window wait for {@link #STABLE_RENDERS} renders in
     * a row with the same pixels : then the last render is taken (a warning is logged).
     */
    public static final long STABLE_TIMEOUT_MILLIS = 5_000;

    private static final int STABLE_POLL_MILLIS = 50;

    private static final int WHITE = 0xFFFFFF;

    private SwtSnapshots() {
    }

    /**
     * Completes (on the user interface thread) once the event loop ran {@code millis} and then until it was idle :
     * pending events, layouts and paints processed, a shell just shown painted and composed.
     */
    public static CompletionStage<Void> settle(long millis) {
        return UiStages.rounds(3).thenCompose(v -> UiStages.delay(millis)).thenCompose(v -> UiStages.rounds(2));
    }

    /**
     * {@link #renderStable} once settled ({@link #SETTLE_MILLIS}) : for the secondary shells of a page
     * ({@link io.quarkiverse.desktop.showcase.swt.core.SwtPage#extraSnapshots}).
     */
    public static CompletionStage<ImageData> renderSettled(Control control) {
        return settle(SETTLE_MILLIS).thenCompose(v -> renderStable(control, STABLE_RENDERS, STABLE_TIMEOUT_MILLIS));
    }

    /**
     * Completes (on the user interface thread) with the render of {@code control} ({@link #render(Control)}, every
     * 50 ms) once {@code count} renders in a row gave the same pixels, or with the last render after
     * {@code timeoutMillis} (a warning is logged : something in the control still changes). Completes exceptionally
     * when a render fails.
     */
    public static CompletionStage<ImageData> renderStable(Control control, int count, long timeoutMillis) {
        return renderStable(() -> render(control), String.valueOf(control), count, timeoutMillis);
    }

    /**
     * {@link #renderStable(Control, int, long)} of the images of {@code renderer} ({@code what} : for the warning).
     */
    public static CompletionStage<ImageData> renderStable(Supplier<ImageData> renderer, String what, int count,
            long timeoutMillis) {
        CompletableFuture<ImageData> done = new CompletableFuture<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Stability stability = new Stability(renderer, what, Math.max(1, count), deadline, timeoutMillis, done);
        UiStages.ui().execute(() -> stability.next(null, 0));
        return done;
    }

    /**
     * The renders of {@link #renderStable(Supplier, String, int, long)}.
     */
    private record Stability(Supplier<ImageData> renderer, String what, int count, long deadline, long timeoutMillis,
            CompletableFuture<ImageData> done) {

        void next(ImageData previous, int streak) {
            ImageData image;
            try {
                image = renderer.get();
            } catch (Throwable t) {
                done.completeExceptionally(t);
                return;
            }
            int identical = previous != null && same(previous, image) ? streak + 1 : 1;
            if (identical >= count) {
                done.complete(image);
            } else if (System.nanoTime() - deadline > 0) {
                LOG.warnf("%s : no %d identical renders in a row within %d ms, the last render is taken", what, count,
                        timeoutMillis);
                done.complete(image);
            } else {
                UiStages.display().timerExec(STABLE_POLL_MILLIS, () -> next(image, identical));
            }
        }
    }

    /**
     * Whether two images have the same size and the same pixels (in the same format : two renders of a control).
     */
    public static boolean same(ImageData first, ImageData second) {
        return first.width == second.width && first.height == second.height && first.depth == second.depth
                && first.bytesPerLine == second.bytesPerLine && Arrays.equals(first.data, second.data)
                && Arrays.equals(first.alphaData, second.alphaData) && Arrays.equals(first.maskData, second.maskData);
    }

    /**
     * Renders {@code control} and its children into an image of its size : copied from the window on Windows when the
     * control is shown whole on a monitor ({@link #copy}), rendered with {@code Control.print} on a white background
     * otherwise.
     *
     * @throws IllegalStateException when {@code Control.print} is not supported (a shell)
     */
    public static ImageData render(Control control) {
        if (control instanceof Shell) {
            throw new IllegalStateException("Control.print of a Shell is not supported by SWT : render its content");
        }
        Point size = control.getSize();
        Rectangle all = new Rectangle(0, 0, size.x, size.y);
        if (SwtMode.isWindows() && control.isVisible() && !control.getShell().getMinimized()
                && onScreen(control, all)) {
            return copy(control, all);
        }
        return print(control);
    }

    /**
     * Whether {@code area} of {@code control} (in its coordinates) is inside the client area of a monitor : the surface
     * of a window holds the pixels of its parts on the screen only (the task bar is left out, to be safe).
     */
    public static boolean onScreen(Control control, Rectangle area) {
        Display display = control.getDisplay();
        Rectangle bounds = display.map(control, null, area);
        for (Monitor monitor : display.getMonitors()) {
            if (bounds.intersection(monitor.getClientArea()).equals(bounds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pixels of {@code area} of {@code control} (in its coordinates : shown, on the screen) copied from its window
     * ({@code GC.copyArea}) once the paints pending in the windows of the application are done ({@code Display.update},
     * as {@code Control.print} does), at the zoom SWT draws with. For Windows (elsewhere {@code GC.copyArea} reads the
     * screen).
     */
    public static ImageData copy(Control control, Rectangle area) {
        Display display = control.getDisplay();
        display.update();
        Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
        try {
            GC gc = new GC(control);
            try {
                gc.copyArea(image, area.x, area.y);
            } finally {
                gc.dispose();
            }
            return image.getImageData(deviceZoom());
        } finally {
            image.dispose();
        }
    }

    /**
     * The image made of {@code tiles} (images in the same format, by the position of their top left corner, in
     * pixels) : the captures of a control larger than the screen, part by part.
     */
    public static ImageData assemble(Map<Point, ImageData> tiles) {
        ImageData first = tiles.values().iterator().next();
        int width = 0;
        int height = 0;
        for (Map.Entry<Point, ImageData> tile : tiles.entrySet()) {
            width = Math.max(width, tile.getKey().x + tile.getValue().width);
            height = Math.max(height, tile.getKey().y + tile.getValue().height);
        }
        ImageData whole = new ImageData(width, height, first.depth, first.palette);
        for (Map.Entry<Point, ImageData> tile : tiles.entrySet()) {
            ImageData data = tile.getValue();
            int[] row = new int[data.width];
            for (int y = 0; y < data.height; y++) {
                data.getPixels(0, y, data.width, row, 0);
                whole.setPixels(tile.getKey().x, tile.getKey().y + y, data.width, row, 0);
            }
        }
        return whole;
    }

    /**
     * {@code Control.print} of {@code control} and its children into an image of its size, on a white background.
     */
    private static ImageData print(Control control) {
        Point size = control.getSize();
        Image image = new Image(control.getDisplay(), Math.max(1, size.x), Math.max(1, size.y));
        try {
            GC gc = new GC(image);
            try {
                gc.setBackground(control.getDisplay().getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, Math.max(1, size.x), Math.max(1, size.y));
                if (!control.print(gc)) {
                    throw new IllegalStateException("Control.print is not supported for " + control);
                }
            } finally {
                gc.dispose();
            }
            return image.getImageData(deviceZoom());
        } finally {
            image.dispose();
        }
    }

    /**
     * A {@code width x height} image drawn by {@code painter} on a white background : independent of the screen. The
     * image is at 100 % (a pixel per point, the coordinates of the drawing), the size {@code new Image(display, data)}
     * expects ({@link SwtKit#image}).
     */
    public static ImageData offscreen(int width, int height, Consumer<GC> painter) {
        Display display = UiStages.display();
        Image image = new Image(display, Math.max(1, width), Math.max(1, height));
        try {
            GC gc = new GC(image);
            try {
                gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, width, height);
                gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
                painter.accept(gc);
            } finally {
                gc.dispose();
            }
            return image.getImageData(100);
        } finally {
            image.dispose();
        }
    }

    /**
     * The zoom SWT draws with, in % : from {@code swt.autoScale} and the zoom of the primary monitor
     * ({@code Monitor.getZoom()} is always the zoom of the monitor, whatever {@code swt.autoScale}). SWT publishes it
     * as the system property {@code org.eclipse.swt.internal.deviceZoom} once the {@code Display} exists.
     */
    public static int deviceZoom() {
        return Integer.getInteger("org.eclipse.swt.internal.deviceZoom", 100);
    }

    // ------------------------------------------------------------------------------------------------------ pixels

    /**
     * The non premultiplied ARGB pixels of {@code data}, row by row, whatever its depth and palette : the alpha of the
     * image ({@code alphaData}, its global alpha, its transparent pixel or its mask), opaque otherwise.
     */
    public static int[] argb(ImageData data) {
        int width = data.width;
        int height = data.height;
        int[] argb = new int[width * height];
        int[] pixels = new int[width];
        byte[] alphas = new byte[width];
        PaletteData palette = data.palette;
        ImageData mask = data.maskData != null ? data.getTransparencyMask() : null;
        for (int y = 0; y < height; y++) {
            data.getPixels(0, y, width, pixels, 0);
            if (data.alphaData != null) {
                data.getAlphas(0, y, width, alphas, 0);
            }
            for (int x = 0; x < width; x++) {
                int pixel = pixels[x];
                int rgb;
                if (palette.isDirect) {
                    rgb = component(pixel, palette.redMask, palette.redShift) << 16
                            | component(pixel, palette.greenMask, palette.greenShift) << 8
                            | component(pixel, palette.blueMask, palette.blueShift);
                } else {
                    RGB color = palette.colors[pixel];
                    rgb = color.red << 16 | color.green << 8 | color.blue;
                }
                int alpha;
                if (data.alphaData != null) {
                    alpha = alphas[x] & 0xFF;
                } else if (data.alpha != -1) {
                    alpha = data.alpha;
                } else if (data.transparentPixel != -1 && pixel == data.transparentPixel) {
                    alpha = 0;
                } else if (mask != null && mask.getPixel(x, y) == 0) {
                    alpha = 0;
                } else {
                    alpha = 0xFF;
                }
                argb[y * width + x] = alpha << 24 | (rgb & WHITE);
            }
        }
        return argb;
    }

    /**
     * The ARGB pixel of {@code data} at ({@code x}, {@code y}) : the alpha of {@code alphaData} or the global alpha of
     * the image, opaque otherwise.
     */
    public static int pixel(ImageData data, int x, int y) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        int alpha = data.alphaData != null ? data.getAlpha(x, y) : data.alpha != -1 ? data.alpha : 0xFF;
        return alpha << 24 | rgb.red << 16 | rgb.green << 8 | rgb.blue;
    }

    private static int component(int pixel, int mask, int shift) {
        int value = pixel & mask;
        return shift < 0 ? value >>> -shift : value << shift;
    }
}
