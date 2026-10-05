package io.quarkiverse.desktop.showcase.swt.pages.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.Bullet;
import org.eclipse.swt.custom.CBanner;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Sash;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * The custom widgets of {@code org.eclipse.swt.custom} : {@link StyledText} (style ranges with fonts, colors, the
 * underline styles single, double, error, squiggle and link, strikeout, borders, rise and an embedded object ; line
 * backgrounds, dot and numbered bullets, alignment, justification and indents ; word wrap, read-only, no caret),
 * {@link CTabFolder} (item images and close buttons, minimize and maximize buttons, a gradient selection background,
 * curved tabs), {@link CLabel} (an image and gradient backgrounds), {@link CCombo}, {@link SashForm} (horizontal and
 * vertical, with weights), {@link ScrolledComposite} (its origin set), {@link ViewForm} and {@link CBanner}.
 * <p>
 * These widgets are written in Java on top of a {@code Canvas} or a {@code Composite} : SWT paints them with a
 * {@code GC} (GDI and GDI+ on Windows, Cairo on GTK, Core Graphics on macOS) and lays their text out with
 * {@code TextLayout} (Uniscribe on Windows, Pango on GTK, the Cocoa text system on macOS), so the shaping and the line
 * breaking of the text, the justification, the underline styles, the glyph metrics of the embedded object and the
 * gradients go through the native graphics and text engines rather than through native controls. CCombo and the
 * scrolled composite combine native controls (a text field, an arrow button, a list in a hidden popup shell, scroll
 * bars). That matters for a native executable : SWT copies its structures (fonts, text metrics, script items,
 * rectangles) to and from native memory through JNI, and a missing registration shows up here as a crash, a blank or
 * misplaced widget, or a failed check, where the JVM works.
 * <p>
 * Checks : the StyledText model (offsets, lines, style ranges, bullets, alignment) and the state of the CTabFolder, the
 * CLabels and the CCombos are exact ; so is the geometry of the sash forms and the origin of the scrolled composite
 * (pure Java arithmetic on fixed sizes). The positions that depend on the fonts and on the theme (line heights, tab
 * height, the client area of the scrolled composite, the bounds in the ViewForm and the CBanner) are informational.
 * Every widget is in a fixed state : no caret, no focus, no selection, no animation, and no close button that the
 * mouse would show by hovering an unselected tab.
 */
@Singleton
public class SwtCustomWidgetsPage implements SwtPage {

    private static final int COLUMN_WIDTH = 490;
    private static final int HALF_WIDTH = 494;
    private static final int NAME_WIDTH = 190;
    private static final int SASH_WIDTH = 6;
    private static final int ORIGIN_X = 120;
    private static final int ORIGIN_Y = 80;
    private static final int[] COLORS = { 0x4FC3F7, 0xFFB74D, 0x81C784, 0xE57373, 0xBA68C8 };

    /** The object replacement character : the place of the embedded object in the text. */
    private static final char OBJECT = '\uFFFC';

    /**
     * The lines of the StyledText, joined with {@code \n} : the same offsets on every platform.
     */
    private static final String[] LINES = {
            "Bold, italic, monospace, red and highlighted text",
            "Underlined single, double, error, squiggle, link",
            "Strikeout, then solid, dashed and dotted borders",
            "Rise in E = mc2 and H2O, an embedded object " + OBJECT,
            "A dot bullet",
            "Another dot bullet",
            "A numbered line",
            "Another numbered line",
            "A third numbered line",
            "A centered line",
            "A right aligned line",
            "A justified paragraph, indented and word wrapped. StyledText breaks it into visual lines at the width of"
                    + " the widget and stretches the spaces of every visual line but the last one to both margins." };
    private static final String TEXT = String.join("\n", LINES);

    // per build state
    private Demo demo;

    /** The widgets of one build, checked once laid out. */
    private static final class Demo {
        StyledText styled;
        CTabFolder folder;
        CLabel imageLabel;
        CLabel gradientLabel;
        CCombo readOnlyCombo;
        CCombo editableCombo;
        SashForm horizontal;
        Control[] horizontalPanels;
        SashForm vertical;
        Control[] verticalPanels;
        ScrolledComposite scrolled;
        Control scrolledContent;
        ViewForm viewForm;
        CBanner banner;
        ChecksTable textTable;
        ChecksTable widgetTable;
        CompletionStage<Void> checked;
    }

    @Override
    public String id() {
        return "swt-custom-widgets";
    }

    @Override
    public String title() {
        return "Custom widgets";
    }

    @Override
    public String category() {
        return SwtCategories.CUSTOM;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Demo d = new Demo();
        demo = d;
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "The widgets of org.eclipse.swt.custom are written in Java on a Canvas or a Composite. SWT"
                + " paints them with a GC and lays their text out with TextLayout, so they exercise the native graphics"
                + " and text engines of the platform more than its native controls. Every widget is in a fixed state"
                + " (no caret, no focus, no selection); the checks verify the StyledText model and the state and"
                + " geometry of the other widgets.", SwtKit.TEXT_WIDTH);

        Composite top = SwtKit.row(page, 16);
        Composite left = SwtKit.column(top, 6);
        SwtKit.title(left, "StyledText");
        d.styled = styledText(left);
        Composite right = SwtKit.column(top, 6);
        SwtKit.title(right, "CTabFolder");
        d.folder = tabFolder(right);
        SwtKit.title(right, "CLabel");
        d.imageLabel = imageLabel(right);
        d.gradientLabel = gradientLabel(right);
        SwtKit.title(right, "CCombo");
        Composite combos = SwtKit.row(right, 12);
        d.readOnlyCombo = new CCombo(combos, SWT.BORDER | SWT.READ_ONLY);
        d.readOnlyCombo.setItems(new String[] { "Alpha", "Beta", "Gamma" });
        d.readOnlyCombo.setVisibleItemCount(3);
        d.readOnlyCombo.select(1);
        SwtKit.size(d.readOnlyCombo, 200, SWT.DEFAULT);
        d.editableCombo = new CCombo(combos, SWT.BORDER | SWT.FLAT);
        d.editableCombo.setItems(new String[] { "One", "Two" });
        d.editableCombo.setText("Typed text");
        SwtKit.size(d.editableCombo, 200, SWT.DEFAULT);

        Composite middle = SwtKit.row(page, 16);
        Composite horizontal = SwtKit.column(middle, 6);
        SwtKit.title(horizontal, "SashForm (horizontal)");
        d.horizontal = sashForm(horizontal, SWT.HORIZONTAL, new String[] { "1", "2", "1" }, 360, 100);
        d.horizontalPanels = d.horizontal.getChildren();
        d.horizontal.setWeights(1, 2, 1);
        SwtKit.caption(horizontal, "weights 1 : 2 : 1, sash width " + SASH_WIDTH);
        Composite vertical = SwtKit.column(middle, 6);
        SwtKit.title(vertical, "SashForm (vertical)");
        d.vertical = sashForm(vertical, SWT.VERTICAL, new String[] { "2", "1" }, 200, 100);
        d.verticalPanels = d.vertical.getChildren();
        d.vertical.setWeights(2, 1);
        SwtKit.caption(vertical, "weights 2 : 1");
        Composite scrolled = SwtKit.column(middle, 6);
        SwtKit.title(scrolled, "ScrolledComposite");
        d.scrolled = scrolledComposite(scrolled);
        d.scrolledContent = d.scrolled.getContent();
        SwtKit.caption(scrolled, "a 480 x 320 content, origin " + ORIGIN_X + ", " + ORIGIN_Y);

        Composite bottom = SwtKit.row(page, 12);
        Composite viewForm = SwtKit.column(bottom, 6);
        SwtKit.title(viewForm, "ViewForm");
        d.viewForm = viewForm(viewForm);
        Composite banner = SwtKit.column(bottom, 6);
        SwtKit.title(banner, "CBanner");
        d.banner = banner(banner);

        Composite tables = SwtKit.row(page, 12);
        d.textTable = ChecksTable.table(tables, "StyledText model", List.of(Check.info("state", "pending")),
                NAME_WIDTH, HALF_WIDTH);
        d.widgetTable = ChecksTable.table(tables, "Custom widgets", List.of(Check.info("state", "pending")),
                NAME_WIDTH, HALF_WIDTH);

        // build returns before the page frame lays the content out : the origin of the scrolled composite needs the
        // ranges of its scroll bars (set by its layout), then the checks read the final geometry
        d.checked = UiStages.rounds(1)
                .thenAccept(v -> {
                    if (!d.scrolled.isDisposed()) {
                        d.scrolled.setOrigin(ORIGIN_X, ORIGIN_Y);
                    }
                })
                .thenCompose(v -> UiStages.rounds(2))
                .thenAccept(v -> {
                    if (!d.textTable.isDisposed()) {
                        d.textTable.setChecks(textChecks(d));
                        d.widgetTable.setChecks(widgetChecks(d));
                    }
                });
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        Demo d = demo;
        return d == null ? CompletableFuture.completedFuture(null) : d.checked;
    }

    @Override
    public void dispose(Control content) {
        // the images are disposed with the widgets that show them
        demo = null;
    }

    // -------------------------------------------------------------------------------------------------- StyledText

    private static StyledText styledText(Composite parent) {
        StyledText styled = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
        styled.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
        styled.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
        styled.setBackground(SwtKit.color(SwtKit.WHITE));
        styled.setMargins(10, 8, 10, 8);
        styled.setLineSpacing(2);
        styled.setText(TEXT);
        // read-only (SWT.READ_ONLY) and no caret at all : nothing blinks, whatever the focus
        styled.setCaret(null);

        List<StyleRange> ranges = new ArrayList<>();
        ranges.add(range("Bold", r -> r.fontStyle = SWT.BOLD));
        ranges.add(range("italic", r -> r.fontStyle = SWT.ITALIC));
        ranges.add(range("monospace", r -> r.font = SwtKit.monospace(SwtKit.TEXT_POINTS)));
        ranges.add(range("red", r -> r.foreground = SwtKit.color(0xC62828)));
        ranges.add(range("highlighted", r -> r.background = SwtKit.color(0xFFF176)));
        ranges.add(underline("single", SWT.UNDERLINE_SINGLE, null));
        ranges.add(underline("double", SWT.UNDERLINE_DOUBLE, SwtKit.color(0x1565C0)));
        ranges.add(underline("error", SWT.UNDERLINE_ERROR, null));
        ranges.add(underline("squiggle", SWT.UNDERLINE_SQUIGGLE, SwtKit.color(0x2E7D32)));
        ranges.add(underline("link", SWT.UNDERLINE_LINK, null));
        ranges.add(range("Strikeout", r -> {
            r.strikeout = true;
            r.strikeoutColor = SwtKit.color(0xC62828);
        }));
        ranges.add(border("solid", SWT.BORDER_SOLID));
        ranges.add(border("dashed", SWT.BORDER_DASH));
        ranges.add(border("dotted", SWT.BORDER_DOT));
        ranges.add(range(TEXT.indexOf("mc2") + 2, 1, r -> {
            r.rise = 6;
            r.font = SwtKit.font(SWT.NORMAL, 7);
        }));
        ranges.add(range(TEXT.indexOf("H2O") + 1, 1, r -> {
            r.rise = -4;
            r.font = SwtKit.font(SWT.NORMAL, 7);
        }));
        // the embedded object : its glyph metrics reserve the space, a PaintObjectListener paints it
        ranges.add(range(TEXT.indexOf(OBJECT), 1, r -> r.metrics = new GlyphMetrics(14, 2, 22)));
        ranges.sort(Comparator.comparingInt(r -> r.start));
        styled.setStyleRanges(ranges.toArray(StyleRange[]::new));
        styled.addPaintObjectListener(event -> {
            GlyphMetrics metrics = event.style.metrics;
            if (event.bullet == null && metrics != null) {
                int top = event.y + event.ascent - metrics.ascent;
                event.gc.setBackground(SwtKit.color(0x7E57C2));
                event.gc.fillRoundRectangle(event.x + 3, top, metrics.width - 6, metrics.ascent + metrics.descent, 6,
                        6);
            }
        });

        StyleRange dotStyle = new StyleRange();
        dotStyle.metrics = new GlyphMetrics(0, 0, 24);
        dotStyle.foreground = SwtKit.color(0x1565C0);
        styled.setLineBullet(4, 2, new Bullet(ST.BULLET_DOT, dotStyle));
        StyleRange numberStyle = new StyleRange();
        numberStyle.metrics = new GlyphMetrics(0, 0, 30);
        numberStyle.fontStyle = SWT.BOLD;
        Bullet numbers = new Bullet(ST.BULLET_NUMBER | ST.BULLET_TEXT, numberStyle);
        numbers.text = ".";
        styled.setLineBullet(6, 3, numbers);

        styled.setLineBackground(0, 1, SwtKit.color(0xE3F2FD));
        styled.setLineBackground(4, 2, SwtKit.color(0xF1F8E9));
        styled.setLineBackground(9, 2, SwtKit.color(0xFFF8E1));
        styled.setLineAlignment(9, 1, SWT.CENTER);
        styled.setLineAlignment(10, 1, SWT.RIGHT);
        styled.setLineIndent(11, 1, 20);
        styled.setLineJustify(11, 1, true);
        // the preferred height of the wrapped text at this width
        SwtKit.size(styled, COLUMN_WIDTH, SWT.DEFAULT);
        return styled;
    }

    /**
     * A style range over the first occurrence of {@code word} in the text.
     */
    private static StyleRange range(String word, Consumer<StyleRange> style) {
        int start = TEXT.indexOf(word);
        if (start < 0) {
            throw new IllegalArgumentException("No " + word + " in the text");
        }
        return range(start, word.length(), style);
    }

    private static StyleRange range(int start, int length, Consumer<StyleRange> style) {
        StyleRange range = new StyleRange();
        range.start = start;
        range.length = length;
        style.accept(range);
        return range;
    }

    private static StyleRange underline(String word, int underlineStyle, Color color) {
        return range(word, r -> {
            r.underline = true;
            r.underlineStyle = underlineStyle;
            r.underlineColor = color;
        });
    }

    private static StyleRange border(String word, int borderStyle) {
        return range(word, r -> {
            r.borderStyle = borderStyle;
            r.borderColor = SwtKit.color(0x6A1B9A);
        });
    }

    // ------------------------------------------------------------------------------------------ CTabFolder, CLabel

    private static CTabFolder tabFolder(Composite parent) {
        CTabFolder folder = new CTabFolder(parent, SWT.BORDER | SWT.MULTI);
        folder.setSimple(false);
        folder.setMinimizeVisible(true);
        folder.setMaximizeVisible(true);
        // CTabFolder shows the close button of an unselected item when the mouse hovers it : never, for the same
        // pixels whatever the mouse does
        folder.setUnselectedCloseVisible(false);
        folder.setSelectionBackground(new Color[] { SwtKit.color(SwtKit.WHITE), SwtKit.color(0xCFE3F7) },
                new int[] { 100 }, true);
        String[] names = { "Overview", "Details", "History" };
        String[] bodies = {
                "The first item, created without SWT.CLOSE: no close button.",
                "The selected item, with its close button. Its control is visible, the controls of the other"
                        + " items are hidden.",
                "The third item, created with SWT.CLOSE: its close button is hidden while unselected." };
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            CTabItem item = new CTabItem(folder, i == 0 ? SWT.NONE : SWT.CLOSE);
            item.setText(names[i]);
            Image image = new Image(folder.getDisplay(), icon(COLORS[i], i));
            images.add(image);
            item.setImage(image);
            Composite body = new Composite(folder, SWT.NONE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 10;
            layout.marginHeight = 8;
            body.setLayout(layout);
            body.setBackground(SwtKit.color(SwtKit.WHITE));
            SwtKit.text(body, bodies[i], COLUMN_WIDTH - 30);
            item.setControl(body);
        }
        // the folder notifies its dispose listeners before it disposes its items, which never paint again
        folder.addListener(SWT.Dispose, event -> images.forEach(Image::dispose));
        folder.setSelection(1);
        SwtKit.size(folder, COLUMN_WIDTH, 96);
        return folder;
    }

    private static CLabel imageLabel(Composite parent) {
        CLabel label = new CLabel(parent, SWT.LEFT);
        label.setImage(image(label, icon(COLORS[4], 1)));
        label.setText("An image, a text and a horizontal gradient");
        label.setMargins(8, 6, 8, 6);
        label.setBackground(new Color[] { SwtKit.color(0xBBDEFB), SwtKit.color(0xE1F5FE), SwtKit.color(SwtKit.WHITE) },
                new int[] { 60, 100 }, false);
        SwtKit.size(label, COLUMN_WIDTH, SWT.DEFAULT);
        return label;
    }

    private static CLabel gradientLabel(Composite parent) {
        CLabel label = new CLabel(parent, SWT.CENTER | SWT.SHADOW_IN);
        label.setText("Centered, a vertical gradient, SHADOW_IN");
        label.setMargins(8, 6, 8, 6);
        label.setBackground(new Color[] { SwtKit.color(0xFFF3E0), SwtKit.color(0xFFCC80) }, new int[] { 100 }, true);
        SwtKit.size(label, COLUMN_WIDTH, SWT.DEFAULT);
        return label;
    }

    /**
     * An image created from {@code data}, disposed with {@code owner}.
     */
    private static Image image(Control owner, ImageData data) {
        Image image = new Image(owner.getDisplay(), data);
        owner.addListener(SWT.Dispose, event -> image.dispose());
        return image;
    }

    /**
     * A 16 x 16 icon with an alpha channel : a square ({@code shape} 0), a disc (1) or a triangle (2) of {@code rgb},
     * its edges anti-aliased by 4 x 4 supersampling. Pure Java : the same pixels everywhere.
     */
    static ImageData icon(int rgb, int shape) {
        int size = 16;
        ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
        byte[] alpha = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int covered = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        if (inside(shape, x + (sx + 0.5) / 4, y + (sy + 0.5) / 4)) {
                            covered++;
                        }
                    }
                }
                data.setPixel(x, y, rgb);
                alpha[y * size + x] = (byte) (covered * 255 / 16);
            }
        }
        data.alphaData = alpha;
        return data;
    }

    private static boolean inside(int shape, double x, double y) {
        return switch (shape) {
            case 0 -> x >= 2.5 && x <= 13.5 && y >= 2.5 && y <= 13.5;
            case 1 -> (x - 8) * (x - 8) + (y - 8) * (y - 8) <= 6.5 * 6.5;
            default -> y <= 14 && Math.abs(x - 8) <= (y - 2) * 6.5 / 12;
        };
    }

    // ------------------------------------------------------------------------------ SashForm and ScrolledComposite

    private static SashForm sashForm(Composite parent, int orientation, String[] weights, int width, int height) {
        SashForm sash = new SashForm(parent, orientation);
        // the background of a SashForm is the color of its sashes
        sash.setBackground(SwtKit.color(0x90A4AE));
        sash.setSashWidth(SASH_WIDTH);
        for (int i = 0; i < weights.length; i++) {
            panel(sash, "weight " + weights[i], COLORS[i]);
        }
        SwtKit.size(sash, width, height);
        return sash;
    }

    /**
     * A colored panel with its name centered, painted with a {@code GC}.
     */
    private static Canvas panel(Composite parent, String name, int rgb) {
        Canvas panel = new Canvas(parent, SWT.NO_FOCUS);
        panel.setBackground(SwtKit.color(rgb));
        panel.addListener(SWT.Paint, event -> {
            Rectangle area = panel.getClientArea();
            GC gc = event.gc;
            gc.setForeground(SwtKit.color(darker(rgb)));
            gc.drawRectangle(0, 0, area.width - 1, area.height - 1);
            gc.setFont(SwtKit.font(SWT.NORMAL, 8));
            gc.setForeground(SwtKit.color(0x212121));
            Point extent = gc.textExtent(name);
            gc.drawText(name, (area.width - extent.x) / 2, (area.height - extent.y) / 2, true);
        });
        return panel;
    }

    private static int darker(int rgb) {
        int r = (rgb >> 16 & 0xFF) * 7 / 10;
        int g = (rgb >> 8 & 0xFF) * 7 / 10;
        int b = (rgb & 0xFF) * 7 / 10;
        return r << 16 | g << 8 | b;
    }

    private static ScrolledComposite scrolledComposite(Composite parent) {
        ScrolledComposite scrolled = new ScrolledComposite(parent, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        // cells of 80 x 80 with their name and their origin in the content, at their top left corner
        Label content = SwtKit.painted(scrolled, 480, 320, gc -> {
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 6; column++) {
                    int x = column * 80;
                    int y = row * 80;
                    gc.setBackground(SwtKit.color((row + column) % 2 == 0 ? 0xE3F2FD : 0xFFF8E1));
                    gc.fillRectangle(x, y, 80, 80);
                    gc.setForeground(SwtKit.color(0x90A4AE));
                    gc.drawRectangle(x, y, 79, 79);
                    gc.setForeground(SwtKit.color(0x37474F));
                    gc.setFont(SwtKit.font(SWT.BOLD, 9));
                    gc.drawText((char) ('A' + column) + String.valueOf(row + 1), x + 6, y + 4, true);
                    gc.setFont(SwtKit.font(SWT.NORMAL, 7));
                    gc.drawText(x + "," + y, x + 6, y + 50, true);
                }
            }
        });
        content.setSize(content.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        scrolled.setContent(content);
        SwtKit.size(scrolled, 280, 110);
        return scrolled;
    }

    // ------------------------------------------------------------------------------------------- ViewForm, CBanner

    private static ViewForm viewForm(Composite parent) {
        ViewForm view = new ViewForm(parent, SWT.BORDER | SWT.FLAT);
        CLabel topLeft = new CLabel(view, SWT.NONE);
        topLeft.setImage(image(topLeft, icon(COLORS[2], 0)));
        topLeft.setText("Top left");
        CLabel topCenter = new CLabel(view, SWT.NONE);
        topCenter.setText("top center");
        topCenter.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
        CLabel topRight = new CLabel(view, SWT.NONE);
        topRight.setText("top right");
        topRight.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
        Composite content = new Composite(view, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 8;
        content.setLayout(layout);
        content.setBackground(SwtKit.color(0xFAFAFA));
        SwtKit.text(content, "The content, under the top row of CLabels. With SWT.BORDER the ViewForm draws its own"
                + " border.", HALF_WIDTH - 40);
        view.setTopLeft(topLeft);
        view.setTopCenter(topCenter);
        view.setTopRight(topRight);
        view.setContent(content);
        SwtKit.size(view, HALF_WIDTH, 96);
        return view;
    }

    private static CBanner banner(Composite parent) {
        CBanner banner = new CBanner(parent, SWT.NONE);
        // CBanner draws its curve and the line above the bottom control with SWT.COLOR_WIDGET_HIGHLIGHT_SHADOW (white
        // on Windows) : on a gray background
        banner.setBackground(SwtKit.color(0xCFD8DC));
        CLabel left = new CLabel(banner, SWT.NONE);
        left.setImage(image(left, icon(COLORS[0], 2)));
        left.setText("Left, a CLabel");
        left.setBackground(SwtKit.color(0xE3F2FD));
        CLabel right = new CLabel(banner, SWT.CENTER);
        right.setText("Right, 160 wide");
        right.setBackground(SwtKit.color(0xFFF3E0));
        CLabel bottom = new CLabel(banner, SWT.NONE);
        bottom.setText("Bottom, under the left and right controls");
        bottom.setBackground(SwtKit.color(0xF1F8E9));
        banner.setLeft(left);
        banner.setRight(right);
        banner.setBottom(bottom);
        // the curve between the left and the right controls
        banner.setSimple(false);
        banner.setRightWidth(160);
        SwtKit.size(banner, HALF_WIDTH, SWT.DEFAULT);
        return banner;
    }

    // ------------------------------------------------------------------------------------------------------ checks

    private static List<Check> textChecks(Demo d) {
        StyledText t = d.styled;
        int squiggle = TEXT.indexOf("squiggle");
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("getLineCount()", 12, t::getLineCount));
        checks.add(SwtChecks.expect("getCharCount()", 518, t::getCharCount));
        checks.add(SwtChecks.expect("getOffsetAtLine(1 / 4 / 11)", "50 / 194 / 323",
                () -> t.getOffsetAtLine(1) + " / " + t.getOffsetAtLine(4) + " / " + t.getOffsetAtLine(11)));
        checks.add(SwtChecks.expect("getLine(9)", "A centered line", () -> t.getLine(9)));
        checks.add(SwtChecks.expect("line of \"squiggle\"", 1, () -> t.getLineAtOffset(squiggle)));
        checks.add(SwtChecks.expect("getTextRange(" + squiggle + ", 8)", "squiggle",
                () -> t.getTextRange(squiggle, 8)));
        checks.add(SwtChecks.expect("getStyleRanges().length", 17, () -> t.getStyleRanges().length));
        checks.add(SwtChecks.expect("ranges in line 1", 5, () -> {
            int start = t.getOffsetAtLine(1);
            return t.getRanges(start, t.getLine(1).length()).length / 2;
        }));
        checks.add(SwtChecks.expect("Bold / italic", "BOLD / ITALIC",
                () -> fontStyle(style(t, "Bold")) + " / " + fontStyle(style(t, "italic"))));
        checks.add(SwtChecks.expect("underline of squiggle", "SQUIGGLE #2E7D32", () -> {
            StyleRange r = style(t, "squiggle");
            return underlineStyle(r) + " " + SwtChecks.rgb(r.underlineColor.getRGB());
        }));
        checks.add(SwtChecks.expect("single, double, error, link", "SINGLE DOUBLE ERROR LINK",
                () -> List.of("single", "double", "error", "link").stream()
                        .map(word -> underlineStyle(style(t, word))).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("strikeout and its color", "true #C62828", () -> {
            StyleRange r = style(t, "Strikeout");
            return r.strikeout + " " + SwtChecks.rgb(r.strikeoutColor.getRGB());
        }));
        checks.add(SwtChecks.expect("solid, dashed, dotted", "SOLID DASH DOT",
                () -> List.of("solid", "dashed", "dotted").stream()
                        .map(word -> borderStyle(style(t, word))).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("rise in mc2 / H2O", "6 / -4",
                () -> t.getStyleRangeAtOffset(TEXT.indexOf("mc2") + 2).rise + " / "
                        + t.getStyleRangeAtOffset(TEXT.indexOf("H2O") + 1).rise));
        checks.add(SwtChecks.expect("object glyph metrics", "14 / 2 / 22", () -> {
            GlyphMetrics m = t.getStyleRangeAtOffset(TEXT.indexOf(OBJECT)).metrics;
            return m.ascent + " / " + m.descent + " / " + m.width;
        }));
        checks.add(SwtChecks.expect("style of plain text", "null",
                () -> String.valueOf(t.getStyleRangeAtOffset(t.getOffsetAtLine(4)))));
        checks.add(SwtChecks.expect("bullets of lines 4 / 6 / 0", "DOT / NUMBER TEXT \".\" / none",
                () -> bullet(t.getLineBullet(4)) + " / " + bullet(t.getLineBullet(6)) + " / "
                        + bullet(t.getLineBullet(0))));
        checks.add(SwtChecks.expect("lines 6 to 8, one bullet", true,
                () -> t.getLineBullet(6) == t.getLineBullet(7) && t.getLineBullet(7) == t.getLineBullet(8)));
        checks.add(SwtChecks.expect("alignment of 0 / 9 / 10", "LEFT / CENTER / RIGHT",
                () -> alignment(t.getLineAlignment(0)) + " / " + alignment(t.getLineAlignment(9)) + " / "
                        + alignment(t.getLineAlignment(10))));
        checks.add(SwtChecks.expect("justify / indent of 11", "true / 20",
                () -> t.getLineJustify(11) + " / " + t.getLineIndent(11)));
        checks.add(SwtChecks.expect("backgrounds of 0 / 1 / 4", "#E3F2FD / none / #F1F8E9",
                () -> List.of(0, 1, 4).stream().map(line -> {
                    Color color = t.getLineBackground(line);
                    return color == null ? "none" : SwtChecks.rgb(color.getRGB());
                }).collect(Collectors.joining(" / "))));
        checks.add(SwtChecks.expect("wrap / editable / caret", "true / false / null",
                () -> t.getWordWrap() + " / " + t.getEditable() + " / " + t.getCaret()));
        checks.add(SwtChecks.info("line height (font)", t::getLineHeight));
        checks.add(SwtChecks.info("paragraph height (font)", () -> t.getLinePixel(12) - t.getLinePixel(11)));
        checks.add(SwtChecks.info("location of \"link\" (font)",
                () -> SwtChecks.point(t.getLocationAtOffset(TEXT.indexOf("link")))));
        checks.add(SwtChecks.info("size (font)", () -> SwtChecks.size(t.getSize())));
        return checks;
    }

    private static List<Check> widgetChecks(Demo d) {
        List<Check> checks = new ArrayList<>();
        CTabFolder folder = d.folder;
        checks.add(SwtChecks.expect("tab items / selection", "3 / 1",
                () -> folder.getItemCount() + " / " + folder.getSelectionIndex()));
        checks.add(SwtChecks.expect("tab texts", "Overview Details History", () -> items(folder, CTabItem::getText)));
        checks.add(SwtChecks.expect("tab close buttons", "false true true",
                () -> items(folder, CTabItem::getShowClose)));
        checks.add(SwtChecks.expect("tab images", "true true true", () -> items(folder, i -> i.getImage() != null)));
        checks.add(SwtChecks.expect("tab controls visible", "false true false",
                () -> items(folder, i -> i.getControl().getVisible())));
        checks.add(SwtChecks.expect("minimize / maximize", "true / true",
                () -> folder.getMinimizeVisible() + " / " + folder.getMaximizeVisible()));
        checks.add(SwtChecks.expect("unselected close / simple", "false / false",
                () -> folder.getUnselectedCloseVisible() + " / " + folder.getSimple()));
        checks.add(SwtChecks.info("tab height, bounds (font)",
                () -> folder.getTabHeight() + " / " + SwtChecks.rect(folder.getSelection().getBounds())));
        checks.add(SwtChecks.expect("CLabel alignments, images", "LEFT CENTER / true false",
                () -> alignment(d.imageLabel.getAlignment()) + " " + alignment(d.gradientLabel.getAlignment()) + " / "
                        + (d.imageLabel.getImage() != null) + " " + (d.gradientLabel.getImage() != null)));
        checks.add(SwtChecks.expect("read-only CCombo", "3 items / 1 / Beta / false",
                () -> d.readOnlyCombo.getItemCount() + " items / " + d.readOnlyCombo.getSelectionIndex() + " / "
                        + d.readOnlyCombo.getText() + " / " + d.readOnlyCombo.getEditable()));
        checks.add(SwtChecks.expect("flat CCombo", "Typed text / -1 / true",
                () -> d.editableCombo.getText() + " / " + d.editableCombo.getSelectionIndex() + " / "
                        + d.editableCombo.getEditable()));
        checks.add(SwtChecks.expect("horizontal weights", "[250, 500, 250]",
                () -> Arrays.toString(d.horizontal.getWeights())));
        checks.add(SwtChecks.expect("horizontal panels; sashes",
                "0,0 87x100 93,0 174x100 273,0 87x100 ; 87,0 6x100 267,0 6x100",
                () -> sashBounds(d.horizontal, d.horizontalPanels)));
        checks.add(SwtChecks.expect("vertical weights", "[666, 333]", () -> Arrays.toString(d.vertical.getWeights())));
        checks.add(SwtChecks.expect("vertical panels; sash", "0,0 200x62 0,68 200x32 ; 0,62 200x6",
                () -> sashBounds(d.vertical, d.verticalPanels)));
        checks.add(SwtChecks.expect("origin / content location", "120,80 / -120,-80",
                () -> SwtChecks.point(d.scrolled.getOrigin()) + " / "
                        + SwtChecks.point(d.scrolledContent.getLocation())));
        checks.add(SwtChecks.expect("scroll bars / content size", "120 / 80 / 480x320",
                () -> d.scrolled.getHorizontalBar().getSelection() + " / " + d.scrolled.getVerticalBar().getSelection()
                        + " / " + SwtChecks.size(d.scrolledContent.getSize())));
        checks.add(SwtChecks.info("client area (theme)", () -> SwtChecks.rect(d.scrolled.getClientArea())));
        checks.add(SwtChecks.expect("ViewForm parts set", "true true true true",
                () -> (d.viewForm.getTopLeft() instanceof CLabel) + " " + (d.viewForm.getTopCenter() instanceof CLabel)
                        + " " + (d.viewForm.getTopRight() instanceof CLabel) + " "
                        + (d.viewForm.getContent() instanceof Composite)));
        checks.add(SwtChecks.info("ViewForm top left, content (font)",
                () -> SwtChecks.rect(d.viewForm.getTopLeft().getBounds()) + " / "
                        + SwtChecks.rect(d.viewForm.getContent().getBounds())));
        checks.add(SwtChecks.expect("CBanner parts, right width", "true true true / 160 / false",
                () -> (d.banner.getLeft() != null) + " " + (d.banner.getRight() != null) + " "
                        + (d.banner.getBottom() != null) + " / " + d.banner.getRightWidth() + " / "
                        + d.banner.getSimple()));
        checks.add(SwtChecks.info("CBanner bounds (font)",
                () -> SwtChecks.rect(d.banner.getLeft().getBounds()) + " / "
                        + SwtChecks.rect(d.banner.getRight().getBounds()) + " / "
                        + SwtChecks.rect(d.banner.getBottom().getBounds())));
        return checks;
    }

    private static StyleRange style(StyledText t, String word) {
        return t.getStyleRangeAtOffset(TEXT.indexOf(word));
    }

    private static String items(CTabFolder folder, Function<CTabItem, Object> value) {
        return Arrays.stream(folder.getItems()).map(item -> String.valueOf(value.apply(item)))
                .collect(Collectors.joining(" "));
    }

    /**
     * {@code x,y wxh} of the panels, then of the sashes (sorted by position) of a sash form.
     */
    private static String sashBounds(SashForm sash, Control[] panels) {
        String controls = Arrays.stream(panels).map(c -> SwtChecks.rect(c.getBounds()))
                .collect(Collectors.joining(" "));
        String sashes = Arrays.stream(sash.getChildren()).filter(Sash.class::isInstance).map(Control::getBounds)
                .sorted(Comparator.comparingInt((Rectangle r) -> r.x).thenComparingInt(r -> r.y))
                .map(SwtChecks::rect).collect(Collectors.joining(" "));
        return controls + " ; " + sashes;
    }

    private static String fontStyle(StyleRange r) {
        return switch (r.fontStyle) {
            case SWT.NORMAL -> "NORMAL";
            case SWT.BOLD -> "BOLD";
            case SWT.ITALIC -> "ITALIC";
            default -> "BOLD ITALIC";
        };
    }

    private static String underlineStyle(StyleRange r) {
        if (!r.underline) {
            return "none";
        }
        return switch (r.underlineStyle) {
            case SWT.UNDERLINE_SINGLE -> "SINGLE";
            case SWT.UNDERLINE_DOUBLE -> "DOUBLE";
            case SWT.UNDERLINE_ERROR -> "ERROR";
            case SWT.UNDERLINE_SQUIGGLE -> "SQUIGGLE";
            case SWT.UNDERLINE_LINK -> "LINK";
            default -> String.valueOf(r.underlineStyle);
        };
    }

    private static String borderStyle(StyleRange r) {
        return switch (r.borderStyle) {
            case SWT.BORDER_SOLID -> "SOLID";
            case SWT.BORDER_DASH -> "DASH";
            case SWT.BORDER_DOT -> "DOT";
            default -> String.valueOf(r.borderStyle);
        };
    }

    private static String alignment(int alignment) {
        return switch (alignment) {
            case SWT.LEFT -> "LEFT";
            case SWT.CENTER -> "CENTER";
            case SWT.RIGHT -> "RIGHT";
            default -> String.valueOf(alignment);
        };
    }

    private static String bullet(Bullet bullet) {
        if (bullet == null) {
            return "none";
        }
        List<String> types = new ArrayList<>();
        if ((bullet.type & ST.BULLET_DOT) != 0) {
            types.add("DOT");
        }
        if ((bullet.type & ST.BULLET_NUMBER) != 0) {
            types.add("NUMBER");
        }
        if ((bullet.type & ST.BULLET_TEXT) != 0) {
            types.add("TEXT \"" + bullet.text + "\"");
        }
        return String.join(" ", types);
    }
}
