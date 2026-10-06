package io.quarkiverse.desktop.showcase.swt.pages.graphics;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;
import io.quarkiverse.desktop.showcase.swt.core.SwtTiles;

/**
 * Helpers of the graphics pages : the colors of the tiles, pixel probes compared exactly or within a tolerance, the
 * number of distinct colors of a part of a tile (an aliased drawing has no intermediate color), the names of the
 * {@code GC} constants, and the shapes shared by the tiles.
 */
final class GcProbes {

    /** Fill colors of the tiles. */
    static final int[] FILLS = { 0x4FC3F7, 0xFFB74D, 0x81C784, 0xE57373, 0xBA68C8, 0x4DB6AC };
    /** Guides, control points. */
    static final int ACCENT = 0xE53935;
    /** Outlines of the untransformed figures. */
    static final int GHOST = 0x90A4AE;

    private GcProbes() {
    }

    /**
     * {@code #FFRRGGBB} of an opaque {@code 0xRRGGBB} color, the format of {@link SwtTiles#probe}.
     */
    static String opaque(int rgb) {
        return SwtChecks.argb(0xFF000000 | rgb);
    }

    /**
     * The {@code 0xRRGGBB} color of a probe ({@code #AARRGGBB}).
     */
    static int rgb(String probe) {
        return Integer.parseUnsignedInt(probe.substring(1), 16) & 0xFFFFFF;
    }

    /**
     * {@code src} drawn with {@code alpha} over {@code dst} ({@code 0xRRGGBB}), rounded.
     */
    static int blend(int src, int dst, int alpha) {
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int s = (src >> shift) & 0xFF;
            int d = (dst >> shift) & 0xFF;
            rgb |= Math.round((s * alpha + d * (255 - alpha)) / 255f) << shift;
        }
        return rgb;
    }

    /**
     * The largest difference between the channels of two {@code 0xRRGGBB} colors.
     */
    static int distance(int a, int b) {
        int max = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            max = Math.max(max, Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)));
        }
        return max;
    }

    /**
     * The pixel at ({@code x}, {@code y}) of the tile with {@code caption} is {@code expected} : exactly.
     */
    static Check exact(String name, SwtTiles tiles, ImageData image, String caption, int x, int y, int expected) {
        return SwtChecks.expect(name, opaque(expected), () -> tiles.probe(image, caption, x, y));
    }

    /**
     * The pixel at ({@code x}, {@code y}) of the tile with {@code caption} is {@code expected} within
     * {@code tolerance} on every channel (blending and gradients round differently with GDI+, Cairo and Core
     * Graphics) : the value is the pixel.
     */
    static Check near(String name, SwtTiles tiles, ImageData image, String caption, int x, int y, int expected,
            int tolerance) {
        try {
            String probe = tiles.probe(image, caption, x, y);
            boolean ok = distance(rgb(probe), expected) <= tolerance;
            return Check.of(name, ok, ok ? probe
                    : "expected " + opaque(expected) + " ± " + tolerance + " but got " + probe);
        } catch (Throwable t) {
            return Check.fail(name, SwtChecks.describe(t));
        }
    }

    /**
     * The number of distinct colors of the {@code width x height} rectangle at ({@code x}, {@code y}) of the drawing
     * area of the tile with {@code caption}.
     */
    static int colors(SwtTiles tiles, ImageData image, String caption, int x, int y, int width, int height) {
        Point origin = tiles.origin(tiles.index(caption));
        Set<Integer> colors = new HashSet<>();
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                colors.add(SwtSnapshots.pixel(image, origin.x + x + i, origin.y + y + j));
            }
        }
        return colors.size();
    }

    /**
     * The number of distinct colors of the whole drawing area of the tile with {@code caption}.
     */
    static int colors(SwtTiles tiles, ImageData image, String caption) {
        return colors(tiles, image, caption, 0, 0, tiles.areaWidth(), tiles.areaHeight());
    }

    /**
     * The vertices of a pentagram (every second vertex of a pentagon) centered on ({@code cx}, {@code cy}), its first
     * vertex at the top : self-intersecting, its center is inside with the non-zero winding rule only.
     */
    static int[] pentagram(int cx, int cy, int radius) {
        int[] points = new int[10];
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2 + i * 4 * Math.PI / 5;
            points[2 * i] = (int) Math.round(cx + radius * Math.cos(angle));
            points[2 * i + 1] = (int) Math.round(cy + radius * Math.sin(angle));
        }
        return points;
    }

    // ------------------------------------------------------------------------------------------------- constants

    static String antialias(int value) {
        return switch (value) {
            case SWT.ON -> "ON";
            case SWT.OFF -> "OFF";
            case SWT.DEFAULT -> "DEFAULT";
            default -> String.valueOf(value);
        };
    }

    static String interpolation(int value) {
        return switch (value) {
            case SWT.NONE -> "NONE";
            case SWT.LOW -> "LOW";
            case SWT.HIGH -> "HIGH";
            case SWT.DEFAULT -> "DEFAULT";
            default -> String.valueOf(value);
        };
    }

    static String lineStyle(int value) {
        return switch (value) {
            case SWT.LINE_SOLID -> "LINE_SOLID";
            case SWT.LINE_DASH -> "LINE_DASH";
            case SWT.LINE_DOT -> "LINE_DOT";
            case SWT.LINE_DASHDOT -> "LINE_DASHDOT";
            case SWT.LINE_DASHDOTDOT -> "LINE_DASHDOTDOT";
            case SWT.LINE_CUSTOM -> "LINE_CUSTOM";
            default -> String.valueOf(value);
        };
    }

    static String lineCap(int value) {
        return switch (value) {
            case SWT.CAP_FLAT -> "CAP_FLAT";
            case SWT.CAP_ROUND -> "CAP_ROUND";
            case SWT.CAP_SQUARE -> "CAP_SQUARE";
            default -> String.valueOf(value);
        };
    }

    static String lineJoin(int value) {
        return switch (value) {
            case SWT.JOIN_MITER -> "JOIN_MITER";
            case SWT.JOIN_ROUND -> "JOIN_ROUND";
            case SWT.JOIN_BEVEL -> "JOIN_BEVEL";
            default -> String.valueOf(value);
        };
    }

    static String fillRule(int value) {
        return switch (value) {
            case SWT.FILL_EVEN_ODD -> "FILL_EVEN_ODD";
            case SWT.FILL_WINDING -> "FILL_WINDING";
            default -> String.valueOf(value);
        };
    }
}
