package io.quarkiverse.desktop.showcase.swt.pages.widgets;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;

/**
 * Small icons computed pixel by pixel (no {@code GC}, no file) for the pages of the structured widgets
 * ({@link SwtListsTablesTreesPage}, {@link SwtContainersPage}) : a shape filled with a color, a darker outline of one
 * pixel and an anti-aliased edge (4 x 4 samples per pixel), on a transparent background (an alpha channel). The pixels
 * are the same on every run and every platform : what differs is how the widgets draw them (the image lists of
 * comctl32 on Windows, Cairo surfaces on GTK, {@code NSImage} on macOS).
 */
final class StructuredIcons {

    enum Shape {
        CIRCLE,
        SQUARE,
        DIAMOND,
        TRIANGLE,
        FOLDER,
        PAGE
    }

    /** Samples per pixel, on each axis. */
    private static final int SAMPLES = 4;

    private static final double[] TRIANGLE_X = { 0.5, 0.94, 0.06 };
    private static final double[] TRIANGLE_Y = { 0.08, 0.9, 0.9 };
    private static final double[] DIAMOND_X = { 0.5, 0.96, 0.5, 0.04 };
    private static final double[] DIAMOND_Y = { 0.04, 0.5, 0.96, 0.5 };
    private static final double[] PAGE_X = { 0.18, 0.62, 0.84, 0.84, 0.18 };
    private static final double[] PAGE_Y = { 0.06, 0.06, 0.28, 0.94, 0.94 };

    private StructuredIcons() {
    }

    /**
     * The pixels of a {@code size x size} icon : {@code shape} filled with {@code 0xRRGGBB}, with an alpha channel.
     */
    static ImageData data(Shape shape, int rgb, int size) {
        ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
        byte[] alphas = new byte[size * size];
        int edge = darker(rgb);
        double inset = 1.0 / size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int fill = 0;
                int outline = 0;
                for (int sy = 0; sy < SAMPLES; sy++) {
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        double u = (x + (sx + 0.5) / SAMPLES) / size;
                        double v = (y + (sy + 0.5) / SAMPLES) / size;
                        if (inside(shape, u, v, 0)) {
                            if (inside(shape, u, v, inset)) {
                                fill++;
                            } else {
                                outline++;
                            }
                        }
                    }
                }
                int covered = fill + outline;
                if (covered > 0) {
                    data.setPixel(x, y, blend(rgb, fill, edge, outline));
                    alphas[y * size + x] = (byte) (covered * 255 / (SAMPLES * SAMPLES));
                }
            }
        }
        data.alphaData = alphas;
        return data;
    }

    /**
     * {@code true} when ({@code u}, {@code v}), in the unit square, is inside {@code shape} shrunk by {@code inset}.
     */
    private static boolean inside(Shape shape, double u, double v, double inset) {
        return switch (shape) {
            case CIRCLE -> {
                double r = 0.46 - inset;
                yield (u - 0.5) * (u - 0.5) + (v - 0.5) * (v - 0.5) <= r * r;
            }
            case SQUARE -> {
                double half = 0.42 - inset;
                double radius = 0.12;
                double dx = Math.max(Math.abs(u - 0.5) - (half - radius), 0);
                double dy = Math.max(Math.abs(v - 0.5) - (half - radius), 0);
                yield Math.abs(u - 0.5) <= half && Math.abs(v - 0.5) <= half && dx * dx + dy * dy <= radius * radius;
            }
            case DIAMOND -> polygon(DIAMOND_X, DIAMOND_Y, u, v, inset);
            case TRIANGLE -> polygon(TRIANGLE_X, TRIANGLE_Y, u, v, inset);
            case FOLDER -> rectangle(0.06, 0.16, 0.44, 0.32, u, v, inset)
                    || rectangle(0.06, 0.26, 0.94, 0.86, u, v, inset);
            case PAGE -> polygon(PAGE_X, PAGE_Y, u, v, inset);
        };
    }

    private static boolean rectangle(double left, double top, double right, double bottom, double u, double v,
            double inset) {
        return u >= left + inset && u <= right - inset && v >= top + inset && v <= bottom - inset;
    }

    /**
     * {@code true} when the point is inside the convex polygon, at {@code inset} at least from every edge.
     */
    private static boolean polygon(double[] xs, double[] ys, double u, double v, double inset) {
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < xs.length; i++) {
            cx += xs[i] / xs.length;
            cy += ys[i] / ys.length;
        }
        for (int i = 0; i < xs.length; i++) {
            int j = (i + 1) % xs.length;
            double ex = xs[j] - xs[i];
            double ey = ys[j] - ys[i];
            double length = Math.sqrt(ex * ex + ey * ey);
            double side = (ex * (v - ys[i]) - ey * (u - xs[i])) / length;
            double center = ex * (cy - ys[i]) - ey * (cx - xs[i]);
            if ((center < 0 ? -side : side) < inset) {
                return false;
            }
        }
        return true;
    }

    private static int darker(int rgb) {
        int r = (rgb >> 16 & 0xFF) * 3 / 5;
        int g = (rgb >> 8 & 0xFF) * 3 / 5;
        int b = (rgb & 0xFF) * 3 / 5;
        return r << 16 | g << 8 | b;
    }

    private static int blend(int a, int weightA, int b, int weightB) {
        int total = weightA + weightB;
        int r = ((a >> 16 & 0xFF) * weightA + (b >> 16 & 0xFF) * weightB) / total;
        int g = ((a >> 8 & 0xFF) * weightA + (b >> 8 & 0xFF) * weightB) / total;
        int bl = ((a & 0xFF) * weightA + (b & 0xFF) * weightB) / total;
        return r << 16 | g << 8 | bl;
    }

    /**
     * The icons of one page content : created on demand, shared by the widgets of the content, disposed with it
     * ({@link #dispose}).
     */
    static final class Cache {

        private final Display display;
        private final Map<String, Image> images = new LinkedHashMap<>();

        Cache(Display display) {
            this.display = display;
        }

        Image get(Shape shape, int rgb, int size) {
            return images.computeIfAbsent(shape + "/" + rgb + "/" + size,
                    key -> new Image(display, data(shape, rgb, size)));
        }

        void dispose() {
            images.values().forEach(Image::dispose);
            images.clear();
        }
    }
}
