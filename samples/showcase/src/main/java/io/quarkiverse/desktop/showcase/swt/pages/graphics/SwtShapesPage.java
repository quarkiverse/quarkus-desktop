package io.quarkiverse.desktop.showcase.swt.pages.graphics;

import static io.quarkiverse.desktop.showcase.swt.core.SwtTiles.BACKGROUND;
import static io.quarkiverse.desktop.showcase.swt.core.SwtTiles.INK;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.ACCENT;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.FILLS;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.GHOST;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.blend;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.colors;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.distance;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.exact;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.near;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.opaque;
import static io.quarkiverse.desktop.showcase.swt.pages.graphics.GcProbes.rgb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.LineAttributes;
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
 * The drawing primitives of the SWT {@code GC} : lines, points, rectangles, round rectangles, ovals, arcs (a negative
 * extent included), polygons and polylines, gradients ({@code fillGradientRectangle}, vertical, horizontal and
 * reversed), line widths, styles, custom dashes ({@code setLineDash}), caps, joins and {@code LineAttributes} (a
 * fractional width, a dash offset, a miter limit), fill rules of polygons, alpha, anti-aliasing on and off, plain GDI
 * drawing ({@code setAdvanced(false)}), XOR mode, foreground and background colors, and focus rectangles.
 * <p>
 * Every tile is drawn offscreen into an image ({@link SwtTiles}) with anti-aliasing on : the {@code GC} is in advanced
 * mode, and SWT draws with GDI+ on Windows ({@code Gdip.Graphics_DrawLine}, {@code Graphics_DrawLines},
 * {@code Graphics_FillEllipse}, {@code Graphics_FillPie}, {@code Graphics_FillPolygon}, {@code Graphics_DrawPath},
 * {@code Pen_new}, {@code Pen_SetDashPattern}, {@code Pen_SetDashOffset}, {@code Pen_SetLineCap},
 * {@code Pen_SetLineJoin}, {@code Pen_SetMiterLimit}, {@code SolidBrush_new}, and {@code LinearGradientBrush_new}
 * between two {@code PointF} structs for the gradients). The tiles that call {@code setAdvanced(false)} draw with plain
 * GDI : {@code Rectangle}, {@code Ellipse}, {@code Pie}, {@code Arc}, {@code Polyline} with pens of
 * {@code ExtCreatePen} (a {@code LOGBRUSH} struct), {@code PatBlt} with {@code PATINVERT} in XOR mode
 * ({@code SetROP2(R2_XORPEN)}), {@code GradientFill} with {@code TRIVERTEX} and {@code GRADIENT_RECT} structs copied to
 * the native heap ({@code MoveMemory}). {@code drawFocus} borrows the device context of GDI+
 * ({@code Graphics_GetHDC}, {@code Region_GetHRGN}) for {@code DrawFocusRect} with a {@code RECT} struct. On Linux
 * the same calls go to Cairo ({@code cairo_move_to}, {@code cairo_line_to}, {@code cairo_arc}, {@code cairo_set_dash},
 * {@code cairo_set_line_cap}, {@code cairo_set_line_join}, {@code cairo_set_fill_rule},
 * {@code cairo_pattern_create_linear}...), on macOS to Core Graphics through {@code NSBezierPath}.
 * <p>
 * Why it matters for a native executable : each of these calls is a JNI method of SWT, resolved only when it is first
 * called, and the structs ({@code RECT}, {@code PointF}, {@code LOGBRUSH}, {@code TRIVERTEX}, {@code GRADIENT_RECT})
 * are Java objects whose fields the C code reads and writes with {@code GetFieldID} : a struct or a field missing from
 * the JNI configuration of the executable fails with a {@code NoSuchFieldError}, or crashes, at the first drawing that
 * needs it, never at startup. The tiles reach them all, and the snapshot compares every pixel with the JVM.
 * <p>
 * Checks : the state of a {@code GC} (each getter after its setter), and pixel probes where the result is exact
 * (inside the fills, fill rules, the colors of an aliased drawing, the XOR results and the GDI rectangle edges on
 * Windows) or within a tolerance (blending and gradients round differently with GDI+, Cairo and Core Graphics).
 */
@Singleton
public class SwtShapesPage implements SwtPage {

    private static final int COLUMNS = 6;
    private static final int TILE_WIDTH = 166;
    private static final int TILE_HEIGHT = 150;
    private static final int HALF_WIDTH = 494;
    private static final String DATA = SwtShapesPage.class.getName();

    private static final String RECTANGLE = "fill/drawRectangle";
    private static final String ROUND_RECTANGLE = "fillRoundRectangle";
    private static final String OVAL = "fill/drawOval";
    private static final String ARC = "fillArc, drawArc";
    private static final String POLYGON = "fill/drawPolygon";
    private static final String VERTICAL = "vertical, reversed";
    private static final String HORIZONTAL = "horizontal, GDI";
    private static final String ALPHA = "setAlpha 128, 64";
    private static final String COLORS = "setFore/Background";
    private static final String EVEN_ODD = "FILL_EVEN_ODD";
    private static final String WINDING = "FILL_WINDING";
    private static final String ALIASED = "setAntialias(OFF)";
    private static final String ANTIALIASED = "setAntialias(ON)";
    private static final String GDI = "setAdvanced(false)";
    private static final String XOR = "setXORMode(true)";

    /** The colors of the gradients. */
    private static final int GRADIENT_TOP = 0x1565C0;
    private static final int GRADIENT_BOTTOM = 0xFFE082;
    private static final int GRADIENT_LEFT = 0x2E7D32;
    private static final int GRADIENT_RIGHT = 0xFFF59D;
    /** The colors filled in XOR mode. */
    private static final int XOR_A = 0x1E88E5;
    private static final int XOR_B = 0xFFB300;
    /** The colors of the foreground and background tile. */
    private static final int FOREGROUND = 0xC62828;
    private static final int BACKGROUND_FILL = 0xFFF59D;

    private record Built(SwtTiles tiles, ImageData image, ChecksTable state, ChecksTable probes) {
    }

    @Override
    public String id() {
        return "swt-graphics-shapes";
    }

    @Override
    public String title() {
        return "Shapes, lines and colors";
    }

    @Override
    public String category() {
        return SwtCategories.GRAPHICS;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "The drawing primitives of GC, painted offscreen into image tiles with anti-aliasing on : the"
                + " GC is then in advanced mode (GDI+ on Windows, Cairo on Linux, Core Graphics on macOS). The tiles"
                + " that turn anti-aliasing off draw aliased, those that call setAdvanced(false) draw with plain GDI on"
                + " Windows. Caps : FLAT, ROUND, SQUARE (red lines : the end points) ; joins : MITER, ROUND, BEVEL (red"
                + " lines : the center lines) ; line attributes : a miter limit of 2, then 10.",
                SwtKit.TEXT_WIDTH);
        SwtTiles tiles = new SwtTiles(COLUMNS, TILE_WIDTH, TILE_HEIGHT, tiles());
        ImageData image = tiles.paint();
        SwtKit.image(page, image);
        Composite row = SwtKit.row(page, 12);
        List<Check> pending = List.of(Check.info("state", "pending"));
        ChecksTable state = ChecksTable.table(row, "GC state", pending, 250, HALF_WIDTH);
        ChecksTable probes = ChecksTable.table(row, "Pixel probes", pending, 300, HALF_WIDTH);
        page.setData(DATA, new Built(tiles, image, state, probes));
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
        built.state().setChecks(stateChecks(content.getDisplay()));
        built.probes().setChecks(probeChecks(built.tiles(), built.image()));
    }

    // ------------------------------------------------------------------------------------------------------- tiles

    private static List<Tile> tiles() {
        List<Tile> tiles = new ArrayList<>();
        // primitives
        tiles.add(new Tile("drawLine, drawPoint", (gc, w, h) -> {
            for (int i = 0; i < 6; i++) {
                gc.setForeground(SwtKit.color(FILLS[i]));
                gc.setLineWidth(1 + i / 2);
                gc.drawLine(8, h - 8, 8 + i * 17, 8);
            }
            gc.setForeground(SwtKit.color(INK));
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 6; y++) {
                    gc.drawPoint(104 + x * 7, 62 + y * 7);
                }
            }
        }));
        tiles.add(new Tile(RECTANGLE, (gc, w, h) -> {
            gc.setBackground(SwtKit.color(FILLS[0]));
            gc.fillRectangle(15, 12, 120, 94);
            gc.drawRectangle(15, 12, 120, 94);
            gc.setLineWidth(3);
            gc.drawRectangle(45, 37, 60, 44);
        }));
        tiles.add(new Tile(ROUND_RECTANGLE, (gc, w, h) -> {
            gc.setBackground(SwtKit.color(FILLS[1]));
            gc.fillRoundRectangle(12, 12, 126, 94, 44, 30);
            gc.drawRoundRectangle(12, 12, 126, 94, 44, 30);
            gc.setLineWidth(2);
            gc.drawRoundRectangle(38, 40, 74, 38, 38, 38);
        }));
        tiles.add(new Tile(OVAL, (gc, w, h) -> {
            gc.setBackground(SwtKit.color(FILLS[2]));
            gc.fillOval(10, 12, 130, 94);
            gc.drawOval(10, 12, 130, 94);
            gc.setLineWidth(2);
            gc.drawOval(50, 34, 50, 50);
        }));
        tiles.add(new Tile(ARC, (gc, w, h) -> {
            // a pie from 30 to 280 degrees (counterclockwise from 3 o'clock), then 120 degrees clockwise
            gc.setBackground(SwtKit.color(FILLS[3]));
            gc.fillArc(25, 9, 100, 100, 30, 250);
            gc.drawArc(25, 9, 100, 100, 30, 250);
            gc.setForeground(SwtKit.color(ACCENT));
            gc.setLineWidth(3);
            gc.drawArc(50, 34, 50, 50, 0, -120);
        }));
        tiles.add(new Tile(POLYGON, (gc, w, h) -> {
            int[] star = new int[28];
            for (int i = 0; i < 14; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 7;
                int radius = i % 2 == 0 ? 52 : 24;
                star[2 * i] = (int) Math.round(75 + radius * Math.cos(angle));
                star[2 * i + 1] = (int) Math.round(60 + radius * Math.sin(angle));
            }
            gc.setBackground(SwtKit.color(FILLS[4]));
            gc.fillPolygon(star);
            gc.drawPolygon(star);
        }));
        // lines
        tiles.add(new Tile("drawPolyline", (gc, w, h) -> {
            gc.setLineWidth(3);
            gc.setLineJoin(SWT.JOIN_ROUND);
            gc.drawPolyline(new int[] { 8, 100, 30, 20, 52, 100, 74, 20, 96, 100, 118, 20, 142, 100 });
            gc.setForeground(SwtKit.color(FILLS[0]));
            gc.setLineWidth(2);
            int[] wave = new int[2 * 27];
            for (int i = 0; i < 27; i++) {
                wave[2 * i] = 10 + i * 5;
                wave[2 * i + 1] = (int) Math.round(60 + 30 * Math.sin(i * Math.PI / 6.5));
            }
            gc.drawPolyline(wave);
        }));
        tiles.add(new Tile("setLineWidth 1 to 6", (gc, w, h) -> {
            for (int i = 0; i < 6; i++) {
                gc.setLineWidth(i + 1);
                gc.drawLine(10, 12 + i * 19, 140, 12 + i * 19);
            }
        }));
        tiles.add(new Tile("setLineStyle", (gc, w, h) -> {
            int[] styles = { SWT.LINE_SOLID, SWT.LINE_DASH, SWT.LINE_DOT, SWT.LINE_DASHDOT, SWT.LINE_DASHDOTDOT };
            String[] names = { "SOLID", "DASH", "DOT", "DASHDOT", "DASHDOTDOT" };
            gc.setLineWidth(2);
            gc.setFont(SwtKit.font(SWT.NORMAL, 6));
            int ascent = gc.getFontMetrics().getHeight() / 2;
            for (int i = 0; i < styles.length; i++) {
                gc.setLineStyle(styles[i]);
                gc.drawLine(82, 13 + i * 23, 146, 13 + i * 23);
                gc.drawString(names[i], 2, 13 + i * 23 - ascent, true);
            }
        }));
        tiles.add(new Tile("setLineDash(int[])", (gc, w, h) -> {
            int[][] dashes = { { 12, 4 }, { 2, 2 }, { 16, 4, 4, 4 }, { 1, 5 }, { 20, 4, 8, 4 } };
            gc.setLineWidth(2);
            for (int i = 0; i < dashes.length; i++) {
                gc.setLineDash(dashes[i]);
                gc.drawLine(10, 10 + i * 13, 140, 10 + i * 13);
            }
            gc.setLineDash(new int[] { 8, 4 });
            gc.setLineWidth(3);
            gc.setForeground(SwtKit.color(FILLS[4]));
            gc.drawOval(20, 76, 110, 34);
        }));
        tiles.add(new Tile("setLineCap", (gc, w, h) -> {
            int[] caps = { SWT.CAP_FLAT, SWT.CAP_ROUND, SWT.CAP_SQUARE };
            gc.setLineWidth(14);
            for (int i = 0; i < caps.length; i++) {
                gc.setLineCap(caps[i]);
                gc.drawLine(45, 22 + i * 37, 105, 22 + i * 37);
            }
            guides(gc, new int[] { 45, 6, 45, 112 }, new int[] { 105, 6, 105, 112 });
        }));
        tiles.add(new Tile("setLineJoin", (gc, w, h) -> {
            int[] joins = { SWT.JOIN_MITER, SWT.JOIN_ROUND, SWT.JOIN_BEVEL };
            int[][] lines = new int[joins.length][];
            gc.setLineWidth(10);
            gc.setLineCap(SWT.CAP_FLAT);
            for (int i = 0; i < joins.length; i++) {
                int x = 12 + i * 46;
                lines[i] = new int[] { x, 24, x + 18, 86, x + 36, 24 };
                gc.setLineJoin(joins[i]);
                gc.drawPolyline(lines[i]);
            }
            guides(gc, lines);
        }));
        // fills and colors
        tiles.add(new Tile(VERTICAL, (gc, w, h) -> {
            gc.setForeground(SwtKit.color(GRADIENT_TOP));
            gc.setBackground(SwtKit.color(GRADIENT_BOTTOM));
            gc.fillGradientRectangle(10, 10, 62, 98, true);
            // a negative height reverses the gradient
            gc.fillGradientRectangle(78, 108, 62, -98, true);
        }));
        tiles.add(new Tile(HORIZONTAL, (gc, w, h) -> {
            // without GDI+ : GradientFill with TRIVERTEX and GRADIENT_RECT on Windows
            gc.setAdvanced(false);
            gc.setForeground(SwtKit.color(GRADIENT_LEFT));
            gc.setBackground(SwtKit.color(GRADIENT_RIGHT));
            gc.fillGradientRectangle(10, 10, 130, 98, false);
        }));
        tiles.add(new Tile(ALPHA, (gc, w, h) -> {
            gc.setBackground(SwtKit.color(FILLS[0]));
            gc.fillRectangle(10, 10, 70, 70);
            gc.setAlpha(128);
            gc.setBackground(SwtKit.color(FILLS[3]));
            gc.fillRectangle(50, 40, 90, 70);
            gc.setAlpha(64);
            gc.setBackground(SwtKit.color(INK));
            gc.fillOval(84, 6, 58, 40);
            gc.setAlpha(255);
        }));
        tiles.add(new Tile(COLORS, (gc, w, h) -> {
            // fills use the background, lines and text the foreground
            gc.setBackground(SwtKit.color(BACKGROUND_FILL));
            gc.setForeground(SwtKit.color(FOREGROUND));
            gc.fillRectangle(10, 8, 130, 44);
            gc.setLineWidth(2);
            gc.drawRectangle(10, 8, 130, 44);
            gc.setForeground(SwtKit.color(0x1565C0));
            gc.setBackground(SwtKit.color(0xC8E6C9));
            gc.drawString("opaque", 12, 62, false);
            gc.drawString("transparent", 12, 88, true);
        }));
        tiles.add(new Tile(EVEN_ODD, (gc, w, h) -> fillRule(gc, SWT.FILL_EVEN_ODD, FILLS[5])));
        tiles.add(new Tile(WINDING, (gc, w, h) -> fillRule(gc, SWT.FILL_WINDING, FILLS[5])));
        // modes
        tiles.add(new Tile(ALIASED, (gc, w, h) -> antialiased(gc, SWT.OFF)));
        tiles.add(new Tile(ANTIALIASED, (gc, w, h) -> antialiased(gc, SWT.ON)));
        tiles.add(new Tile(GDI, (gc, w, h) -> {
            gc.setAdvanced(false);
            gc.setBackground(SwtKit.color(FILLS[4]));
            gc.fillRectangle(10, 10, 40, 30);
            gc.drawRectangle(10, 10, 40, 30);
            gc.fillOval(60, 10, 80, 50);
            gc.drawOval(60, 10, 80, 50);
            gc.fillArc(98, 66, 44, 44, 90, 270);
            gc.drawArc(98, 66, 44, 44, 90, 270);
            gc.setLineWidth(3);
            gc.drawPolyline(new int[] { 10, 106, 30, 62, 50, 106, 70, 62, 90, 106 });
        }));
        tiles.add(new Tile(XOR, (gc, w, h) -> {
            // XOR is a raster operation of GDI (R2_XORPEN, PATINVERT) : without GDI+
            gc.setAdvanced(false);
            gc.setXORMode(true);
            gc.setBackground(SwtKit.color(XOR_A));
            gc.fillRectangle(10, 10, 80, 60);
            gc.setBackground(SwtKit.color(XOR_B));
            gc.fillRectangle(50, 40, 90, 70);
            // twice : erased (a rubber band)
            gc.setBackground(SwtKit.color(INK));
            gc.fillRectangle(16, 80, 26, 26);
            gc.fillRectangle(16, 80, 26, 26);
            gc.setXORMode(false);
            gc.setForeground(SwtKit.color(GHOST));
            gc.setLineStyle(SWT.LINE_DOT);
            gc.drawRectangle(16, 80, 26, 26);
        }));
        tiles.add(new Tile("drawFocus", (gc, w, h) -> {
            gc.drawFocus(10, 10, 130, 40);
            gc.drawFocus(30, 62, 90, 44);
            gc.drawFocus(40, 72, 70, 24);
        }));
        tiles.add(new Tile("setLineAttributes", (gc, w, h) -> {
            // a fractional width, a dash pattern of floats and its offset, round caps, a miter limit
            float[] dash = { 10, 6 };
            gc.setLineAttributes(new LineAttributes(4.5f, SWT.CAP_ROUND, SWT.JOIN_ROUND, SWT.LINE_CUSTOM, dash, 0, 10));
            gc.drawLine(10, 14, 140, 14);
            gc.setLineAttributes(new LineAttributes(4.5f, SWT.CAP_ROUND, SWT.JOIN_ROUND, SWT.LINE_CUSTOM, dash, 8, 10));
            gc.drawLine(10, 30, 140, 30);
            gc.setForeground(SwtKit.color(FILLS[3]));
            gc.setLineAttributes(new LineAttributes(6, SWT.CAP_FLAT, SWT.JOIN_MITER, SWT.LINE_SOLID, null, 0, 2));
            gc.drawPolyline(new int[] { 18, 106, 40, 46, 62, 106 });
            gc.setLineAttributes(new LineAttributes(6, SWT.CAP_FLAT, SWT.JOIN_MITER, SWT.LINE_SOLID, null, 0, 10));
            gc.drawPolyline(new int[] { 88, 106, 110, 46, 132, 106 });
        }));
        return tiles;
    }

    /**
     * Thin red lines through {@code lines} (polylines).
     */
    private static void guides(GC gc, int[]... lines) {
        gc.setLineCap(SWT.CAP_FLAT);
        gc.setLineJoin(SWT.JOIN_MITER);
        gc.setLineWidth(1);
        gc.setForeground(SwtKit.color(ACCENT));
        for (int[] line : lines) {
            gc.drawPolyline(line);
        }
    }

    private static void fillRule(GC gc, int rule, int fill) {
        int[] star = GcProbes.pentagram(75, 62, 54);
        gc.setFillRule(rule);
        gc.setBackground(SwtKit.color(fill));
        gc.fillPolygon(star);
        gc.drawPolygon(star);
    }

    /**
     * Three colors only : the background of the tile, a fill and the ink (aliased, nothing in between).
     */
    private static void antialiased(GC gc, int antialias) {
        gc.setAntialias(antialias);
        gc.setBackground(SwtKit.color(FILLS[2]));
        gc.fillOval(8, 8, 70, 70);
        gc.drawOval(8, 8, 70, 70);
        gc.fillPolygon(new int[] { 90, 104, 142, 70, 120, 112 });
        gc.setLineWidth(2);
        gc.drawLine(84, 10, 142, 50);
        gc.drawArc(14, 70, 80, 40, 0, 180);
    }

    // ------------------------------------------------------------------------------------------------------ checks

    /**
     * Each getter of a {@code GC} after its setter, on the {@code GC} of an image.
     */
    private static List<Check> stateChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        Image image = new Image(display, 8, 8);
        try {
            GC gc = new GC(image);
            try {
                // the GC of an image starts without GDI+ on Windows ; Cairo is always used on Linux
                checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("new GC(image) : getAdvanced()",
                        false, gc::getAdvanced)));
                checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("new GC(image) : getAntialias()",
                        "DEFAULT", () -> GcProbes.antialias(gc.getAntialias()))));
                checks.add(SwtChecks.expect("setAntialias(ON) : getAntialias(), getAdvanced()", "ON true", () -> {
                    gc.setAntialias(SWT.ON);
                    return GcProbes.antialias(gc.getAntialias()) + " " + gc.getAdvanced();
                }));
                checks.add(SwtChecks.expect("setLineWidth(3) : getLineWidth()", 3, () -> {
                    gc.setLineWidth(3);
                    return gc.getLineWidth();
                }));
                checks.add(SwtChecks.expect("setLineStyle(LINE_DASHDOT) : getLineStyle()", "LINE_DASHDOT", () -> {
                    gc.setLineStyle(SWT.LINE_DASHDOT);
                    return GcProbes.lineStyle(gc.getLineStyle());
                }));
                checks.add(SwtChecks.expect("setLineDash(12, 4, 2, 4) : getLineDash(), getLineStyle()",
                        "[12, 4, 2, 4] LINE_CUSTOM", () -> {
                            gc.setLineDash(new int[] { 12, 4, 2, 4 });
                            return Arrays.toString(gc.getLineDash()) + " " + GcProbes.lineStyle(gc.getLineStyle());
                        }));
                checks.add(SwtChecks.expect("setLineCap(CAP_ROUND) : getLineCap()", "CAP_ROUND", () -> {
                    gc.setLineCap(SWT.CAP_ROUND);
                    return GcProbes.lineCap(gc.getLineCap());
                }));
                checks.add(SwtChecks.expect("setLineJoin(JOIN_BEVEL) : getLineJoin()", "JOIN_BEVEL", () -> {
                    gc.setLineJoin(SWT.JOIN_BEVEL);
                    return GcProbes.lineJoin(gc.getLineJoin());
                }));
                checks.add(SwtChecks.expect("setLineAttributes(4.5, CAP_SQUARE, JOIN_ROUND, [10, 6], offset 8, miter"
                        + " 4) : getLineAttributes()", "4.500 CAP_SQUARE JOIN_ROUND LINE_CUSTOM [10.000, 6.000] 8.000"
                                + " 4.000", () -> {
                                    gc.setLineAttributes(new LineAttributes(4.5f, SWT.CAP_SQUARE, SWT.JOIN_ROUND,
                                            SWT.LINE_CUSTOM, new float[] { 10, 6 }, 8, 4));
                                    return lineAttributes(gc.getLineAttributes());
                                }));
                checks.add(SwtChecks.expect("getLineWidth() of a 4.5 wide line (truncated)", 4, gc::getLineWidth));
                checks.add(SwtChecks.expect("setAlpha(128) : getAlpha()", 128, () -> {
                    gc.setAlpha(128);
                    return gc.getAlpha();
                }));
                checks.add(SwtChecks.expect("setFillRule(FILL_EVEN_ODD) : getFillRule()", "FILL_EVEN_ODD", () -> {
                    gc.setFillRule(SWT.FILL_EVEN_ODD);
                    return GcProbes.fillRule(gc.getFillRule());
                }));
                checks.add(SwtChecks.expect("setForeground, setBackground : getForeground(), getBackground()",
                        "#C62828 #FFF59D", () -> {
                            gc.setForeground(SwtKit.color(FOREGROUND));
                            gc.setBackground(SwtKit.color(BACKGROUND_FILL));
                            return SwtChecks.rgb(gc.getForeground().getRGB()) + " "
                                    + SwtChecks.rgb(gc.getBackground().getRGB());
                        }));
                checks.add(SwtChecks.expect("setXORMode(true) : getXORMode()", true, () -> {
                    gc.setXORMode(true);
                    boolean xor = gc.getXORMode();
                    gc.setXORMode(false);
                    return xor;
                }));
                // setAdvanced(false) drops GDI+ and resets the alpha on Windows
                checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                        "setAdvanced(false) : getAdvanced(), getAlpha()", "false 255", () -> {
                            gc.setAdvanced(false);
                            return gc.getAdvanced() + " " + gc.getAlpha();
                        })));
                checks.add(SwtChecks.expect("setInterpolation(HIGH) : getAdvanced() (advanced again)", true, () -> {
                    gc.setInterpolation(SWT.HIGH);
                    return gc.getAdvanced();
                }));
            } finally {
                gc.dispose();
            }
        } finally {
            image.dispose();
        }
        return checks;
    }

    private static String lineAttributes(LineAttributes attributes) {
        StringBuilder dash = new StringBuilder("[");
        if (attributes.dash != null) {
            for (int i = 0; i < attributes.dash.length; i++) {
                dash.append(i == 0 ? "" : ", ").append(SwtChecks.num(attributes.dash[i]));
            }
        }
        return SwtChecks.num(attributes.width) + " " + GcProbes.lineCap(attributes.cap) + " "
                + GcProbes.lineJoin(attributes.join) + " " + GcProbes.lineStyle(attributes.style) + " " + dash + "] "
                + SwtChecks.num(attributes.dashOffset) + " " + SwtChecks.num(attributes.miterLimit);
    }

    /**
     * Pixel probes of the tiles : exact inside the fills (whatever the anti-aliasing), within a tolerance for the
     * blending and the gradients.
     */
    private static List<Check> probeChecks(SwtTiles tiles, ImageData image) {
        List<Check> checks = new ArrayList<>();
        checks.add(exact("fillRectangle : inside", tiles, image, RECTANGLE, 75, 59, FILLS[0]));
        checks.add(exact("fillRoundRectangle : inside", tiles, image, ROUND_RECTANGLE, 75, 59, FILLS[1]));
        checks.add(exact("fillOval : inside", tiles, image, OVAL, 75, 59, FILLS[2]));
        checks.add(exact("fillArc(30, 250) : at 180 degrees, inside", tiles, image, ARC, 40, 59, FILLS[3]));
        checks.add(exact("fillArc(30, 250) : at 0 degrees, outside", tiles, image, ARC, 112, 59, BACKGROUND));
        checks.add(exact("fillPolygon : inside", tiles, image, POLYGON, 75, 60, FILLS[4]));
        checks.add(exact("fillPolygon, FILL_EVEN_ODD : center of the pentagram", tiles, image, EVEN_ODD, 75, 62,
                BACKGROUND));
        checks.add(exact("fillPolygon, FILL_WINDING : center of the pentagram", tiles, image, WINDING, 75, 62,
                FILLS[5]));
        checks.add(exact("fillRectangle uses the background color", tiles, image, COLORS, 75, 30, BACKGROUND_FILL));
        checks.add(exact("drawRectangle uses the foreground color", tiles, image, COLORS, 75, 8, FOREGROUND));
        checks.add(near("setAlpha(128) over an opaque fill", tiles, image, ALPHA, 65, 60,
                blend(FILLS[3], FILLS[0], 128), 3));
        checks.add(near("setAlpha(128) over the background", tiles, image, ALPHA, 120, 100,
                blend(FILLS[3], BACKGROUND, 128), 3));
        checks.add(near("setAlpha(64) over the background", tiles, image, ALPHA, 113, 18,
                blend(INK, BACKGROUND, 64), 3));
        checks.add(gradient("fillGradientRectangle vertical : top, bottom", tiles, image, VERTICAL, 40, 10, 40, 107,
                GRADIENT_TOP, GRADIENT_BOTTOM));
        checks.add(gradient("negative height (reversed) : top, bottom", tiles, image, VERTICAL, 110, 10, 110, 107,
                GRADIENT_BOTTOM, GRADIENT_TOP));
        checks.add(gradient("fillGradientRectangle horizontal, GDI : left, right", tiles, image, HORIZONTAL, 10, 59,
                139, 59, GRADIENT_LEFT, GRADIENT_RIGHT));
        // GTK : GC.drawLine switches Cairo to CAIRO_ANTIALIAS_BEST and never restores the antialias of setAntialias
        // (GC.drawLineInPixels, GC.java:1001-1011 of SWT GTK 3.132.0), a GTK SWT bug : the line and the arc drawn after
        // it are anti-aliased ; the oval, drawn before the line (the top left 80 x 64, clear of the line and of the
        // arc), has the three colors
        checks.add(SwtChecks.expect("setAntialias(OFF) : colors of the drawing (background, fill, ink)",
                SwtMode.<Object> pick(3, 3, "3 in the oval (before drawLine), more than 3 in all"), () -> {
                    int all = colors(tiles, image, ALIASED);
                    if (!SwtMode.isLinux()) {
                        return all;
                    }
                    return colors(tiles, image, ALIASED, 0, 0, 80, 64) + " in the oval (before drawLine), "
                            + (all > 3 ? "more than 3" : String.valueOf(all)) + " in all";
                }));
        checks.add(SwtChecks.run("setAntialias(ON) : colors of the drawing (more than 3)", () -> {
            int count = colors(tiles, image, ANTIALIASED);
            if (count <= 3) {
                throw new IllegalStateException(count + " colors : not anti-aliased");
            }
            return count;
        }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "setAdvanced(false) : colors of the GDI drawing (aliased)", 3, () -> colors(tiles, image, GDI))));
        // GDI : drawRectangle(10, 10, 40, 30) covers the columns 10 to 50 and the rows 10 to 40
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("GDI drawRectangle(10, 10, 40, 30) : column 50 is the outline", tiles, image, GDI, 50, 25, INK)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("GDI drawRectangle(10, 10, 40, 30) : column 51 is outside", tiles, image, GDI, 51, 25,
                        BACKGROUND)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("GDI drawRectangle(10, 10, 40, 30) : row 40 is the outline", tiles, image, GDI, 30, 40, INK)));
        // XOR : PATINVERT of GDI on Windows (Cairo uses another operator, macOS has no XOR)
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("XOR : first fill", tiles, image, XOR, 25, 20, BACKGROUND ^ XOR_A)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("XOR : second fill", tiles, image, XOR, 120, 100, BACKGROUND ^ XOR_B)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("XOR : both fills", tiles, image, XOR, 65, 55, BACKGROUND ^ XOR_A ^ XOR_B)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS,
                exact("XOR : the same fill twice (erased)", tiles, image, XOR, 29, 93, BACKGROUND)));
        return checks;
    }

    /**
     * The two ends of a gradient are its two colors, within a tolerance : the value is both probes.
     */
    private static Check gradient(String name, SwtTiles tiles, ImageData image, String caption, int x1, int y1,
            int x2, int y2, int from, int to) {
        try {
            String first = tiles.probe(image, caption, x1, y1);
            String last = tiles.probe(image, caption, x2, y2);
            boolean ok = distance(rgb(first), from) <= 8 && distance(rgb(last), to) <= 8;
            return Check.of(name, ok, ok ? first + " " + last
                    : "expected " + opaque(from) + " " + opaque(to) + " ± 8 but got " + first + " " + last);
        } catch (Throwable t) {
            return Check.fail(name, SwtChecks.describe(t));
        }
    }
}
