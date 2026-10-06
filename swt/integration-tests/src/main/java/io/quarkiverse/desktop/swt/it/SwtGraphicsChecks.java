package io.quarkiverse.desktop.swt.it;

import static io.quarkiverse.desktop.swt.it.SwtChecks.require;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.PathData;
import org.eclipse.swt.graphics.Pattern;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Checks of the graphics : advanced graphics drawn into an image (paths, transforms, patterns, regions, line
 * attributes, interpolation, alpha), text layouts (styles, wrapping, alignment, tabs, bidirectional text), fonts and
 * their metrics, images and their file formats, system colors and cursors.
 * <p>
 * The measures are written to {@code <check>-<mode>.txt}, the drawings to {@code <check>-<mode>.png}.
 */
final class SwtGraphicsChecks {

    private final SwtChecks checks;
    private final Display display;

    SwtGraphicsChecks(SwtChecks checks) {
        this.checks = checks;
        this.display = checks.display();
    }

    // ------------------------------------------------------------------------------------------ advanced graphics

    /**
     * Paths, transforms, patterns, clipping regions, line attributes, interpolation, alpha and antialiasing.
     */
    Object advanced() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            StringBuilder text = new StringBuilder();
            boolean[] advanced = new boolean[1];
            ImageData data = checks.draw(480, 360, gc -> {
                paintPaths(gc, resources, text);
                paintTransforms(gc, resources, text);
                paintPatterns(gc, resources);
                paintRegions(gc, resources, text);
                paintLines(gc);
                paintInterpolation(gc, resources);
                paintAlpha(gc);
                paintFillRules(gc);
                advanced[0] = gc.getAdvanced();
            });
            require(advanced[0], "the GC did not switch to advanced graphics");
            checks.writeImage("graphics-advanced", data);
            checks.writeText("graphics-advanced", text.toString());
            return "size=" + data.width + "x" + data.height + " advanced=" + advanced[0];
        }
    }

    private void paintPaths(GC gc, SwtChecks.Resources resources, StringBuilder text) {
        gc.setAntialias(SWT.ON);
        Path path = resources.add(new Path(display));
        path.moveTo(10, 100);
        path.lineTo(30, 20);
        path.cubicTo(50, 0, 80, 60, 100, 20);
        path.quadTo(130, 0, 150, 50);
        path.lineTo(150, 100);
        path.close();
        gc.setBackground(new Color(204, 234, 244));
        gc.fillPath(path);
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_BLUE));
        gc.setLineWidth(2);
        gc.drawPath(path);
        Path shapes = resources.add(new Path(display));
        shapes.addArc(20, 55, 50, 40, 0, 270);
        shapes.addRectangle(90, 65, 50, 30);
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_RED));
        gc.drawPath(shapes);
        describe(text, "path", path);
        describe(text, "shapes", shapes);
        text.append("path contains ").append(path.contains(80, 60, gc, false)).append(' ')
                .append(path.contains(5, 5, gc, false)).append('\n');

        Font font = resources.add(new Font(display, "Arial", 28, SWT.BOLD));
        Path string = resources.add(new Path(display));
        string.addString("SWT", 175, 15, font);
        gc.setBackground(new Color(240, 160, 40));
        gc.fillPath(string);
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.setLineWidth(1);
        gc.drawPath(string);
        float[] bounds = new float[4];
        string.getBounds(bounds);
        require(bounds[2] > 20 && bounds[3] > 10, "Path.addString bounds " + Arrays.toString(bounds));
    }

    private void describe(StringBuilder text, String name, Path path) {
        float[] bounds = new float[4];
        path.getBounds(bounds);
        PathData data = path.getPathData();
        text.append(name).append(" bounds=").append(Arrays.toString(bounds)).append(" types=")
                .append(Arrays.toString(data.types)).append(" points=").append(data.points.length).append('\n');
    }

    private void paintTransforms(GC gc, SwtChecks.Resources resources, StringBuilder text) {
        Transform transform = resources.add(new Transform(display));
        transform.translate(400, 85);
        transform.rotate(30);
        transform.scale(1.1f, 0.7f);
        transform.shear(0.3f, 0);
        gc.setTransform(transform);
        gc.setBackground(new Color(60, 170, 80));
        gc.fillRectangle(-30, -20, 60, 40);
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.drawRectangle(-30, -20, 60, 40);
        gc.drawString("T", -4, -10, true);
        gc.setTransform(null);
        float[] elements = new float[6];
        transform.getElements(elements);
        float[] point = { 10, 10 };
        transform.transform(point);
        Transform inverse = resources.add(new Transform(display, elements));
        inverse.invert();
        inverse.multiply(transform);
        text.append("transform elements=").append(Arrays.toString(elements)).append(" point=")
                .append(Arrays.toString(point)).append('\n');
        require(!transform.isIdentity(), "the transform is the identity");
        float[] product = new float[6];
        inverse.getElements(product);
        require(Math.abs(product[0] - 1) < 1e-3 && Math.abs(product[3] - 1) < 1e-3 && Math.abs(product[4]) < 1e-2,
                "inverse x transform " + Arrays.toString(product));
    }

    private void paintPatterns(GC gc, SwtChecks.Resources resources) {
        Pattern gradient = resources.add(new Pattern(display, 10, 130, 150, 230, display.getSystemColor(SWT.COLOR_BLUE),
                255, display.getSystemColor(SWT.COLOR_YELLOW), 128));
        gc.setBackgroundPattern(gradient);
        gc.fillRoundRectangle(10, 130, 140, 50, 20, 20);
        gc.setBackgroundPattern(null);
        ImageData checker = new ImageData(8, 8, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                checker.setPixel(x, y, (x < 4) == (y < 4) ? 0xC03030 : 0xF0F0F0);
            }
        }
        Image tile = resources.add(new Image(display, checker));
        Pattern tiles = resources.add(new Pattern(display, tile));
        gc.setBackgroundPattern(tiles);
        gc.fillOval(15, 185, 70, 45);
        gc.setBackgroundPattern(null);
        Pattern line = resources.add(new Pattern(display, 90, 190, 150, 230, display.getSystemColor(SWT.COLOR_RED),
                display.getSystemColor(SWT.COLOR_DARK_GREEN)));
        gc.setForegroundPattern(line);
        gc.setLineWidth(6);
        gc.drawLine(95, 195, 150, 230);
        gc.setForegroundPattern(null);
        gc.setLineWidth(1);
    }

    private void paintRegions(GC gc, SwtChecks.Resources resources, StringBuilder text) {
        Region region = resources.add(new Region(display));
        region.add(new Rectangle(170, 130, 140, 100));
        region.subtract(new int[] { 200, 150, 280, 150, 240, 215 });
        region.subtract(290, 210, 20, 20);
        Region extra = resources.add(new Region(display));
        extra.add(new int[] { 320, 130, 330, 230, 310, 230 });
        region.add(extra);
        require(region.contains(175, 135) && !region.contains(240, 165) && !region.contains(300, 220),
                "Region.contains");
        require(region.intersects(150, 120, 30, 30) && !region.intersects(0, 0, 10, 10), "Region.intersects");
        gc.setClipping(region);
        gc.setBackground(new Color(SwtPalette.ACCENT));
        gc.fillRectangle(160, 120, 180, 120);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.setLineWidth(3);
        for (int x = 160; x < 340; x += 12) {
            gc.drawLine(x, 120, x + 40, 240);
        }
        gc.setClipping((Rectangle) null);
        gc.setLineWidth(1);
        Rectangle bounds = region.getBounds();
        text.append("region bounds=").append(bounds.x).append(',').append(bounds.y).append(',').append(bounds.width)
                .append(',').append(bounds.height).append('\n');
    }

    private void paintLines(GC gc) {
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_MAGENTA));
        gc.setLineAttributes(new LineAttributes(8, SWT.CAP_ROUND, SWT.JOIN_ROUND));
        gc.drawPolyline(new int[] { 350, 140, 380, 170, 410, 140 });
        gc.setLineCap(SWT.CAP_SQUARE);
        gc.setLineJoin(SWT.JOIN_MITER);
        gc.setLineWidth(6);
        gc.drawPolyline(new int[] { 420, 140, 445, 170, 470, 140 });
        gc.setLineCap(SWT.CAP_FLAT);
        gc.setLineJoin(SWT.JOIN_BEVEL);
        gc.drawPolyline(new int[] { 350, 190, 380, 220, 410, 190 });
        gc.setLineWidth(2);
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.setLineStyle(SWT.LINE_DASHDOT);
        gc.drawLine(420, 185, 475, 185);
        gc.setLineStyle(SWT.LINE_DOT);
        gc.drawLine(420, 195, 475, 195);
        gc.setLineDash(new int[] { 8, 4, 2, 4 });
        gc.drawLine(420, 205, 475, 205);
        LineAttributes attributes = gc.getLineAttributes();
        require(attributes.style == SWT.LINE_CUSTOM && attributes.dash != null && attributes.dash.length == 4,
                "line dash " + attributes.style);
        gc.setLineDash(null);
        gc.setLineStyle(SWT.LINE_SOLID);
        gc.setLineCap(SWT.CAP_FLAT);
        gc.setLineJoin(SWT.JOIN_MITER);
        gc.setLineWidth(1);
    }

    private void paintInterpolation(GC gc, SwtChecks.Resources resources) {
        Image small = resources.add(new Image(display, SwtChecks.icon(new RGB(220, 60, 50), 2)));
        gc.setInterpolation(SWT.NONE);
        gc.drawImage(small, 0, 0, 16, 16, 10, 250, 64, 64);
        gc.setInterpolation(SWT.HIGH);
        gc.drawImage(small, 0, 0, 16, 16, 84, 250, 64, 64);
        gc.setInterpolation(SWT.DEFAULT);
    }

    private void paintAlpha(GC gc) {
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.setLineWidth(2);
        gc.setAntialias(SWT.OFF);
        gc.drawOval(170, 250, 60, 60);
        gc.setAntialias(SWT.ON);
        gc.drawOval(240, 250, 60, 60);
        gc.setAlpha(96);
        gc.setBackground(display.getSystemColor(SWT.COLOR_RED));
        gc.fillOval(180, 265, 60, 60);
        gc.setBackground(display.getSystemColor(SWT.COLOR_BLUE));
        gc.fillOval(215, 280, 60, 60);
        gc.setAlpha(255);
        gc.setLineWidth(1);
        gc.setTextAntialias(SWT.ON);
        gc.drawString("alpha", 260, 330, true);
        gc.setTextAntialias(SWT.DEFAULT);
    }

    private void paintFillRules(GC gc) {
        int[] star = { 380, 250, 395, 345, 335, 285, 425, 285, 365, 345 };
        gc.setBackground(display.getSystemColor(SWT.COLOR_DARK_CYAN));
        gc.setFillRule(SWT.FILL_EVEN_ODD);
        gc.fillPolygon(star);
        int[] winding = new int[star.length];
        for (int i = 0; i < star.length; i++) {
            winding[i] = star[i] + (i % 2 == 0 ? 60 : 0);
        }
        gc.setFillRule(SWT.FILL_WINDING);
        gc.fillPolygon(winding);
        gc.setForeground(display.getSystemColor(SWT.COLOR_YELLOW));
        gc.setBackground(display.getSystemColor(SWT.COLOR_DARK_GREEN));
        gc.fillGradientRectangle(330, 5, 140, 15, false);
        gc.fillGradientRectangle(330, 25, 140, 15, true);
    }

    // -------------------------------------------------------------------------------------------------- text layout

    /**
     * Text layouts : styles, wrapping, alignment, justification, tabs, and bidirectional text (Hebrew and Arabic, in
     * the fonts the platform falls back to).
     */
    Object textLayout() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            String family = display.getSystemFont().getFontData()[0].getName();
            Font font = resources.add(new Font(display, family, 10, SWT.NORMAL));
            Font bold = resources.add(new Font(display, family, 10, SWT.BOLD));
            Font italic = resources.add(new Font(display, family, 10, SWT.ITALIC));
            Font small = resources.add(new Font(display, family, 7, SWT.NORMAL));
            Font serif = resources.add(new Font(display, "Times New Roman", 12, SWT.NORMAL));

            String text = "Quarkus Desktop SWT : bold, italic, serif, colored, underline, double, squiggle, error,"
                    + " link, strikeout, raised, lowered, boxed, dashed, \ufffc object.\nTabs :\tone\ttwo\tthree";
            TextLayout styled = layout(resources, font, text, 340);
            styled.setTabs(new int[] { 70, 140 });
            styled.setSpacing(2);
            styled.setIndent(10);
            styled.setWrapIndent(20);
            style(styled, "bold", new TextStyle(bold, null, null));
            style(styled, "italic", new TextStyle(italic, display.getSystemColor(SWT.COLOR_DARK_BLUE), null));
            style(styled, "serif", new TextStyle(serif, null, null));
            style(styled, "colored", new TextStyle(null, display.getSystemColor(SWT.COLOR_WHITE),
                    new Color(SwtPalette.ACCENT)));
            style(styled, "underline", underline(SWT.UNDERLINE_SINGLE, null));
            style(styled, "double", underline(SWT.UNDERLINE_DOUBLE, display.getSystemColor(SWT.COLOR_DARK_GREEN)));
            style(styled, "squiggle", underline(SWT.UNDERLINE_SQUIGGLE, display.getSystemColor(SWT.COLOR_RED)));
            style(styled, "error", underline(SWT.UNDERLINE_ERROR, null));
            style(styled, "link", underline(SWT.UNDERLINE_LINK, null));
            TextStyle strikeout = new TextStyle();
            strikeout.strikeout = true;
            strikeout.strikeoutColor = display.getSystemColor(SWT.COLOR_RED);
            style(styled, "strikeout", strikeout);
            TextStyle raised = new TextStyle(small, null, null);
            raised.rise = 5;
            style(styled, "raised", raised);
            TextStyle lowered = new TextStyle(small, null, null);
            lowered.rise = -3;
            style(styled, "lowered", lowered);
            TextStyle boxed = new TextStyle();
            boxed.borderStyle = SWT.BORDER_SOLID;
            boxed.borderColor = display.getSystemColor(SWT.COLOR_DARK_MAGENTA);
            style(styled, "boxed", boxed);
            TextStyle dashed = new TextStyle();
            dashed.borderStyle = SWT.BORDER_DASH;
            style(styled, "dashed", dashed);
            TextStyle object = new TextStyle();
            object.metrics = new GlyphMetrics(12, 2, 24);
            style(styled, "\ufffc", object);

            String paragraph = "A paragraph wrapped in the width of the layout, centered, with a selection drawn"
                    + " over a few words of its second line.";
            TextLayout centered = layout(resources, font, paragraph, 300);
            centered.setAlignment(SWT.CENTER);
            TextLayout justified = layout(resources, font, paragraph.replace("centered", "justified"), 300);
            justified.setJustify(true);
            String bidiText = "Hebrew : \u05e9\u05dc\u05d5\u05dd \u05e2\u05d5\u05dc\u05dd. Arabic : \u0645\u0631\u062d"
                    + "\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645. Numbers 2024.";
            TextLayout bidi = layout(resources, font, bidiText, 340);
            String rtlText = "\u05e9\u05dc\u05d5\u05dd, Quarkus \u0645\u0631\u062d\u0628\u0627 123";
            TextLayout rtl = layout(resources, font, rtlText, 340);
            rtl.setOrientation(SWT.RIGHT_TO_LEFT);

            int hebrew = bidiText.indexOf('\u05e9');
            int arabic = bidiText.indexOf('\u0645');
            require(bidi.getLevel(0) == 0, "level of the latin text " + bidi.getLevel(0));
            require(bidi.getLevel(hebrew) % 2 == 1 && bidi.getLevel(arabic) % 2 == 1,
                    "levels of the right to left text " + bidi.getLevel(hebrew) + " " + bidi.getLevel(arabic));
            require(rtl.getLevel(rtlText.indexOf('Q')) % 2 == 0 && rtl.getLevel(0) % 2 == 1,
                    "levels in a right to left paragraph");
            require(styled.getLineCount() > 2, "styled lines " + styled.getLineCount());
            require(centered.getLineCount() > 1, "the paragraph did not wrap");

            StringBuilder measures = new StringBuilder();
            TextLayout[] layouts = { styled, centered, justified, bidi, rtl };
            String[] names = { "styled", "centered", "justified", "bidi", "rtl" };
            int height = 10;
            for (int i = 0; i < layouts.length; i++) {
                measure(measures, names[i], layouts[i]);
                height += layouts[i].getBounds().height + 10;
            }
            Color selectionForeground = display.getSystemColor(SWT.COLOR_WHITE);
            Color selectionBackground = display.getSystemColor(SWT.COLOR_DARK_BLUE);
            int[] lineOffsets = centered.getLineOffsets();
            ImageData data = checks.draw(360, height, gc -> {
                int y = 10;
                for (TextLayout layout : layouts) {
                    // the color of the text without foreground style
                    gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
                    if (layout == centered) {
                        layout.draw(gc, 10, y, lineOffsets[1] + 2, lineOffsets[1] + 12, selectionForeground,
                                selectionBackground);
                    } else {
                        layout.draw(gc, 10, y);
                    }
                    if (layout == styled) {
                        int index = text.indexOf('\ufffc');
                        Rectangle bounds = styled.getBounds(index, index);
                        gc.setBackground(new Color(240, 160, 40));
                        gc.fillRectangle(10 + bounds.x + 2, y + bounds.y + 2, bounds.width - 4, bounds.height - 4);
                    }
                    gc.setForeground(display.getSystemColor(SWT.COLOR_GRAY));
                    Rectangle bounds = layout.getBounds();
                    gc.drawRectangle(10 + bounds.x, y + bounds.y, bounds.width, bounds.height);
                    y += bounds.height + 10;
                }
            });
            checks.writeImage("text-layout", data);
            checks.writeText("text-layout", measures.toString());
            return "layouts=" + layouts.length + " lines=" + styled.getLineCount() + "," + centered.getLineCount()
                    + "," + bidi.getLineCount() + " size=" + data.width + "x" + data.height;
        }
    }

    private TextLayout layout(SwtChecks.Resources resources, Font font, String text, int width) {
        TextLayout layout = resources.add(new TextLayout(display));
        layout.setFont(font);
        layout.setText(text);
        layout.setWidth(width);
        return layout;
    }

    private static TextStyle underline(int style, Color color) {
        TextStyle underline = new TextStyle();
        underline.underline = true;
        underline.underlineStyle = style;
        underline.underlineColor = color;
        return underline;
    }

    /**
     * Styles the first occurrence of the word.
     */
    private static void style(TextLayout layout, String word, TextStyle style) {
        int start = layout.getText().indexOf(word);
        require(start >= 0, word);
        layout.setStyle(style, start, start + word.length() - 1);
    }

    private static void measure(StringBuilder text, String name, TextLayout layout) {
        text.append(name).append(" lines=").append(layout.getLineCount()).append(" offsets=")
                .append(Arrays.toString(layout.getLineOffsets())).append(" bounds=").append(bounds(layout.getBounds()))
                .append(" ascent=").append(layout.getAscent()).append(" descent=").append(layout.getDescent())
                .append(" ranges=").append(layout.getRanges().length).append('\n');
        for (int line = 0; line < layout.getLineCount(); line++) {
            FontMetrics metrics = layout.getLineMetrics(line);
            text.append(name).append(" line ").append(line).append(' ').append(bounds(layout.getLineBounds(line)))
                    .append(" ascent=").append(metrics.getAscent()).append(" descent=").append(metrics.getDescent())
                    .append('\n');
        }
        String value = layout.getText();
        StringBuilder locations = new StringBuilder();
        StringBuilder levels = new StringBuilder();
        for (int offset = 0; offset < value.length(); offset += 7) {
            Point location = layout.getLocation(offset, false);
            locations.append(' ').append(offset).append(':').append(location.x).append(',').append(location.y);
            levels.append(layout.getLevel(offset));
        }
        text.append(name).append(" locations").append(locations).append('\n');
        text.append(name).append(" levels ").append(levels).append('\n');
        StringBuilder words = new StringBuilder();
        for (int offset = 0, i = 0; offset < value.length() && i < 12; i++) {
            offset = layout.getNextOffset(offset, SWT.MOVEMENT_WORD);
            words.append(' ').append(offset);
        }
        text.append(name).append(" words").append(words).append('\n');
        int[] trailing = new int[1];
        Rectangle bounds = layout.getBounds();
        int hit = layout.getOffset(new Point(bounds.width / 2, bounds.height / 2), trailing);
        text.append(name).append(" offset at the center ").append(hit).append(',').append(trailing[0]).append('\n');
    }

    private static String bounds(Rectangle bounds) {
        return bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height;
    }

    // -------------------------------------------------------------------------------------------------------- fonts

    /**
     * The fonts of the platform, the round trip of the font data, the metrics of a few fonts and the extents of
     * texts.
     */
    Object fonts() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            FontData[] scalable = display.getFontList(null, true);
            FontData[] bitmaps = display.getFontList(null, false);
            require(scalable.length > 0, "no scalable font");
            StringBuilder text = new StringBuilder();
            FontData system = display.getSystemFont().getFontData()[0];
            text.append("system ").append(describe(system)).append('\n');

            Font font = resources.add(new Font(display, "Arial", 12, SWT.BOLD | SWT.ITALIC));
            FontData data = font.getFontData()[0];
            require(data.getName().equals("Arial") && data.getHeight() == 12
                    && data.getStyle() == (SWT.BOLD | SWT.ITALIC), "font data " + describe(data));
            Font copy = resources.add(new Font(display, data));
            FontData copied = copy.getFontData()[0];
            require(copied.getName().equals(data.getName()) && copied.getHeight() == data.getHeight()
                    && copied.getStyle() == data.getStyle(), "copied font data " + describe(copied));
            FontData parsed = new FontData(data.toString());
            require(parsed.equals(data), "FontData(String) " + parsed + " " + data);
            text.append("created ").append(describe(data)).append('\n');

            Font[] fonts = { display.getSystemFont(), font, resources.add(new Font(display, "Times New Roman", 14,
                    SWT.NORMAL)), resources.add(new Font(display, "Courier New", 10, SWT.NORMAL)),
                    resources.add(new Font(display, new FontData[] { new FontData(system.getName(), 9, SWT.BOLD) })) };
            Image scratch = resources.add(new Image(display, 1, 1));
            GC gc = new GC(scratch);
            try {
                for (Font sample : fonts) {
                    gc.setFont(sample);
                    FontMetrics metrics = gc.getFontMetrics();
                    require(metrics.getHeight() > 0 && metrics.getAscent() > 0,
                            "metrics of " + describe(sample.getFontData()[0]));
                    Point extent = gc.textExtent("Quarkus Desktop SWT");
                    Point string = gc.stringExtent("Quarkus Desktop SWT");
                    Point lines = gc.textExtent("Tab\tand\nlines", SWT.DRAW_TAB | SWT.DRAW_DELIMITER);
                    require(lines.y > extent.y, "multi-line extent " + lines + " " + extent);
                    text.append(describe(sample.getFontData()[0])).append(" ascent=").append(metrics.getAscent())
                            .append(" descent=").append(metrics.getDescent()).append(" height=")
                            .append(metrics.getHeight()).append(" leading=").append(metrics.getLeading())
                            .append(" average=").append(metrics.getAverageCharacterWidth()).append(" extent=")
                            .append(extent.x).append('x').append(extent.y).append(" string=").append(string.x)
                            .append('x').append(string.y).append(" lines=").append(lines.x).append('x').append(lines.y)
                            .append(" advanceW=").append(gc.getAdvanceWidth('W')).append(" charWidthI=")
                            .append(gc.getCharWidth('i')).append('\n');
                }
            } finally {
                gc.dispose();
            }
            checks.writeText("fonts", text.toString());
            return "scalable=" + scalable.length + " bitmaps=" + bitmaps.length + " system=" + system.getName() + "-"
                    + system.getHeight() + " created=" + describe(data);
        }
    }

    private static String describe(FontData data) {
        return data.getName() + "|" + data.getHeight() + "|" + data.getStyle();
    }

    // ------------------------------------------------------------------------------------------------------- images

    private record Format(String name, int type, ImageData source, boolean lossless) {
    }

    /**
     * Images saved and loaded in every format of SWT, drawn scaled, and the system images.
     */
    Object images() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            ImageData direct = directImage(new PaletteData(0xFF0000, 0xFF00, 0xFF));
            ImageData opaque = (ImageData) direct.clone();
            opaque.alphaData = null;
            // SWT writes the pixels of the 24 bits icons as they are, and reads them as blue, green, red
            ImageData bgr = directImage(new PaletteData(0xFF, 0xFF00, 0xFF0000));
            bgr.alphaData = null;
            ImageData indexed = indexedImage();
            Format[] formats = { new Format("png-direct", SWT.IMAGE_PNG, direct, true),
                    new Format("png-indexed", SWT.IMAGE_PNG, indexed, true),
                    new Format("bmp-direct", SWT.IMAGE_BMP, opaque, true),
                    new Format("bmp-indexed", SWT.IMAGE_BMP, indexed, true),
                    new Format("bmp-rle", SWT.IMAGE_BMP_RLE, indexed, true),
                    new Format("gif", SWT.IMAGE_GIF, indexed, true),
                    new Format("ico-direct", SWT.IMAGE_ICO, bgr, true),
                    new Format("ico-indexed", SWT.IMAGE_ICO, indexed, true),
                    new Format("jpeg", SWT.IMAGE_JPEG, opaque, false),
                    new Format("tiff-direct", SWT.IMAGE_TIFF, unpadded(opaque, 0), true),
                    new Format("tiff-indexed", SWT.IMAGE_TIFF, unpadded(indexed, 256), true) };
            boolean gtk = SWT.getPlatform().equals("gtk");
            StringBuilder text = new StringBuilder();
            ImageData jpeg = null;
            for (Format format : formats) {
                byte[] bytes = save(format.type(), format.source());
                ImageLoader loader = new ImageLoader();
                ImageData loaded = loader.load(new ByteArrayInputStream(bytes))[0];
                require(loaded.width == format.source().width && loaded.height == format.source().height,
                        format.name() + " size " + loaded.width + "x" + loaded.height);
                int different = differentPixels(format.source(), loaded);
                if (format.lossless()) {
                    require(different == 0, format.name() + " : " + different + " pixels differ");
                } else {
                    jpeg = loaded;
                }
                // on Linux, SWT writes the direct images with GdkPixbuf, whose TIFF files have a padding byte that is
                // not initialized (before the image file directory)
                boolean stable = !(gtk && format.type() == SWT.IMAGE_TIFF && format.source().palette.isDirect);
                text.append(format.name()).append(" bytes=").append(bytes.length).append(" crc=")
                        .append(stable ? crc(bytes) : "-").append(" depth=").append(loaded.depth).append(" pixels=")
                        .append(pixelsCrc(loaded)).append(" different=").append(different).append(" transparency=")
                        .append(loaded.getTransparencyType()).append('\n');
            }
            ImageData png = load(save(SWT.IMAGE_PNG, direct))[0];
            for (int x = 0; x < direct.width; x++) {
                require(png.getAlpha(x, 5) == direct.getAlpha(x, 5), "PNG alpha at " + x + " : " + png.getAlpha(x, 5));
            }
            // the pixel (0, 0) is transparent, (6, 0) is not : a transparent pixel, or an alpha channel (the images
            // are decoded by GdkPixbuf on Linux)
            ImageData gif = load(save(SWT.IMAGE_GIF, indexed))[0];
            boolean transparent = gif.transparentPixel >= 0
                    ? gif.getPixel(0, 0) == gif.transparentPixel && gif.getPixel(6, 0) != gif.transparentPixel
                    : gif.alphaData != null && gif.getAlpha(0, 0) == 0 && gif.getAlpha(6, 0) == 255;
            require(transparent, "GIF transparency " + gif.transparentPixel + " " + gif.getTransparencyType());

            ImageLoader animation = new ImageLoader();
            animation.data = new ImageData[] { frame(0), frame(1) };
            animation.repeatCount = 0;
            animation.logicalScreenWidth = 16;
            animation.logicalScreenHeight = 16;
            ByteArrayOutputStream animated = new ByteArrayOutputStream();
            animation.save(animated, SWT.IMAGE_GIF);
            ImageLoader frames = new ImageLoader();
            frames.load(new ByteArrayInputStream(animated.toByteArray()));
            // on Linux, SWT decodes the images with GdkPixbuf, which reads this animation as a static image (its first
            // frame)
            require(frames.data.length == (gtk ? 1 : 2)
                    && (gtk || frames.repeatCount == 0 && frames.data[1].delayTime == 20),
                    "animated GIF " + frames.data.length + " frames, repeat " + frames.repeatCount);
            text.append("gif-animated bytes=").append(animated.size()).append(" frames=").append(frames.data.length)
                    .append('\n');

            Image directImage = resources.add(new Image(display, direct));
            Image indexedImage = resources.add(new Image(display, indexed));
            Image jpegImage = resources.add(new Image(display, jpeg));
            Image resource;
            try (InputStream in = SwtChecks.class.getResourceAsStream("quarkus.png")) {
                require(in != null, "quarkus.png not found");
                resource = resources.add(new Image(display, in));
            }
            Image disabled = resources.add(new Image(display, directImage, SWT.IMAGE_DISABLE));
            Image gray = resources.add(new Image(display, directImage, SWT.IMAGE_GRAY));
            Image copy = resources.add(new Image(display, indexedImage, SWT.IMAGE_COPY));
            ImageData icon = SwtChecks.icon(new RGB(60, 170, 80), 1);
            ImageDataProvider provider = zoom -> icon.scaledTo(16 * zoom / 100, 16 * zoom / 100);
            Image provided = resources.add(new Image(display, provider));
            require(provided.getBounds().width == 16, "ImageDataProvider image " + provided.getBounds());
            int[] systemIds = { SWT.ICON_ERROR, SWT.ICON_INFORMATION, SWT.ICON_QUESTION, SWT.ICON_WARNING,
                    SWT.ICON_WORKING };
            Image[] systemImages = new Image[systemIds.length];
            StringBuilder system = new StringBuilder();
            int found = 0;
            for (int i = 0; i < systemIds.length; i++) {
                systemImages[i] = display.getSystemImage(systemIds[i]);
                Rectangle bounds = systemImages[i] == null ? null : systemImages[i].getBounds();
                system.append(' ').append(bounds == null ? "none" : bounds.width + "x" + bounds.height);
                found += systemImages[i] == null ? 0 : 1;
            }
            text.append("system").append(system).append('\n');
            text.append("resource ").append(bounds(resource.getBounds())).append('\n');

            ImageData data = checks.draw(420, 300, gc -> {
                gc.setBackground(display.getSystemColor(SWT.COLOR_GRAY));
                for (int y = 0; y < 300; y += 10) {
                    for (int x = (y / 10) % 2 * 10; x < 420; x += 20) {
                        gc.fillRectangle(x, y, 10, 10);
                    }
                }
                gc.setInterpolation(SWT.NONE);
                gc.drawImage(directImage, 0, 0, 48, 32, 10, 10, 144, 96);
                gc.drawImage(indexedImage, 0, 0, 48, 32, 164, 10, 144, 96);
                gc.drawImage(jpegImage, 0, 0, 48, 32, 318, 10, 96, 64);
                gc.drawImage(resource, 0, 0, 16, 16, 10, 120, 64, 64);
                gc.drawImage(disabled, 0, 0, 48, 32, 84, 120, 96, 64);
                gc.drawImage(gray, 0, 0, 48, 32, 190, 120, 96, 64);
                gc.drawImage(provided, 0, 0, 16, 16, 296, 120, 64, 64);
                gc.drawImage(copy, 366, 120);
                int x = 10;
                for (Image image : systemImages) {
                    if (image != null) {
                        gc.drawImage(image, x, 200);
                        x += image.getBounds().width + 10;
                    }
                }
            });
            checks.writeImage("images", data);
            checks.writeText("images", text.toString());
            return "formats=" + formats.length + " animated=" + frames.data.length + " systemImages=" + found + "/"
                    + systemIds.length;
        }
    }

    /**
     * 48 x 32, 24 bits, a gradient with an alpha gradient.
     */
    private static ImageData directImage(PaletteData palette) {
        ImageData data = new ImageData(48, 32, 24, palette);
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                data.setPixel(x, y, data.palette.getPixel(new RGB(x * 5, y * 7, (x + y) * 3)));
                data.setAlpha(x, y, 64 + x * 4);
            }
        }
        return data;
    }

    /**
     * 48 x 32, 8 bits with a palette of 16 colors, the first one transparent.
     */
    private static ImageData indexedImage() {
        RGB[] colors = new RGB[16];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = new RGB((i * 53) % 256, (i * 97) % 256, (i * 151) % 256);
        }
        ImageData data = new ImageData(48, 32, 8, new PaletteData(colors));
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                data.setPixel(x, y, (x / 6 + y / 4) % colors.length);
            }
        }
        data.transparentPixel = 0;
        return data;
    }

    /**
     * A frame of an animated GIF : 16 x 16, 2 colors.
     */
    private static ImageData frame(int index) {
        ImageData data = new ImageData(16, 16, 1, new PaletteData(new RGB(255, 255, 255), new RGB(0, 150, 201)));
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                data.setPixel(x, y, (x / 4 + y / 4 + index) % 2);
            }
        }
        data.delayTime = 20;
        data.disposalMethod = SWT.DM_FILL_BACKGROUND;
        return data;
    }

    /**
     * A copy whose lines are not padded, and whose palette has all the colors of its depth (completed with black) :
     * the TIFF writer of SWT only supports these.
     */
    private static ImageData unpadded(ImageData source, int colors) {
        PaletteData palette = source.palette;
        if (!palette.isDirect) {
            RGB[] rgbs = Arrays.copyOf(palette.getRGBs(), colors);
            Arrays.fill(rgbs, palette.getRGBs().length, colors, new RGB(0, 0, 0));
            palette = new PaletteData(rgbs);
        }
        int bytesPerLine = (source.width * source.depth + 7) / 8;
        ImageData data = new ImageData(source.width, source.height, source.depth, palette, 1,
                new byte[bytesPerLine * source.height]);
        for (int y = 0; y < source.height; y++) {
            for (int x = 0; x < source.width; x++) {
                data.setPixel(x, y, source.getPixel(x, y));
            }
        }
        data.transparentPixel = source.transparentPixel;
        return data;
    }

    private static byte[] save(int type, ImageData data) {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { data };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        loader.save(out, type);
        return out.toByteArray();
    }

    private static ImageData[] load(byte[] bytes) throws IOException {
        return new ImageLoader().load(new ByteArrayInputStream(bytes));
    }

    private static int differentPixels(ImageData expected, ImageData actual) {
        int different = 0;
        for (int y = 0; y < expected.height; y++) {
            for (int x = 0; x < expected.width; x++) {
                RGB rgb = expected.palette.getRGB(expected.getPixel(x, y));
                if (!rgb.equals(actual.palette.getRGB(actual.getPixel(x, y)))) {
                    different++;
                }
            }
        }
        return different;
    }

    private static String crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return Long.toHexString(crc.getValue());
    }

    private static String pixelsCrc(ImageData data) {
        CRC32 crc = new CRC32();
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                RGB rgb = data.palette.getRGB(data.getPixel(x, y));
                crc.update(rgb.red);
                crc.update(rgb.green);
                crc.update(rgb.blue);
            }
        }
        return Long.toHexString(crc.getValue());
    }

    // --------------------------------------------------------------------------------------------- colors, cursors

    private static final String[] COLORS = { "WHITE", "BLACK", "RED", "DARK_RED", "GREEN", "DARK_GREEN", "YELLOW",
            "DARK_YELLOW", "BLUE", "DARK_BLUE", "MAGENTA", "DARK_MAGENTA", "CYAN", "DARK_CYAN", "GRAY", "DARK_GRAY",
            "WIDGET_DARK_SHADOW", "WIDGET_NORMAL_SHADOW", "WIDGET_LIGHT_SHADOW", "WIDGET_HIGHLIGHT_SHADOW",
            "WIDGET_FOREGROUND", "WIDGET_BACKGROUND", "WIDGET_BORDER", "LIST_FOREGROUND", "LIST_BACKGROUND",
            "LIST_SELECTION", "LIST_SELECTION_TEXT", "INFO_FOREGROUND", "INFO_BACKGROUND", "TITLE_FOREGROUND",
            "TITLE_BACKGROUND", "TITLE_BACKGROUND_GRADIENT", "TITLE_INACTIVE_FOREGROUND", "TITLE_INACTIVE_BACKGROUND",
            "TITLE_INACTIVE_BACKGROUND_GRADIENT", "LINK_FOREGROUND", "TRANSPARENT", "TEXT_DISABLED_BACKGROUND",
            "WIDGET_DISABLED_FOREGROUND" };

    /**
     * The system colors ({@code SWT.COLOR_*}, from 1 to 39), written to {@code colors-cursors-<mode>.txt}, and the
     * cursors : the system cursors ({@code SWT.CURSOR_*}, from 0 to 21), and cursors created from images.
     */
    Object colorsAndCursors() throws Exception {
        require(SWT.COLOR_WHITE == 1 && SWT.COLOR_WIDGET_DISABLED_FOREGROUND == COLORS.length, "SWT.COLOR_* values");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < COLORS.length; i++) {
            Color color = display.getSystemColor(i + 1);
            require(color != null, "COLOR_" + COLORS[i]);
            text.append("COLOR_").append(COLORS[i]).append('=').append(color.getRed()).append(',')
                    .append(color.getGreen()).append(',').append(color.getBlue()).append(',').append(color.getAlpha())
                    .append('\n');
        }
        Color translucent = new Color(display, 10, 20, 30, 40);
        require(translucent.getRGBA().alpha == 40 && translucent.equals(new Color(display, 10, 20, 30, 40)),
                "Color alpha");
        checks.writeText("colors-cursors", text.toString());

        require(SWT.CURSOR_ARROW == 0 && SWT.CURSOR_HAND == 21, "SWT.CURSOR_* values");
        int cursors = 0;
        for (int style = SWT.CURSOR_ARROW; style <= SWT.CURSOR_HAND; style++) {
            Cursor cursor = display.getSystemCursor(style);
            require(cursor != null && !cursor.isDisposed(), "system cursor " + style);
            Cursor created = new Cursor(display, style);
            created.dispose();
            cursors++;
        }
        ImageData source = SwtChecks.icon(SwtPalette.ACCENT, 0).scaledTo(32, 32);
        PaletteData blackAndWhite = new PaletteData(new RGB(0, 0, 0), new RGB(255, 255, 255));
        ImageData bitmap = new ImageData(32, 32, 1, blackAndWhite);
        ImageData mask = new ImageData(32, 32, 1, blackAndWhite);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                bitmap.setPixel(x, y, (x + y) % 8 < 4 ? 1 : 0);
                mask.setPixel(x, y, Math.abs(x - y) < 6 || Math.abs(x + y - 31) < 6 ? 1 : 0);
            }
        }
        Shell shell = new Shell(display, SWT.SHELL_TRIM);
        Cursor[] images = { new Cursor(display, source, 16, 16), maskCursor(bitmap, mask),
                new Cursor(display, (ImageDataProvider) zoom -> source.scaledTo(32 * zoom / 100, 32 * zoom / 100),
                        8, 8) };
        try {
            for (Cursor cursor : images) {
                shell.setCursor(cursor);
                require(shell.getCursor() == cursor, "Control.setCursor");
            }
            shell.setCursor(null);
        } finally {
            shell.dispose();
            for (Cursor cursor : images) {
                cursor.dispose();
            }
        }
        return "colors=" + COLORS.length + " cursors=" + cursors + " imageCursors=" + images.length;
    }

    /**
     * A cursor from a source and a mask of 1 bit : deprecated, but another native path than the cursors with alpha.
     */
    @SuppressWarnings("deprecation")
    private Cursor maskCursor(ImageData source, ImageData mask) {
        return new Cursor(display, source, mask, 4, 4);
    }
}
