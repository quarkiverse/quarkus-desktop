package io.quarkiverse.desktop.showcase.swt.pages.graphics;

import static io.quarkiverse.desktop.showcase.swt.core.SwtTiles.BACKGROUND;
import static io.quarkiverse.desktop.showcase.swt.core.SwtTiles.INK;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.ACCENT;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.FILLS;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.GHOST;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.colors;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.exact;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.near;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.PathData;
import org.eclipse.swt.graphics.Pattern;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtMode;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.SwtTiles;
import io.quarkiverse.desktop.showcase.swt.core.SwtTiles.Tile;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * The advanced graphics of SWT : {@code Path} (lines, quadratic and cubic curves, arcs, rectangles, the outline of a
 * string, closed figures, both fill rules, flattening, bounds, hit testing, path data), {@code Transform} (translate,
 * rotate, scale, shear, multiply in both orders, invert, transformed points and text, nested transforms),
 * {@code Pattern} (linear gradients, with alpha, repeated, as the foreground of lines and text, and image patterns),
 * clipping with a {@code Region} (rectangles and polygons, added, subtracted and intersected), with a {@code Path} (a
 * circle, the outline of a string) and with a rectangle under a rotation, the interpolation of a scaled generated image
 * ({@code NONE}, {@code LOW}, {@code HIGH}) and the text anti-aliasing setting.
 * <p>
 * These are the advanced graphics of the platform : GDI+ on Windows, Cairo on Linux, Core Graphics on macOS. On
 * Windows, {@code Path} wraps a GDI+ {@code GraphicsPath} ({@code Gdip.GraphicsPath_AddLine},
 * {@code GraphicsPath_AddBezier}, {@code GraphicsPath_AddArc}, {@code GraphicsPath_AddRectangle},
 * {@code GraphicsPath_AddString} with a {@code FontFamily}, {@code GraphicsPath_CloseFigure},
 * {@code GraphicsPath_Flatten}, {@code GraphicsPath_GetBounds} filling a {@code RectF} struct,
 * {@code GraphicsPath_GetLastPoint} filling a {@code PointF}, {@code GraphicsPath_IsVisible},
 * {@code GraphicsPath_IsOutlineVisible}, {@code GraphicsPath_GetPathPoints} and {@code GetPathTypes}),
 * {@code Transform} a GDI+ {@code Matrix} ({@code Matrix_Translate}, {@code Matrix_Rotate}, {@code Matrix_Scale},
 * {@code Matrix_Shear}, {@code Matrix_Multiply}, {@code Matrix_Invert}, {@code Matrix_GetElements} into a
 * {@code float[]}, {@code Matrix_TransformPoints}), {@code Pattern} a {@code LinearGradientBrush} between two
 * {@code PointF} structs or a {@code TextureBrush} of a GDI+ {@code Bitmap}, and the clipping goes through
 * {@code Graphics_SetClip}, {@code Graphics_SetClipPath} and {@code Region_new} from the GDI region of a
 * {@code Region} ({@code CreateRectRgn}, {@code CreatePolygonRgn}, {@code CombineRgn}, {@code PtInRegion},
 * {@code GetRgnBox} filling a {@code RECT}). On Linux they are Cairo paths, matrices ({@code cairo_matrix_t} as a
 * {@code double[]}), patterns ({@code cairo_pattern_create_linear}, {@code cairo_pattern_add_color_stop_rgba},
 * {@code cairo_pattern_create_for_surface}) and regions ({@code cairo_region_t}), with Pango for the outline of a
 * string.
 * <p>
 * Why it matters for a native executable : these are JNI methods and structs that the widgets of a window seldom
 * reach, resolved when they are first called. A {@code RectF} or {@code PointF} missing from the JNI configuration
 * fails only when a path is measured or a gradient is created, and GDI+ itself is started lazily
 * ({@code GdiplusStartup} with a {@code GdiplusStartupInput} struct, by the first {@code Path}, {@code Transform},
 * {@code Pattern} or advanced {@code GC}). The tiles reach them all, the checks read back what the native code wrote
 * into the structs and arrays, and the snapshot compares every pixel with the JVM.
 * <p>
 * Checks : bounds ({@code Path.getBounds}, formatted with 3 decimals), current points, hit testing with both fill
 * rules, the elements of transforms, region bounds and containment, pattern creation and use, the state of a
 * {@code GC}, and pixel probes : exact inside clipped fills, within a tolerance for the gradients, and the number of
 * colors of the interpolated images and of the aliased text.
 */
@Singleton
public class SwtAdvancedGraphicsPage implements SwtPage {

    private static final int COLUMNS = 6;
    private static final int TILE_WIDTH = 166;
    private static final int TILE_HEIGHT = 150;
    private static final int HALF_WIDTH = 494;
    /** Center of the drawing area of a tile. */
    private static final float CX = 75;
    private static final float CY = 59;
    private static final String DATA = SwtAdvancedGraphicsPage.class.getName();

    private static final String LINES = "lineTo, close";
    private static final String RECTANGLES = "addRectangle";
    private static final String GRADIENT = "linear gradient";
    private static final String GRADIENT_ALPHA = "gradient with alpha";
    private static final String IMAGE_PATTERN = "image pattern";
    private static final String REGION_ADD = "Region.add(rect)";
    private static final String REGION_POLYGON = "Region.add(polygon)";
    private static final String REGION_SUBTRACT = "Region.subtract";
    private static final String REGION_INTERSECT = "Region.intersect";
    private static final String PATH_CLIP = "setClipping(Path)";
    private static final String ROTATED_CLIP = "rotated clipping";
    private static final String NONE = "interpolation NONE";
    private static final String LOW = "interpolation LOW";
    private static final String HIGH = "interpolation HIGH";
    private static final String TEXT_OFF = "text antialias OFF";
    private static final String TEXT_ON = "text antialias ON";

    private static final int GRADIENT_FROM = 0x1565C0;
    private static final int GRADIENT_TO = 0xFFB74D;
    private static final int CHECKER = 0xCFD8DC;
    private static final int WHITE = 0xFFFFFF;
    /** The colors of the generated images. */
    private static final int[] PALETTE = { 0x1565C0, 0xFFB74D, 0xFFFFFF, 0x2E7D32 };
    /** Where the interpolation tiles draw the 8 x 8 image, scaled 12 times. */
    private static final int SCALED_X = 27;
    private static final int SCALED_Y = 11;
    private static final int SCALED_SIZE = 96;

    private record Built(SwtTiles fills, ImageData fillsImage, ChecksTable geometry, ChecksTable rendering,
            ChecksTable regions, ChecksTable clipping) {
    }

    @Override
    public String id() {
        return "swt-graphics-advanced";
    }

    @Override
    public String title() {
        return "Paths, transforms, patterns and clipping";
    }

    @Override
    public String category() {
        return SwtCategories.GRAPHICS;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Control build(Composite parent) {
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "The advanced graphics of GC : GDI+ on Windows, Cairo on Linux, Core Graphics on macOS. Each"
                + " tile is drawn offscreen into an image. Dashed gray outlines are the untransformed figures or the"
                + " shapes of a clip, red dots are vertices and control points.", SwtKit.TEXT_WIDTH);
        SwtKit.title(page, "Path and Transform");
        // the checks of the path and transform tiles compute the same geometry without drawing
        SwtKit.image(page, new SwtTiles(COLUMNS, TILE_WIDTH, TILE_HEIGHT, pathTiles()).paint());
        SwtKit.title(page, "Pattern, clipping, interpolation and text anti-aliasing");
        SwtTiles fills = new SwtTiles(COLUMNS, TILE_WIDTH, TILE_HEIGHT, fillTiles());
        ImageData fillsImage = fills.paint();
        SwtKit.image(page, fillsImage);
        List<Check> pending = List.of(Check.info("state", "pending"));
        Composite row = SwtKit.row(page, 12);
        ChecksTable geometry = ChecksTable.table(row, "Path and Transform", pending, 250, HALF_WIDTH);
        ChecksTable rendering = ChecksTable.table(row, "Pattern, Region and GC", pending, 250, HALF_WIDTH);
        Composite probes = SwtKit.row(page, 12);
        ChecksTable regions = ChecksTable.table(probes, "Pixel probes : patterns and regions", pending, 330,
                HALF_WIDTH);
        ChecksTable clipping = ChecksTable.table(probes, "Pixel probes : clipping, interpolation and text", pending,
                330, HALF_WIDTH);
        page.setData(DATA, new Built(fills, fillsImage, geometry, rendering, regions, clipping));
        if (!SwtMode.snapshot()) {
            UiStages.rounds(1).thenAccept(v -> complete(page));
        }
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        return UiStages.rounds(1).thenAccept(v -> complete(content));
    }

    /**
     * Computes the checks once the page is shown.
     */
    private static void complete(Control content) {
        if (content.isDisposed() || !(content.getData(DATA) instanceof Built built)) {
            return;
        }
        Display display = content.getDisplay();
        built.geometry().setChecks(geometryChecks(display));
        built.rendering().setChecks(renderingChecks(display));
        built.regions().setChecks(regionProbes(built.fills(), built.fillsImage()));
        built.clipping().setChecks(clippingProbes(built.fills(), built.fillsImage()));
    }

    // -------------------------------------------------------------------------------------------------- resources

    @FunctionalInterface
    private interface PathBuilder {
        void build(Path path);
    }

    /**
     * A new path built by {@code builder} : the caller disposes it.
     */
    private static Path path(Device device, PathBuilder builder) {
        Path path = new Path(device);
        try {
            builder.build(path);
            return path;
        } catch (RuntimeException e) {
            path.dispose();
            throw e;
        }
    }

    /**
     * Paints with a path, then disposes it.
     */
    private static void withPath(GC gc, PathBuilder builder, Consumer<Path> painter) {
        Path path = path(gc.getDevice(), builder);
        try {
            painter.accept(path);
        } finally {
            path.dispose();
        }
    }

    /**
     * An "F" (36 x 50) at ({@code x}, {@code y}) : asymmetric, its orientation shows the transform.
     */
    private static Path figure(Device device, float x, float y) {
        float[] points = { 36, 0, 36, 10, 12, 10, 12, 20, 28, 20, 28, 30, 12, 30, 12, 50, 0, 50 };
        return path(device, path -> {
            path.moveTo(x, y);
            for (int i = 0; i < points.length; i += 2) {
                path.lineTo(x + points[i], y + points[i + 1]);
            }
            path.close();
        });
    }

    /**
     * A pentagram path ({@link GcProbes#pentagram}).
     */
    private static Path pentagram(Device device, int cx, int cy, int radius) {
        int[] points = GcProbes.pentagram(cx, cy, radius);
        return path(device, path -> {
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) {
                path.lineTo(points[i], points[i + 1]);
            }
            path.close();
        });
    }

    /**
     * The outline of {@code text} in {@code font}, centered in a {@code width x height} area.
     */
    private static Path text(Device device, String text, int points, int width, int height) {
        Path outline = path(device, path -> path.addString(text, 0, 0, SwtKit.font(SWT.BOLD, points)));
        try {
            float[] bounds = new float[4];
            outline.getBounds(bounds);
            PathData data = outline.getPathData();
            Transform center = new Transform(device);
            try {
                center.translate((width - bounds[2]) / 2 - bounds[0], (height - bounds[3]) / 2 - bounds[1]);
                center.transform(data.points);
            } finally {
                center.dispose();
            }
            return new Path(device, data);
        } finally {
            outline.dispose();
        }
    }

    /**
     * A {@code width x height} image of the colors {@code rgb(x, y)} ({@code 0xRRGGBB}) : the caller disposes it.
     */
    private static Image image(Device device, int width, int height, IntBinaryOperator rgb) {
        ImageData data = new ImageData(width, height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                data.setPixel(x, y, rgb.applyAsInt(x, y));
            }
        }
        return new Image(device, data);
    }

    /**
     * The 8 x 8 image of the interpolation tiles : diagonal stripes of the 4 colors of {@link #PALETTE}.
     */
    private static int stripes(int x, int y) {
        return PALETTE[(x + y) % PALETTE.length];
    }

    /**
     * The 16 x 16 image of the image pattern : a diagonal stripe and a square on a light background.
     */
    private static int motif(int x, int y) {
        if ((x + y) % 16 < 4) {
            return 0xFF7043;
        }
        return x >= 9 && x < 14 && y >= 2 && y < 7 ? 0x5C6BC0 : 0xFFF8E1;
    }

    private static void fillAndDraw(GC gc, Path path, int fill) {
        gc.setBackground(SwtKit.color(fill));
        gc.fillPath(path);
        gc.setForeground(SwtKit.color(INK));
        gc.drawPath(path);
    }

    /**
     * The dashed gray outline of {@code path}.
     */
    private static void ghost(GC gc, Path path) {
        gc.setForeground(SwtKit.color(GHOST));
        gc.setLineWidth(1);
        gc.setLineDash(new int[] { 3, 3 });
        gc.drawPath(path);
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    private static void ghostRectangle(GC gc, int x, int y, int width, int height) {
        gc.setForeground(SwtKit.color(GHOST));
        gc.setLineWidth(1);
        gc.setLineDash(new int[] { 3, 3 });
        gc.drawRectangle(x, y, width, height);
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    private static void dots(GC gc, float... points) {
        gc.setBackground(SwtKit.color(ACCENT));
        for (int i = 0; i + 1 < points.length; i += 2) {
            gc.fillOval(Math.round(points[i]) - 3, Math.round(points[i + 1]) - 3, 6, 6);
        }
    }

    /**
     * Horizontal bands of 12 points, alternately {@code FILLS[4]} and {@code FILLS[5]}, over the whole area.
     */
    private static void bands(GC gc, int width, int height) {
        for (int y = 0; y < height; y += 12) {
            gc.setBackground(SwtKit.color(FILLS[y / 12 % 2 == 0 ? 4 : 5]));
            gc.fillRectangle(0, y, width, 12);
        }
    }

    // ------------------------------------------------------------------------------------------------- path tiles

    private static List<Tile> pathTiles() {
        List<Tile> tiles = new ArrayList<>();
        tiles.add(new Tile(LINES, (gc, w, h) -> withPath(gc, path -> {
            path.moveTo(15, 100);
            path.lineTo(60, 15);
            path.lineTo(100, 100);
            path.close();
            // a second figure, left open : filled as if closed, stroked open
            path.moveTo(80, 20);
            path.lineTo(140, 20);
            path.lineTo(110, 60);
        }, path -> {
            gc.setLineWidth(2);
            fillAndDraw(gc, path, FILLS[0]);
            dots(gc, 15, 100, 60, 15, 100, 100, 80, 20, 140, 20, 110, 60);
        })));
        tiles.add(new Tile("cubicTo", (gc, w, h) -> withPath(gc, path -> {
            path.moveTo(10, 90);
            path.cubicTo(40, 0, 110, 120, 140, 28);
        }, path -> {
            gc.setForeground(SwtKit.color(GHOST));
            gc.setLineDash(new int[] { 3, 3 });
            gc.drawPolyline(new int[] { 10, 90, 40, 0, 110, 120, 140, 28 });
            gc.setLineStyle(SWT.LINE_SOLID);
            gc.setForeground(SwtKit.color(INK));
            gc.setLineWidth(3);
            gc.drawPath(path);
            dots(gc, 40, 3, 110, 115);
        })));
        tiles.add(new Tile("quadTo", (gc, w, h) -> withPath(gc, path -> {
            path.moveTo(10, 104);
            path.quadTo(75, 4, 140, 104);
            path.close();
        }, path -> {
            gc.setLineWidth(2);
            fillAndDraw(gc, path, FILLS[1]);
            gc.setForeground(SwtKit.color(GHOST));
            gc.setLineDash(new int[] { 3, 3 });
            gc.drawPolyline(new int[] { 10, 104, 75, 4, 140, 104 });
            dots(gc, 75, 4);
        })));
        tiles.add(new Tile("addArc", (gc, w, h) -> {
            // a chord (an arc closed by a line), and a full circle
            withPath(gc, path -> {
                path.addArc(15, 10, 120, 98, 30, 250);
                path.close();
            }, path -> {
                gc.setLineWidth(2);
                fillAndDraw(gc, path, FILLS[2]);
            });
            withPath(gc, path -> path.addArc(55, 39, 40, 40, 0, 360), path -> {
                gc.setForeground(SwtKit.color(ACCENT));
                gc.setLineWidth(3);
                gc.drawPath(path);
            });
        }));
        tiles.add(new Tile(RECTANGLES, (gc, w, h) -> withPath(gc, path -> {
            path.addRectangle(12, 10, 126, 98);
            path.addRectangle(42, 34, 66, 50);
        }, path -> {
            gc.setFillRule(SWT.FILL_EVEN_ODD);
            fillAndDraw(gc, path, FILLS[3]);
        })));
        tiles.add(new Tile("addString", (gc, w, h) -> {
            Path path = text(gc.getDevice(), "SWT", 34, w, h);
            try {
                gc.setLineWidth(1);
                fillAndDraw(gc, path, FILLS[4]);
            } finally {
                path.dispose();
            }
        }));
        tiles.add(new Tile("FILL_EVEN_ODD", (gc, w, h) -> fillRule(gc, SWT.FILL_EVEN_ODD)));
        tiles.add(new Tile("FILL_WINDING", (gc, w, h) -> fillRule(gc, SWT.FILL_WINDING)));
        tiles.add(transformTile("translate(34, 26)", t -> t.translate(34, 26), FILLS[0]));
        tiles.add(transformTile("rotate(30)", t -> t.rotate(30), FILLS[1]));
        tiles.add(transformTile("scale(1.5, 0.7)", t -> t.scale(1.5f, 0.7f), FILLS[2]));
        tiles.add(transformTile("shear(0.6, 0)", t -> t.shear(0.6f, 0), FILLS[3]));
        tiles.add(new Tile("multiply (red, blue)", (gc, w, h) -> {
            // red : multiply(rotate) then multiply(translate) ; blue : the other order
            Device device = gc.getDevice();
            Path figure = figure(device, 10, 10);
            Transform rotate = new Transform(device);
            Transform translate = new Transform(device);
            Transform red = new Transform(device);
            Transform blue = new Transform(device);
            try {
                ghost(gc, figure);
                rotate.rotate(20);
                translate.translate(60, 0);
                red.multiply(rotate);
                red.multiply(translate);
                blue.multiply(translate);
                blue.multiply(rotate);
                gc.setAlpha(180);
                gc.setTransform(red);
                gc.setBackground(SwtKit.color(0xE53935));
                gc.fillPath(figure);
                gc.setTransform(blue);
                gc.setBackground(SwtKit.color(0x1E88E5));
                gc.fillPath(figure);
            } finally {
                gc.setTransform(null);
                blue.dispose();
                red.dispose();
                translate.dispose();
                rotate.dispose();
                figure.dispose();
            }
        }));
        tiles.add(new Tile("invert", (gc, w, h) -> {
            // the figure transformed, then transformed back by the product with the inverse : the ghost again
            Device device = gc.getDevice();
            Path figure = figure(device, CX - 18, CY - 25);
            Transform transform = new Transform(device);
            Transform inverse = new Transform(device);
            Transform back = new Transform(device);
            try {
                ghost(gc, figure);
                transform.translate(CX, CY);
                transform.rotate(40);
                transform.scale(1.3f, 0.9f);
                transform.translate(-CX, -CY);
                float[] elements = new float[6];
                transform.getElements(elements);
                inverse.setElements(elements[0], elements[1], elements[2], elements[3], elements[4], elements[5]);
                inverse.invert();
                back.multiply(transform);
                back.multiply(inverse);
                gc.setTransform(transform);
                gc.setAlpha(160);
                fillAndDraw(gc, figure, FILLS[5]);
                gc.setAlpha(255);
                gc.setTransform(back);
                gc.setForeground(SwtKit.color(ACCENT));
                gc.setLineWidth(2);
                gc.drawPath(figure);
            } finally {
                gc.setTransform(null);
                back.dispose();
                inverse.dispose();
                transform.dispose();
                figure.dispose();
            }
        }));
        tiles.add(new Tile("rotated drawString", (gc, w, h) -> {
            Transform transform = new Transform(gc.getDevice());
            try {
                // rotated around (22, 104), from 10 points away
                int middle = gc.getFontMetrics().getHeight() / 2;
                for (int i = 0; i < 4; i++) {
                    transform.identity();
                    transform.translate(22, 104);
                    transform.rotate(-30 * i);
                    gc.setTransform(transform);
                    gc.setForeground(SwtKit.color(i == 0 ? INK : FILLS[i + 2]));
                    gc.drawString("Transform " + 30 * i, 10, -middle, true);
                }
            } finally {
                gc.setTransform(null);
                transform.dispose();
            }
            dots(gc, 22, 104);
        }));
        tiles.add(new Tile("transform(points)", (gc, w, h) -> {
            float[] square = { -30, -30, 30, -30, 30, 30, -30, 30 };
            Transform transform = new Transform(gc.getDevice());
            try {
                transform.translate(CX, CY);
                float[] ghost = square.clone();
                transform.transform(ghost);
                transform.rotate(30);
                transform.scale(1.4f, 0.8f);
                float[] points = square.clone();
                transform.transform(points);
                gc.setForeground(SwtKit.color(GHOST));
                gc.setLineDash(new int[] { 3, 3 });
                gc.drawPolygon(round(ghost));
                gc.setLineStyle(SWT.LINE_SOLID);
                gc.setLineWidth(2);
                gc.setForeground(SwtKit.color(INK));
                gc.drawPolygon(round(points));
                dots(gc, points);
            } finally {
                transform.dispose();
            }
        }));
        tiles.add(new Tile("nested transforms", (gc, w, h) -> {
            Transform transform = new Transform(gc.getDevice());
            try {
                transform.translate(CX, CY);
                gc.setLineWidth(2);
                for (int i = 0; i < 12; i++) {
                    gc.setTransform(transform);
                    gc.setForeground(SwtKit.color(FILLS[i % FILLS.length]));
                    gc.drawRectangle(-40, -40, 80, 80);
                    transform.rotate(15);
                    transform.scale(0.88f, 0.88f);
                }
            } finally {
                gc.setTransform(null);
                transform.dispose();
            }
        }));
        tiles.add(new Tile("flattened Path", (gc, w, h) -> {
            Device device = gc.getDevice();
            Path circle = path(device, path -> path.addArc(25, 9, 100, 100, 0, 360));
            try {
                // a copy of the circle flattened to lines no farther than 8 points from the curve
                Path flat = new Path(device, circle, 8);
                try {
                    ghost(gc, circle);
                    gc.setAlpha(200);
                    gc.setLineWidth(2);
                    fillAndDraw(gc, flat, FILLS[0]);
                    gc.setAlpha(255);
                    dots(gc, flat.getPathData().points);
                } finally {
                    flat.dispose();
                }
            } finally {
                circle.dispose();
            }
        }));
        return tiles;
    }

    private static int[] round(float[] points) {
        int[] rounded = new int[points.length];
        for (int i = 0; i < points.length; i++) {
            rounded[i] = Math.round(points[i]);
        }
        return rounded;
    }

    private static void fillRule(GC gc, int rule) {
        Path star = pentagram(gc.getDevice(), 75, 62, 54);
        try {
            gc.setFillRule(rule);
            fillAndDraw(gc, star, FILLS[5]);
        } finally {
            star.dispose();
        }
    }

    /**
     * The "F" at the center, transformed by {@code operation} around the center of the tile, over its ghost.
     */
    private static Tile transformTile(String caption, Consumer<Transform> operation, int fill) {
        return new Tile(caption, (gc, w, h) -> {
            Device device = gc.getDevice();
            Path figure = figure(device, CX - 18, CY - 25);
            Transform transform = new Transform(device);
            try {
                ghost(gc, figure);
                transform.translate(CX, CY);
                operation.accept(transform);
                transform.translate(-CX, -CY);
                gc.setTransform(transform);
                fillAndDraw(gc, figure, fill);
            } finally {
                gc.setTransform(null);
                transform.dispose();
                figure.dispose();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------- fill tiles

    private static List<Tile> fillTiles() {
        List<Tile> tiles = new ArrayList<>();
        tiles.add(new Tile(GRADIENT, (gc, w, h) -> {
            Device device = gc.getDevice();
            Pattern horizontal = new Pattern(device, 10, 0, 140, 0, SwtKit.color(GRADIENT_FROM),
                    SwtKit.color(GRADIENT_TO));
            Pattern diagonal = new Pattern(device, 10, 62, 140, 108, SwtKit.color(FILLS[2]),
                    SwtKit.color(FILLS[4]));
            try {
                gc.setBackgroundPattern(horizontal);
                gc.fillRectangle(10, 10, 130, 44);
                gc.setBackgroundPattern(diagonal);
                gc.fillOval(10, 62, 130, 46);
            } finally {
                gc.setBackgroundPattern(null);
                diagonal.dispose();
                horizontal.dispose();
            }
        }));
        tiles.add(new Tile(GRADIENT_ALPHA, (gc, w, h) -> {
            checker(gc, w, h);
            Pattern fade = new Pattern(gc.getDevice(), 10, 0, 140, 0, SwtKit.color(0xC62828), 255,
                    SwtKit.color(0xC62828), 0);
            try {
                gc.setBackgroundPattern(fade);
                gc.fillRectangle(10, 10, 130, 98);
            } finally {
                gc.setBackgroundPattern(null);
                fade.dispose();
            }
        }));
        tiles.add(new Tile(IMAGE_PATTERN, (gc, w, h) -> {
            Device device = gc.getDevice();
            Image motif = image(device, 16, 16, SwtAdvancedGraphicsPage::motif);
            try {
                Pattern pattern = new Pattern(device, motif);
                try {
                    gc.setBackgroundPattern(pattern);
                    gc.fillOval(8, 8, 134, 102);
                    gc.setBackgroundPattern(null);
                    gc.setLineWidth(2);
                    gc.drawOval(8, 8, 134, 102);
                } finally {
                    gc.setBackgroundPattern(null);
                    pattern.dispose();
                }
            } finally {
                motif.dispose();
            }
        }));
        tiles.add(new Tile("foreground pattern", (gc, w, h) -> {
            Pattern pattern = new Pattern(gc.getDevice(), 10, 0, 140, 0, SwtKit.color(FILLS[0]),
                    SwtKit.color(FILLS[3]));
            try {
                gc.setForegroundPattern(pattern);
                gc.setLineWidth(10);
                gc.setLineCap(SWT.CAP_ROUND);
                gc.setLineJoin(SWT.JOIN_ROUND);
                gc.drawPolyline(new int[] { 14, 50, 44, 12, 74, 50, 104, 12, 136, 50 });
                gc.setFont(SwtKit.font(SWT.BOLD, 14));
                gc.drawString("Pattern", 14, 66, true);
            } finally {
                gc.setForegroundPattern(null);
                pattern.dispose();
            }
        }));
        tiles.add(new Tile("repeated gradient", (gc, w, h) -> {
            // a gradient 30 points long repeats beyond its end points
            Pattern pattern = new Pattern(gc.getDevice(), 10, 0, 40, 30, SwtKit.color(INK),
                    SwtKit.color(FILLS[1]));
            try {
                gc.setBackgroundPattern(pattern);
                gc.fillRectangle(10, 10, 130, 98);
            } finally {
                gc.setBackgroundPattern(null);
                pattern.dispose();
            }
        }));
        tiles.add(new Tile("fillPath, pattern", (gc, w, h) -> {
            Device device = gc.getDevice();
            Path star = pentagram(device, 75, 62, 54);
            Pattern pattern = new Pattern(device, 20, 8, 130, 110, SwtKit.color(FILLS[4]), 255,
                    SwtKit.color(FILLS[0]), 96);
            try {
                gc.setFillRule(SWT.FILL_WINDING);
                gc.setBackgroundPattern(pattern);
                gc.fillPath(star);
                gc.setBackgroundPattern(null);
                gc.drawPath(star);
            } finally {
                gc.setBackgroundPattern(null);
                pattern.dispose();
                star.dispose();
            }
        }));
        tiles.add(regionTile(REGION_ADD, FILLS[0], region -> {
            region.add(10, 10, 80, 50);
            region.add(60, 40, 80, 68);
        }, gc -> {
            ghostRectangle(gc, 10, 10, 80, 50);
            ghostRectangle(gc, 60, 40, 80, 68);
        }));
        tiles.add(regionTile(REGION_POLYGON, FILLS[1], region -> region.add(new int[] { 75, 8, 142, 110, 8, 110 }),
                null));
        tiles.add(regionTile(REGION_SUBTRACT, FILLS[2], region -> {
            region.add(10, 10, 130, 98);
            region.subtract(40, 30, 70, 58);
            region.subtract(new int[] { 10, 108, 40, 108, 10, 78 });
        }, null));
        tiles.add(regionTile(REGION_INTERSECT, FILLS[3], region -> {
            region.add(10, 10, 90, 70);
            region.intersect(50, 40, 90, 70);
        }, gc -> {
            ghostRectangle(gc, 10, 10, 90, 70);
            ghostRectangle(gc, 50, 40, 90, 70);
        }));
        tiles.add(new Tile(PATH_CLIP, (gc, w, h) -> withPath(gc, path -> path.addArc(20, 4, 110, 110, 0, 360),
                path -> {
                    gc.setClipping(path);
                    bands(gc, w, h);
                    // (Rectangle) null : the documented reset ; setClipping((Path) null) throws on macOS (SWT 3.132)
                    gc.setClipping((Rectangle) null);
                    ghost(gc, path);
                })));
        tiles.add(new Tile("text Path clip", (gc, w, h) -> {
            Device device = gc.getDevice();
            Path path = text(device, "SWT", 40, w, h);
            Pattern pattern = new Pattern(device, 0, 0, w, h, SwtKit.color(0x1565C0), SwtKit.color(0xE53935));
            try {
                gc.setClipping(path);
                gc.setBackgroundPattern(pattern);
                gc.fillRectangle(0, 0, w, h);
                gc.setBackgroundPattern(null);
                gc.setForeground(SwtKit.color(BACKGROUND));
                gc.setLineWidth(3);
                for (int x = -h; x < w; x += 10) {
                    gc.drawLine(x, h, x + h, 0);
                }
                gc.setClipping((Rectangle) null);
                gc.setLineWidth(1);
                gc.setForeground(SwtKit.color(INK));
                gc.drawPath(path);
            } finally {
                gc.setBackgroundPattern(null);
                pattern.dispose();
                path.dispose();
            }
        }));
        tiles.add(interpolationTile(NONE, SWT.NONE));
        tiles.add(interpolationTile(LOW, SWT.LOW));
        tiles.add(interpolationTile(HIGH, SWT.HIGH));
        tiles.add(textTile(TEXT_OFF, SWT.OFF));
        tiles.add(textTile(TEXT_ON, SWT.ON));
        tiles.add(new Tile(ROTATED_CLIP, (gc, w, h) -> {
            // the clipping rectangle is transformed by the transform of the GC when it is set
            Transform transform = new Transform(gc.getDevice());
            try {
                transform.translate(CX, CY);
                transform.rotate(25);
                transform.translate(-CX, -CY);
                gc.setTransform(transform);
                gc.setClipping(35, 29, 80, 60);
                gc.setTransform(null);
                bands(gc, w, h);
                gc.setClipping((Rectangle) null);
                gc.setTransform(transform);
                ghostRectangle(gc, 35, 29, 80, 60);
            } finally {
                gc.setTransform(null);
                transform.dispose();
            }
        }));
        return tiles;
    }

    /**
     * A checkerboard of 10 points squares, white at the top left corner.
     */
    private static void checker(GC gc, int width, int height) {
        for (int y = 0; y < height; y += 10) {
            for (int x = 0; x < width; x += 10) {
                gc.setBackground(SwtKit.color((x + y) / 10 % 2 == 0 ? WHITE : CHECKER));
                gc.fillRectangle(x, y, 10, 10);
            }
        }
    }

    /**
     * The area filled with {@code fill} through the clipping of the region built by {@code builder}, then
     * {@code outlines} (if any) without clipping.
     */
    private static Tile regionTile(String caption, int fill, Consumer<Region> builder, Consumer<GC> outlines) {
        return new Tile(caption, (gc, w, h) -> {
            Region region = new Region(gc.getDevice());
            try {
                builder.accept(region);
                gc.setClipping(region);
                gc.setBackground(SwtKit.color(fill));
                gc.fillRectangle(0, 0, w, h);
            } finally {
                gc.setClipping((Region) null);
                region.dispose();
            }
            if (outlines != null) {
                outlines.accept(gc);
            }
        });
    }

    /**
     * The 8 x 8 stripes image scaled 12 times with {@code interpolation}.
     */
    private static Tile interpolationTile(String caption, int interpolation) {
        return new Tile(caption, (gc, w, h) -> {
            Image image = image(gc.getDevice(), 8, 8, SwtAdvancedGraphicsPage::stripes);
            try {
                gc.setInterpolation(interpolation);
                gc.drawImage(image, 0, 0, 8, 8, SCALED_X, SCALED_Y, SCALED_SIZE, SCALED_SIZE);
            } finally {
                image.dispose();
            }
        });
    }

    private static Tile textTile(String caption, int antialias) {
        return new Tile(caption, (gc, w, h) -> {
            gc.setTextAntialias(antialias);
            gc.setFont(SwtKit.font(SWT.NORMAL, 11));
            int line = gc.getFontMetrics().getHeight();
            gc.drawString("Anti-aliasing", 8, 8, true);
            gc.setFont(SwtKit.font(SWT.BOLD, 11));
            gc.drawString("Wg 0123", 8, 8 + line, true);
            gc.setFont(SwtKit.font(SWT.ITALIC, 11));
            gc.drawString("Quarkus", 8, 8 + 2 * line, true);
        });
    }

    // ------------------------------------------------------------------------------------------------------ checks

    private static String bounds(Path path) {
        float[] bounds = new float[4];
        path.getBounds(bounds);
        return SwtChecks.num(bounds[0]) + "," + SwtChecks.num(bounds[1]) + " " + SwtChecks.num(bounds[2]) + "x"
                + SwtChecks.num(bounds[3]);
    }

    private static String elements(Transform transform) {
        float[] elements = new float[6];
        transform.getElements(elements);
        StringBuilder sb = new StringBuilder();
        for (float element : elements) {
            sb.append(sb.isEmpty() ? "" : " ").append(SwtChecks.num(element));
        }
        return sb.toString();
    }

    private static String segments(PathData data) {
        StringBuilder sb = new StringBuilder();
        for (byte type : data.types) {
            sb.append(sb.isEmpty() ? "" : " ").append(switch (type) {
                case SWT.PATH_MOVE_TO -> "MOVE_TO";
                case SWT.PATH_LINE_TO -> "LINE_TO";
                case SWT.PATH_QUAD_TO -> "QUAD_TO";
                case SWT.PATH_CUBIC_TO -> "CUBIC_TO";
                case SWT.PATH_CLOSE -> "CLOSE";
                default -> String.valueOf(type);
            });
        }
        return sb.append(", ").append(data.points.length / 2).append(" points").toString();
    }

    /**
     * {@code action} with a new path, disposed after.
     */
    private static Object onPath(Display display, PathBuilder builder, Function<Path, ?> action) {
        Path path = path(display, builder);
        try {
            return action.apply(path);
        } finally {
            path.dispose();
        }
    }

    /**
     * {@code action} with a new transform, disposed after.
     */
    private static Object onTransform(Display display, Consumer<Transform> builder, Function<Transform, ?> action) {
        Transform transform = new Transform(display);
        try {
            builder.accept(transform);
            return action.apply(transform);
        } finally {
            transform.dispose();
        }
    }

    /**
     * {@code action} with a new region, disposed after.
     */
    private static Object onRegion(Display display, Consumer<Region> builder, Function<Region, ?> action) {
        Region region = new Region(display);
        try {
            builder.accept(region);
            return action.apply(region);
        } finally {
            region.dispose();
        }
    }

    private static List<Check> geometryChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("Path.getBounds : addRectangle(10, 20, 30, 40)", "10.000,20.000 30.000x40.000",
                () -> onPath(display, path -> path.addRectangle(10, 20, 30, 40), SwtAdvancedGraphicsPage::bounds)));
        checks.add(SwtChecks.expect("Path.getBounds : triangle of lineTo", "15.000,15.000 85.000x85.000",
                () -> onPath(display, path -> {
                    path.moveTo(15, 100);
                    path.lineTo(60, 15);
                    path.lineTo(100, 100);
                    path.close();
                }, SwtAdvancedGraphicsPage::bounds)));
        checks.add(SwtChecks.info("Path.getBounds : cubicTo (control points may count)",
                () -> onPath(display, path -> {
                    path.moveTo(10, 90);
                    path.cubicTo(40, 0, 110, 120, 140, 28);
                }, SwtAdvancedGraphicsPage::bounds)));
        checks.add(SwtChecks.info("Path.getBounds : addString(\"SWT\", 0, 0, bold 34)",
                () -> onPath(display, path -> path.addString("SWT", 0, 0, SwtKit.font(SWT.BOLD, 34)),
                        SwtAdvancedGraphicsPage::bounds)));
        checks.add(SwtChecks.expect("Path.getCurrentPoint : after lineTo(70, 80)", "70.000,80.000",
                () -> onPath(display, path -> {
                    path.moveTo(10, 10);
                    path.lineTo(70, 80);
                }, path -> {
                    float[] point = new float[2];
                    path.getCurrentPoint(point);
                    return SwtChecks.num(point[0]) + "," + SwtChecks.num(point[1]);
                })));
        checks.add(SwtChecks.info("Path.getPathData : moveTo, lineTo, lineTo, close",
                () -> onPath(display, path -> {
                    path.moveTo(10, 10);
                    path.lineTo(70, 10);
                    path.lineTo(40, 60);
                    path.close();
                }, path -> segments(path.getPathData()))));
        checks.add(SwtChecks.info("new Path(device, circle, flatness 8) : segments",
                () -> onPath(display, path -> path.addArc(25, 9, 100, 100, 0, 360), circle -> {
                    Path flat = new Path(display, circle, 8);
                    try {
                        return segments(flat.getPathData());
                    } finally {
                        flat.dispose();
                    }
                })));
        // macOS : Path.contains tests the area with NSBezierPath.containsPoint, under the winding rule of the path
        // (never set : non-zero) and not the fill rule of the GC given "to use when testing for containment" ("TODO -
        // see windows" in Path.contains) : the center of the pentagram is inside, a Cocoa SWT bug
        checks.add(contains(display, "Path.contains : center of the pentagram, FILL_EVEN_ODD", SWT.FILL_EVEN_ODD,
                SwtMode.pick(true, false, false)));
        checks.add(contains(display, "Path.contains : center of the pentagram, FILL_WINDING", SWT.FILL_WINDING,
                true));
        checks.add(SwtChecks.expect("Path.contains, addRectangle(10, 20, 30, 40), line width 6 : outline at (10,"
                + " 40), outline at (25, 40), fill at (25, 40), fill at (60, 40)", "true false true false", () -> {
                    Image image = new Image(display, 8, 8);
                    GC gc = new GC(image);
                    try {
                        gc.setLineWidth(6);
                        return onPath(display, path -> path.addRectangle(10, 20, 30, 40),
                                path -> path.contains(10, 40, gc, true) + " " + path.contains(25, 40, gc, true) + " "
                                        + path.contains(25, 40, gc, false) + " " + path.contains(60, 40, gc, false));
                    } finally {
                        gc.dispose();
                        image.dispose();
                    }
                }));
        checks.add(SwtChecks.expect("Transform : translate(10, 20), rotate(90)", "0.000 1.000 -1.000 0.000 10.000"
                + " 20.000", () -> onTransform(display, t -> {
                    t.translate(10, 20);
                    t.rotate(90);
                }, SwtAdvancedGraphicsPage::elements)));
        checks.add(SwtChecks.expect("Transform : translate(5, 7), scale(2, 3), transform(1, 1)", "7.000,10.000",
                () -> onTransform(display, t -> {
                    t.translate(5, 7);
                    t.scale(2, 3);
                }, t -> {
                    float[] point = { 1, 1 };
                    t.transform(point);
                    return SwtChecks.num(point[0]) + "," + SwtChecks.num(point[1]);
                })));
        // macOS : Transform.shear puts shearX in m12 and shearY in m21 of the NSAffineTransformStruct, where
        // x' = m11 x + m21 y + tX and y' = m12 x + m22 y + tY : shear(0.5, 0) shears vertically (the shear(0.6, 0)
        // tile too), a Cocoa SWT bug against the Javadoc of Transform.shear ("the shear factor in the X direction")
        checks.add(SwtChecks.expect("Transform : shear(0.5, 0)", SwtMode.pick("1.000 0.500 0.000 1.000 0.000 0.000",
                "1.000 0.000 0.500 1.000 0.000 0.000", "1.000 0.000 0.500 1.000 0.000 0.000"),
                () -> onTransform(display, t -> t.shear(0.5f, 0), SwtAdvancedGraphicsPage::elements)));
        checks.add(SwtChecks.expect("Transform : translate(10, 0).multiply(scale(2, 2))",
                "2.000 0.000 0.000 2.000 10.000 0.000", () -> onTransform(display, t -> t.translate(10, 0), t -> {
                    Transform scale = new Transform(display, 2, 0, 0, 2, 0, 0);
                    try {
                        t.multiply(scale);
                        return elements(t);
                    } finally {
                        scale.dispose();
                    }
                })));
        checks.add(SwtChecks.expect("Transform : (2, 0, 0, 4, 10, 20).invert()",
                "0.500 0.000 0.000 0.250 -5.000 -5.000", () -> {
                    Transform t = new Transform(display, 2, 0, 0, 4, 10, 20);
                    try {
                        t.invert();
                        return elements(t);
                    } finally {
                        t.dispose();
                    }
                }));
        checks.add(SwtChecks.expect("Transform : invert() of a singular matrix (1, 2, 2, 4, 0, 0)",
                "SWTException: Cannot invert matrix", () -> {
                    Transform t = new Transform(display, 1, 2, 2, 4, 0, 0);
                    try {
                        t.invert();
                        return "inverted : " + elements(t);
                    } catch (SWTException e) {
                        return "SWTException: " + e.getMessage();
                    } finally {
                        t.dispose();
                    }
                }));
        checks.add(SwtChecks.expect("Transform.isIdentity() : new, after rotate(30), after identity()",
                "true false true", () -> onTransform(display, t -> {
                }, t -> {
                    boolean created = t.isIdentity();
                    t.rotate(30);
                    boolean rotated = t.isIdentity();
                    t.identity();
                    return created + " " + rotated + " " + t.isIdentity();
                })));
        checks.add(SwtChecks.expect("new Transform(device, float[6]) : getElements", "1.000 0.500 -0.500 1.000"
                + " 3.000 4.000", () -> {
                    Transform t = new Transform(display, new float[] { 1, 0.5f, -0.5f, 1, 3, 4 });
                    try {
                        return elements(t);
                    } finally {
                        t.dispose();
                    }
                }));
        checks.add(SwtChecks.expect("GC.setTransform(rotate(30)) : GC.getTransform", "0.866 0.500 -0.500 0.866"
                + " 0.000 0.000", () -> {
                    Image image = new Image(display, 8, 8);
                    GC gc = new GC(image);
                    Transform set = new Transform(display);
                    Transform get = new Transform(display);
                    try {
                        set.rotate(30);
                        gc.setTransform(set);
                        gc.getTransform(get);
                        return elements(get);
                    } finally {
                        get.dispose();
                        set.dispose();
                        gc.dispose();
                        image.dispose();
                    }
                }));
        return checks;
    }

    /**
     * {@code Path.contains} at the center of a pentagram, with the fill rule of the {@code GC}.
     */
    private static Check contains(Display display, String name, int rule, boolean expected) {
        return SwtChecks.expect(name, expected, () -> {
            Image image = new Image(display, 8, 8);
            GC gc = new GC(image);
            Path star = pentagram(display, 75, 62, 54);
            try {
                gc.setFillRule(rule);
                return star.contains(75, 62, gc, false);
            } finally {
                star.dispose();
                gc.dispose();
                image.dispose();
            }
        });
    }

    private static List<Check> renderingChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("Pattern : linear, alpha 255 to 0, isDisposed()", false, () -> {
            Pattern pattern = new Pattern(display, 0, 0, 100, 0, SwtKit.color(0xC62828), 255,
                    SwtKit.color(0xC62828), 0);
            try {
                return pattern.isDisposed();
            } finally {
                pattern.dispose();
            }
        }));
        // the GC of SWT 3.132 on Windows keeps a copy of the pattern (for its zoom levels) : getBackgroundPattern()
        // returns that copy
        Pattern[] used = new Pattern[2];
        checks.add(SwtChecks.expect("Pattern : image, GC.setBackgroundPattern then getBackgroundPattern() != null,"
                + " isDisposed() after dispose()", "true true", () -> {
                    Image motif = image(display, 16, 16, SwtAdvancedGraphicsPage::motif);
                    Image image = new Image(display, 8, 8);
                    GC gc = new GC(image);
                    Pattern pattern = new Pattern(display, motif);
                    try {
                        gc.setBackgroundPattern(pattern);
                        used[0] = pattern;
                        used[1] = gc.getBackgroundPattern();
                        gc.fillRectangle(0, 0, 8, 8);
                        gc.setBackgroundPattern(null);
                        pattern.dispose();
                        return (used[1] != null) + " " + pattern.isDisposed();
                    } finally {
                        if (!pattern.isDisposed()) {
                            pattern.dispose();
                        }
                        gc.dispose();
                        image.dispose();
                        motif.dispose();
                    }
                }));
        checks.add(Check.info("GC.getBackgroundPattern() is the pattern set (Windows : a copy)",
                used[0] != null && used[0] == used[1]));
        checks.add(SwtChecks.expect("Region.add two rectangles : getBounds()", "10,10 130x98",
                () -> onRegion(display, r -> {
                    r.add(10, 10, 80, 50);
                    r.add(60, 40, 80, 68);
                }, r -> SwtChecks.rect(r.getBounds()))));
        checks.add(SwtChecks.expect("Region.contains : in a rectangle, between them", "true false",
                () -> onRegion(display, r -> {
                    r.add(10, 10, 80, 50);
                    r.add(60, 40, 80, 68);
                }, r -> r.contains(20, 20) + " " + r.contains(120, 20))));
        checks.add(SwtChecks.expect("Region.add(polygon) : contains the centroid, a corner", "true false",
                () -> onRegion(display, r -> r.add(new int[] { 75, 8, 142, 110, 8, 110 }),
                        r -> r.contains(75, 76) + " " + r.contains(10, 10))));
        checks.add(SwtChecks.expect("Region.subtract : contains the hole, getBounds()", "false 10,10 130x98",
                () -> onRegion(display, r -> {
                    r.add(10, 10, 130, 98);
                    r.subtract(40, 30, 70, 58);
                }, r -> r.contains(75, 59) + " " + SwtChecks.rect(r.getBounds()))));
        checks.add(SwtChecks.expect("Region.intersect : getBounds()", "50,40 50x40", () -> onRegion(display, r -> {
            r.add(10, 10, 90, 70);
            r.intersect(50, 40, 90, 70);
        }, r -> SwtChecks.rect(r.getBounds()))));
        checks.add(SwtChecks.expect("Region.intersect with a disjoint rectangle : isEmpty()", true,
                () -> onRegion(display, r -> {
                    r.add(10, 10, 20, 20);
                    r.intersect(50, 50, 20, 20);
                }, Region::isEmpty)));
        checks.add(SwtChecks.expect("Region.intersects : an overlapping, a disjoint rectangle", "true false",
                () -> onRegion(display, r -> r.add(10, 10, 20, 20),
                        r -> r.intersects(25, 25, 20, 20) + " " + r.intersects(40, 40, 5, 5))));
        checks.add(SwtChecks.expect("Region.translate(5, 5) : getBounds()", "15,15 20x20",
                () -> onRegion(display, r -> {
                    r.add(10, 10, 20, 20);
                    r.translate(5, 5);
                }, r -> SwtChecks.rect(r.getBounds()))));
        checks.add(SwtChecks.expect("GC.setClipping(Region) : isClipped(), getClipping(), getClipping(Region)",
                "true 10,10 130x98 10,10 130x98", () -> {
                    Image image = new Image(display, 200, 200);
                    GC gc = new GC(image);
                    try {
                        return onRegion(display, r -> {
                            r.add(10, 10, 80, 50);
                            r.add(60, 40, 80, 68);
                        }, r -> {
                            gc.setClipping(r);
                            Region clip = new Region(display);
                            try {
                                gc.getClipping(clip);
                                return gc.isClipped() + " " + SwtChecks.rect(gc.getClipping()) + " "
                                        + SwtChecks.rect(clip.getBounds());
                            } finally {
                                clip.dispose();
                            }
                        });
                    } finally {
                        gc.dispose();
                        image.dispose();
                    }
                }));
        Image image = new Image(display, 200, 200);
        try {
            GC gc = new GC(image);
            try {
                checks.add(SwtChecks.expect("GC.setClipping(10, 20, 30, 40) : getClipping()", "10,20 30x40", () -> {
                    gc.setClipping(10, 20, 30, 40);
                    Rectangle clip = gc.getClipping();
                    gc.setClipping((Rectangle) null);
                    return SwtChecks.rect(clip);
                }));
                checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("new GC(image) : getAdvanced()",
                        false, gc::getAdvanced)));
                checks.add(SwtChecks.expect("setTransform : getAdvanced()", true, () -> {
                    Transform transform = new Transform(display);
                    try {
                        gc.setTransform(transform);
                        return gc.getAdvanced();
                    } finally {
                        gc.setTransform(null);
                        transform.dispose();
                    }
                }));
                checks.add(SwtChecks.expect("setInterpolation(HIGH) : getInterpolation()", "HIGH", () -> {
                    gc.setInterpolation(SWT.HIGH);
                    return GcProbes.interpolation(gc.getInterpolation());
                }));
                checks.add(SwtChecks.expect("setTextAntialias(OFF) : getTextAntialias()", "OFF", () -> {
                    gc.setTextAntialias(SWT.OFF);
                    return GcProbes.antialias(gc.getTextAntialias());
                }));
            } finally {
                gc.dispose();
            }
        } finally {
            image.dispose();
        }
        return checks;
    }

    /**
     * Pixel probes of the pattern and region tiles : exact inside the clipped fills, within a tolerance for the
     * gradients.
     */
    private static List<Check> regionProbes(SwtTiles tiles, ImageData image) {
        List<Check> checks = new ArrayList<>();
        checks.add(near("linear gradient : start", tiles, image, GRADIENT, 11, 30, GRADIENT_FROM, 10));
        checks.add(near("linear gradient : end", tiles, image, GRADIENT, 139, 30, GRADIENT_TO, 10));
        checks.add(near("gradient alpha 255 : the color", tiles, image, GRADIENT_ALPHA, 11, 15, 0xC62828, 10));
        checks.add(near("gradient alpha 0 : the checkerboard", tiles, image, GRADIENT_ALPHA, 138, 15, WHITE, 10));
        // the motif is tiled from the origin of the GC (GDI+ : exact texels at 100 %)
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, exact("image pattern : the stripe of the motif at (72, 58)",
                tiles, image, IMAGE_PATTERN, 72, 58, motif(72 % 16, 58 % 16))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, exact("image pattern : the square of the motif at (75, 52)",
                tiles, image, IMAGE_PATTERN, 75, 52, motif(75 % 16, 52 % 16))));
        checks.add(exact("Region.add(rect) : in the first rectangle", tiles, image, REGION_ADD, 20, 20, FILLS[0]));
        checks.add(exact("Region.add(rect) : in the second rectangle", tiles, image, REGION_ADD, 120, 100,
                FILLS[0]));
        checks.add(exact("Region.add(rect) : outside", tiles, image, REGION_ADD, 120, 20, BACKGROUND));
        checks.add(exact("Region.add(polygon) : inside", tiles, image, REGION_POLYGON, 75, 76, FILLS[1]));
        checks.add(exact("Region.add(polygon) : outside", tiles, image, REGION_POLYGON, 12, 12, BACKGROUND));
        checks.add(exact("Region.subtract : the frame", tiles, image, REGION_SUBTRACT, 25, 59, FILLS[2]));
        checks.add(exact("Region.subtract : the hole", tiles, image, REGION_SUBTRACT, 75, 59, BACKGROUND));
        checks.add(exact("Region.subtract(polygon) : the corner", tiles, image, REGION_SUBTRACT, 15, 103,
                BACKGROUND));
        checks.add(exact("Region.intersect : inside", tiles, image, REGION_INTERSECT, 75, 60, FILLS[3]));
        checks.add(exact("Region.intersect : outside", tiles, image, REGION_INTERSECT, 30, 25, BACKGROUND));
        return checks;
    }

    /**
     * Pixel probes of the clipping, interpolation and text tiles : exact inside the clips and in the image scaled
     * without interpolation, and the number of colors of the interpolated images and of the text.
     */
    private static List<Check> clippingProbes(SwtTiles tiles, ImageData image) {
        List<Check> checks = new ArrayList<>();
        checks.add(exact("setClipping(Path) : inside the circle", tiles, image, PATH_CLIP, 75, 54, FILLS[4]));
        checks.add(exact("setClipping(Path) : outside the circle", tiles, image, PATH_CLIP, 5, 5, BACKGROUND));
        checks.add(exact("rotated clipping : center", tiles, image, ROTATED_CLIP, 75, 54, FILLS[4]));
        checks.add(exact("rotated clipping : a corner of the unrotated rectangle", tiles, image, ROTATED_CLIP, 113,
                31, BACKGROUND));
        checks.add(exact("interpolation NONE : center of a source pixel", tiles, image, NONE, SCALED_X + 6,
                SCALED_Y + 6, PALETTE[0]));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "interpolation NONE : colors of the scaled image", PALETTE.length, () -> colors(tiles, image, NONE,
                        SCALED_X, SCALED_Y, SCALED_SIZE, SCALED_SIZE))));
        checks.add(SwtChecks.info("interpolation LOW : colors of the scaled image",
                () -> colors(tiles, image, LOW, SCALED_X, SCALED_Y, SCALED_SIZE, SCALED_SIZE)));
        checks.add(SwtChecks.run("interpolation HIGH : colors of the scaled image (more than NONE)", () -> {
            int none = colors(tiles, image, NONE, SCALED_X, SCALED_Y, SCALED_SIZE, SCALED_SIZE);
            int high = colors(tiles, image, HIGH, SCALED_X, SCALED_Y, SCALED_SIZE, SCALED_SIZE);
            if (high <= none) {
                throw new IllegalStateException(high + " colors, " + none + " with NONE : not interpolated");
            }
            return high;
        }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "text antialias OFF : colors (background, ink)", 2, () -> colors(tiles, image, TEXT_OFF))));
        checks.add(SwtChecks.run("text antialias ON : colors (more than 2)", () -> {
            int count = colors(tiles, image, TEXT_ON);
            if (count <= 2) {
                throw new IllegalStateException(count + " colors : not anti-aliased");
            }
            return count;
        }));
        return checks;
    }
}
