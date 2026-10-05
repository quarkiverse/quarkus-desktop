package io.quarkiverse.desktop.showcase.swt.pages.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.graphics.TextStyle;
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
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * Fonts and text with SWT : the system font and its {@link FontData}, fonts in styles, sizes and families, the font
 * lists of the display, {@link FontMetrics}, {@code GC.drawText}, {@code textExtent} and {@code stringExtent} with the
 * flags {@code DRAW_TAB}, {@code DRAW_MNEMONIC}, {@code DRAW_DELIMITER} and {@code DRAW_TRANSPARENT}, and
 * {@link TextLayout} : {@link TextStyle}s (fonts, colors, underline styles and links, strikeout, rise, borders,
 * {@link GlyphMetrics} of inline objects), selection, wrapping, alignment and justification, indents, tab stops, line
 * spacing, a bidirectional paragraph (Arabic and Hebrew) in both orientations, segments, bidi levels and the caret
 * movements of {@code getNextOffset} ({@code MOVEMENT_CLUSTER}, {@code MOVEMENT_WORD_START},
 * {@code MOVEMENT_WORD_END}).
 * <p>
 * Every drawing is painted offscreen with a {@code GC} into an image ({@link SwtKit#painted}) : the same pixels
 * whatever the screen. On Windows, {@code TextLayout} itemizes, shapes, places, breaks and justifies the text with
 * Uniscribe ({@code ScriptItemize}, {@code ScriptShape}, {@code ScriptPlace}, {@code ScriptBreak},
 * {@code ScriptJustify}, {@code ScriptLayout}, {@code ScriptTextOut} : the structs {@code SCRIPT_ITEM},
 * {@code SCRIPT_ANALYSIS}, {@code SCRIPT_STATE}, {@code SCRIPT_CONTROL}, {@code SCRIPT_LOGATTR},
 * {@code SCRIPT_PROPERTIES}, {@code SCRIPT_FONTPROPERTIES}, {@code GOFFSET}, {@code ABC}), reads the underline and
 * strikeout positions from {@code OUTLINETEXTMETRIC}, and draws with GDI+ ({@code DrawDriverString}) in an advanced
 * {@code GC} (the bidirectional drawing, anti-aliased) ; an advanced {@code GC} measures and draws its own text with
 * {@code GetCharacterPlacement} ({@code GCP_RESULTS}) ; the font list comes from {@code EnumFontFamilies} through a
 * {@code Callback} ({@code LOGFONT}, {@code TEXTMETRIC}). On Linux, a {@code TextLayout} is a Pango layout (attribute
 * lists, {@code PangoLogAttr}, {@code PangoRectangle}, {@code PangoLayoutLine}) drawn with Cairo ; on macOS, an
 * {@code NSLayoutManager}.
 * <p>
 * In a native executable, every struct that SWT's natives read or write through JNI needs its fields registered for
 * JNI access, and every {@code Callback} target method its reflection metadata : a missing entry fails, crashes or
 * silently lays the text out wrong (zero-filled glyph advances, levels or break attributes). The page is a sensitive
 * probe of the native configuration of quarkus-desktop-swt : every pixel and every check must be equal in the JVM and
 * native runs.
 * <p>
 * The layouts of the RIGHT_TO_LEFT orientation are drawn with a mirrored {@code GC} ({@code new GC(image,
 * SWT.RIGHT_TO_LEFT)}), as in a right-to-left widget : in a left-to-right advanced {@code GC}, SWT mirrors their glyphs
 * on Windows. The drawings measure themselves (a first pass into an image of one pixel high) : the fonts, and their
 * size in pixels, are those of the machine (the resolution of the monitor, whatever {@code swt.autoScale}).
 * <p>
 * The fonts are the system fonts of the machine : their metrics and extents are informational (the same in the two
 * runs of one machine) ; the results that Unicode or the SWT contract define (line offsets of explicit line breaks,
 * bidi levels, grapheme clusters, getters, segments) are checked everywhere, the platform specific ones on Windows
 * only.
 */
@Singleton
public class SwtTextFontsPage implements SwtPage {

    /** Width of the drawings. */
    private static final int WIDTH = 1000;
    /** Width of the name column of the check tables. */
    private static final int NAME_WIDTH = 330;

    private static final int INK = 0x263238;
    private static final int MUTED = 0x607D8B;
    private static final int RULE = 0xB0BEC5;
    private static final int FRAME = 0xCFD8DC;
    private static final int RED = 0xC62828;
    private static final int BLUE = 0x1565C0;
    private static final int GREEN = 0x2E7D32;
    private static final int HIGHLIGHT = 0xFFE9A8;
    private static final int SELECTION = 0x3367D6;

    static final int[] SIZES = { 8, 10, 12, 16, 22 };
    static final int[] STYLES = { SWT.NORMAL, SWT.BOLD, SWT.ITALIC, SWT.BOLD | SWT.ITALIC };
    static final String PANGRAM = "Sphinx of black quartz, judge my vow";
    static final String MISSING_FONT = "Showcase Missing Font";

    static final String FLAGS_SAMPLE = "Tab\t&Key\nLine 2";
    static final int ALL_FLAGS = SWT.DRAW_TAB | SWT.DRAW_MNEMONIC | SWT.DRAW_DELIMITER | SWT.DRAW_TRANSPARENT;
    static final int[] FLAGS = { SWT.DRAW_TAB, SWT.DRAW_MNEMONIC, SWT.DRAW_DELIMITER, ALL_FLAGS };

    static final String PARAGRAPH = "SWT lays this paragraph out with the text engine of the platform: Uniscribe on"
            + " Windows, Pango on Linux and Core Text on macOS. Every line wraps at the width of the layout.";
    /** Width of the wrapped paragraphs. */
    static final int BOX = 232;
    static final int[] ALIGNMENTS = { SWT.LEFT, SWT.CENTER, SWT.RIGHT, SWT.LEFT };
    /** Width of the layouts of the second row (indents, tab stops, line breaks). */
    static final int WIDE_BOX = 310;
    static final int INDENT = 16;
    static final int WRAP_INDENT = 32;
    static final int SPACING = 6;
    static final String TABS = "Name\tSize\tKind\nphoto.png\t12 KB\tPNG image\nnotes.txt\t2 KB\tText\n"
            + "logo.svg\t9 KB\tSVG";
    static final int[] TAB_STOPS = { 110, 180 };
    static final String BREAKS = "First line\nSecond line\r\nThird line\rFourth line";

    static final String MIXED = "SWT 3.132 draws שלום עולם and مرحبا بالعالم (2026).";
    static final String ARABIC = "مرحبا بالعالم! هذا نص عربي يحتوي على كلمة Quarkus والرقم 2026.";
    static final String HEBREW = "שלום עולם! זהו טקסט עברי עם המילה Java והמספר 25.";
    /** A path of Hebrew folder names : reordered as one right-to-left run without segments. */
    static final String PATH = "מסמכים/תמונות/קובץ.png";
    static final String LEVELS = "abc אבג 123";
    /** Combining acute accent, precomposed i diaeresis, Hebrew with points (qamats, shin dot, holam), Arabic. */
    static final String CARETS = "Café naïve, שָׁלוֹם and مرحبا";
    static final char LRM = '‎';
    static final char OBJECT = '￼';
    /** The {@link GlyphMetrics} of the inline objects : ascent, descent, width. */
    static final int OBJECT_ASCENT = 18;
    static final int OBJECT_DESCENT = 4;
    static final int OBJECT_WIDTH = 40;

    /** The checks, set once the content is shown : completes on the user interface thread. */
    private CompletionStage<Void> checked = CompletableFuture.completedFuture(null);

    @Override
    public String id() {
        return "swt-text-fonts";
    }

    @Override
    public String title() {
        return "Fonts and text layout";
    }

    @Override
    public String category() {
        return SwtCategories.TEXT;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Display display = parent.getDisplay();
        Composite page = SwtKit.page(parent, 8);
        SwtKit.heading(page, "Fonts and text layout");
        SwtKit.text(page, "The system font and its FontData, fonts in styles, sizes and families, FontMetrics, GC text"
                + " with its drawing flags, and TextLayout: styles, wrapping, alignment, tab stops, bidirectional text,"
                + " segments and caret movement. Everything is painted offscreen with a GC; the metrics depend on the"
                + " fonts of the machine, the structure of the layouts on Unicode.", SwtKit.TEXT_WIDTH);
        SwtKit.title(page, "Fonts: the system font, styles, sizes and families, FontMetrics");
        painted(page, SwtTextFontsPage::paintFonts);
        SwtKit.title(page, "GC text: drawString and drawText flags, stringExtent and textExtent (red)");
        painted(page, SwtTextFontsPage::paintExtents);
        SwtKit.title(page, "TextLayout: text styles, selection and inline objects (GlyphMetrics)");
        painted(page, SwtTextFontsPage::paintStyles);
        SwtKit.title(page, "TextLayout: wrapping, alignment, justification, indents, spacing, tab stops, line breaks");
        painted(page, SwtTextFontsPage::paintLayouts);
        SwtKit.title(page, "Bidirectional text, orientation, segments and caret movement (anti-aliased, advanced GC)");
        painted(page, SwtTextFontsPage::paintBidi);
        ChecksTable fonts = ChecksTable.table(page, "Fonts and GC text", List.of(Check.info("state", "pending")),
                NAME_WIDTH, ChecksTable.WIDTH);
        ChecksTable layouts = ChecksTable.table(page, "TextLayout", List.of(Check.info("state", "pending")),
                NAME_WIDTH, ChecksTable.WIDTH);
        // computed once the content is shown (in the interactive mode too) ; ready() waits for it
        checked = UiStages.rounds(1).thenAccept(v -> {
            if (!fonts.isDisposed()) {
                fonts.setChecks(fontChecks(display));
                layouts.setChecks(layoutChecks(display));
            }
        });
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        return checked;
    }

    // ------------------------------------------------------------------------------------------------------- fonts

    /**
     * An image {@link #WIDTH} wide painted by {@code painter}, as high as the drawing : the painter returns the bottom
     * of its drawing, measured by a first pass into an image of one pixel high. The fonts are those of the machine (and
     * of its resolution) : the drawings adapt to their metrics.
     */
    private static void painted(Composite parent, ToIntFunction<GC> painter) {
        int[] bottom = { 1 };
        SwtSnapshots.offscreen(WIDTH, 1, gc -> bottom[0] = painter.applyAsInt(gc));
        SwtKit.painted(parent, WIDTH, bottom[0] + 2, painter::applyAsInt);
    }

    /**
     * The font families of the right column : monospace, serif and a font that is not installed (substituted).
     */
    private static String[][] families() {
        String mono = SwtMode.pick("Menlo", "Consolas", "Monospace");
        String serif = serif();
        return new String[][] { { mono, mono + " : 0O 1lI {}[] -> != ==" }, { serif, serif + " : Hamburgefonstiv" },
                { MISSING_FONT, MISSING_FONT + " (substituted)" } };
    }

    private static String serif() {
        return SwtMode.pick("Times New Roman", "Times New Roman", "Serif");
    }

    private static int paintFonts(GC gc) {
        Display display = UiStages.display();
        gc.setForeground(SwtKit.color(INK));
        int y = 0;
        for (int points : SIZES) {
            gc.setFont(SwtKit.font(SWT.NORMAL, points));
            gc.drawString(points + " pt  " + PANGRAM, 0, y, true);
            y += gc.getFontMetrics().getHeight() + 2;
        }
        int x = 0;
        y += 4;
        for (int style : STYLES) {
            gc.setFont(SwtKit.font(style, 12));
            String name = styleName(style);
            gc.drawString(name, x, y, true);
            x += gc.stringExtent(name).x + 18;
        }
        y += gc.getFontMetrics().getHeight() + 10;

        // the system font and three families on the left, the metrics of a large font on the right
        int top = y;
        gc.setFont(display.getSystemFont());
        gc.drawString("Display.getSystemFont() : " + SwtChecks.font(display.getSystemFont().getFontData()[0]), 0, y,
                true);
        y += gc.getFontMetrics().getHeight() + 4;
        for (String[] family : families()) {
            gc.setFont(SwtKit.font(family[0], SWT.NORMAL, 11));
            gc.drawString(family[1], 0, y, true);
            y += gc.getFontMetrics().getHeight() + 1;
        }
        return Math.max(y, paintMetrics(gc, 600, top + 4));
    }

    /**
     * A large sample with the lines of its {@link FontMetrics} : top of the cell, leading, ascent (baseline), descent.
     * Returns the bottom of the drawing.
     */
    private static int paintMetrics(GC gc, int x, int top) {
        gc.setFont(SwtKit.font(SWT.NORMAL, 28));
        FontMetrics m = gc.getFontMetrics();
        String sample = "Ágy";
        Point extent = gc.stringExtent(sample);
        gc.setForeground(SwtKit.color(INK));
        gc.drawString(sample, x, top, true);
        int baseline = top + m.getLeading() + m.getAscent();
        int[] ys = { top, top + m.getLeading(), baseline, baseline + m.getDescent() };
        String[] names = { "top of the cell", "leading " + m.getLeading(), "ascent " + m.getAscent() + ", baseline",
                "descent " + m.getDescent() + " (height " + m.getHeight() + ")" };
        int[] colors = { RULE, GREEN, RED, BLUE };
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        int labelHeight = gc.getFontMetrics().getHeight();
        int right = x + extent.x + 8;
        int labelY = Integer.MIN_VALUE;
        for (int i = 0; i < ys.length; i++) {
            gc.setForeground(SwtKit.color(colors[i]));
            gc.drawLine(x - 6, ys[i], right, ys[i]);
            labelY = Math.max(ys[i] - labelHeight / 2, labelY + labelHeight);
            gc.drawLine(right, ys[i], right + 10, labelY + labelHeight / 2);
            gc.drawString(names[i], right + 14, labelY, true);
        }
        return Math.max(top + m.getHeight(), labelY + labelHeight);
    }

    // ------------------------------------------------------------------------------------------------- GC extents

    private static int paintExtents(GC gc) {
        gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
        int boxHeight = 2 * gc.getFontMetrics().getHeight() + 14;
        int captionHeight = 0;
        for (int i = 0; i <= FLAGS.length; i++) {
            int x = i * 200;
            hatch(gc, x, 0, 196, boxHeight);
            gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
            gc.setForeground(SwtKit.color(INK));
            // the background of the text when it is not transparent
            gc.setBackground(SwtKit.color(HIGHLIGHT));
            Point extent;
            String caption;
            String size;
            if (i == 0) {
                gc.drawString(FLAGS_SAMPLE, x + 6, 6);
                extent = gc.stringExtent(FLAGS_SAMPLE);
                caption = "drawString";
                size = "stringExtent ";
            } else {
                gc.drawText(FLAGS_SAMPLE, x + 6, 6, FLAGS[i - 1]);
                extent = gc.textExtent(FLAGS_SAMPLE, FLAGS[i - 1]);
                caption = "drawText " + flagNames(FLAGS[i - 1]);
                size = "textExtent ";
            }
            gc.setForeground(SwtKit.color(RED));
            gc.drawRectangle(x + 6, 6, Math.max(0, extent.x - 1), Math.max(0, extent.y - 1));
            gc.setFont(SwtKit.font(SWT.NORMAL, 8));
            gc.setForeground(SwtKit.color(MUTED));
            captionHeight = gc.getFontMetrics().getHeight();
            gc.drawString(caption, x, boxHeight + 4, true);
            gc.drawString(size + SwtChecks.size(extent), x, boxHeight + 4 + captionHeight, true);
        }
        return boxHeight + 4 + 2 * captionHeight;
    }

    /**
     * A light hatched background : shows where the text is drawn transparent.
     */
    private static void hatch(GC gc, int x, int y, int width, int height) {
        gc.setBackground(SwtKit.color(0xF5F7FA));
        gc.fillRectangle(x, y, width, height);
        gc.setClipping(x, y, width, height);
        gc.setForeground(SwtKit.color(0xD5DDE5));
        for (int d = -height; d < width; d += 6) {
            gc.drawLine(x + d, y + height, x + d + height, y);
        }
        gc.setClipping((Rectangle) null);
    }

    static String flagNames(int flags) {
        if (flags == ALL_FLAGS) {
            return "all four";
        }
        List<String> names = new ArrayList<>();
        if ((flags & SWT.DRAW_TAB) != 0) {
            names.add("TAB");
        }
        if ((flags & SWT.DRAW_MNEMONIC) != 0) {
            names.add("MNEMONIC");
        }
        if ((flags & SWT.DRAW_DELIMITER) != 0) {
            names.add("DELIMITER");
        }
        if ((flags & SWT.DRAW_TRANSPARENT) != 0) {
            names.add("TRANSPARENT");
        }
        return names.isEmpty() ? "0" : String.join(" ", names);
    }

    static String styleName(int style) {
        return switch (style) {
            case SWT.BOLD -> "Bold";
            case SWT.ITALIC -> "Italic";
            case SWT.BOLD | SWT.ITALIC -> "Bold Italic";
            default -> "Normal";
        };
    }

    // ------------------------------------------------------------------------------------------------ text styles

    /**
     * A styled run of a {@link Styled} text : {@code start} and {@code end} inclusive, as {@code TextLayout.setStyle}.
     */
    record Run(int start, int end, TextStyle style) {
    }

    /**
     * A text built piece by piece with the styles of its runs, the range of its selection and the offsets of its
     * inline objects.
     */
    static final class Styled {

        private final StringBuilder text = new StringBuilder();
        private final List<Run> runs = new ArrayList<>();
        private final List<Integer> objects = new ArrayList<>();
        private int selectionStart = -1;
        private int selectionEnd = -1;

        Styled add(String s) {
            text.append(s);
            return this;
        }

        Styled add(String s, TextStyle style) {
            int start = text.length();
            text.append(s);
            runs.add(new Run(start, text.length() - 1, style));
            return this;
        }

        Styled select(String s) {
            selectionStart = text.length();
            text.append(s);
            selectionEnd = text.length() - 1;
            return this;
        }

        Styled object(TextStyle style) {
            objects.add(text.length());
            return add(String.valueOf(OBJECT), style);
        }

        String text() {
            return text.toString();
        }

        List<Run> runs() {
            return runs;
        }

        List<Integer> objects() {
            return objects;
        }

        TextLayout layout(Display display) {
            TextLayout layout = new TextLayout(display);
            layout.setFont(SwtKit.font(SWT.NORMAL, 10));
            layout.setText(text());
            for (Run run : runs) {
                layout.setStyle(run.style(), run.start(), run.end());
            }
            return layout;
        }
    }

    private static TextStyle font(Font font) {
        return new TextStyle(font, null, null);
    }

    private static TextStyle colors(int foreground, int background) {
        return new TextStyle(null, foreground < 0 ? null : SwtKit.color(foreground),
                background < 0 ? null : SwtKit.color(background));
    }

    private static TextStyle underline(int underlineStyle, int color) {
        TextStyle style = new TextStyle();
        style.underline = true;
        style.underlineStyle = underlineStyle;
        style.underlineColor = color < 0 ? null : SwtKit.color(color);
        return style;
    }

    private static TextStyle strikeout(int color) {
        TextStyle style = new TextStyle();
        style.strikeout = true;
        style.strikeoutColor = color < 0 ? null : SwtKit.color(color);
        return style;
    }

    private static TextStyle rise(int rise) {
        TextStyle style = font(SwtKit.font(SWT.NORMAL, 7));
        style.rise = rise;
        return style;
    }

    private static TextStyle border(int borderStyle, int color) {
        TextStyle style = new TextStyle();
        style.borderStyle = borderStyle;
        style.borderColor = color < 0 ? null : SwtKit.color(color);
        return style;
    }

    private static TextStyle object() {
        TextStyle style = new TextStyle();
        style.metrics = new GlyphMetrics(OBJECT_ASCENT, OBJECT_DESCENT, OBJECT_WIDTH);
        return style;
    }

    /**
     * The styled sample : one line per kind of style.
     */
    static Styled styled() {
        Styled s = new Styled();
        s.add("Fonts: ").add("bold", font(SwtKit.font(SWT.BOLD, 10))).add(", ")
                .add("italic", font(SwtKit.font(SWT.ITALIC, 10))).add(", ")
                .add("16 points", font(SwtKit.font(SWT.NORMAL, 16))).add(", ")
                .add("monospace", font(SwtKit.monospace(10))).add(", ")
                .add("serif", font(SwtKit.font(serif(), SWT.NORMAL, 11))).add(".\n");
        s.add("Colors: ").add("red", colors(RED, -1)).add(", ").add("white on blue", colors(0xFFFFFF, BLUE)).add(", ")
                .add("green background", colors(-1, 0xC8E6C9)).add(", ").select("selected text").add(".\n");
        s.add("Underline: ").add("single", underline(SWT.UNDERLINE_SINGLE, -1)).add(", ")
                .add("double", underline(SWT.UNDERLINE_DOUBLE, -1)).add(", ")
                .add("error", underline(SWT.UNDERLINE_ERROR, -1)).add(", ")
                .add("squiggle", underline(SWT.UNDERLINE_SQUIGGLE, GREEN)).add(", ")
                .add("link", underline(SWT.UNDERLINE_LINK, -1)).add(", ")
                .add("red single", underline(SWT.UNDERLINE_SINGLE, RED)).add(".\n");
        s.add("Strikeout: ").add("struck", strikeout(-1)).add(", ").add("red strikeout", strikeout(RED))
                .add(".   Rise: E = mc").add("2", rise(6)).add(", H").add("2", rise(-3)).add("O, x").add("2", rise(6))
                .add(" + y").add("2", rise(6)).add(".\n");
        s.add("Border: ").add("solid", border(SWT.BORDER_SOLID, -1)).add(", ").add("dash", border(SWT.BORDER_DASH, -1))
                .add(", ").add("dot", border(SWT.BORDER_DOT, -1)).add(", ").add("red", border(SWT.BORDER_SOLID, RED))
                .add(".   GlyphMetrics(18, 4, 40) objects: ").object(object()).add(" and ").object(object()).add(".");
        return s;
    }

    private static int paintStyles(GC gc) {
        Display display = UiStages.display();
        Styled styled = styled();
        TextLayout layout = styled.layout(display);
        try {
            gc.setForeground(SwtKit.color(INK));
            layout.draw(gc, 8, 4, styled.selectionStart, styled.selectionEnd, SwtKit.color(0xFFFFFF),
                    SwtKit.color(SELECTION));
            // the inline objects : drawn by the application in the space of their GlyphMetrics, on the baseline (red)
            int index = 0;
            for (int offset : styled.objects()) {
                Rectangle r = layout.getBounds(offset, offset);
                FontMetrics line = layout.getLineMetrics(layout.getLineIndex(offset));
                int baseline = 4 + r.y + line.getLeading() + line.getAscent();
                int top = baseline - OBJECT_ASCENT;
                gc.setBackground(SwtKit.color(index == 0 ? 0x81C784 : 0x64B5F6));
                gc.fillRoundRectangle(8 + r.x + 2, top, r.width - 4, OBJECT_ASCENT + OBJECT_DESCENT, 6, 6);
                gc.setForeground(SwtKit.color(INK));
                gc.drawRoundRectangle(8 + r.x + 2, top, r.width - 5, OBJECT_ASCENT + OBJECT_DESCENT - 1, 6, 6);
                gc.setForeground(SwtKit.color(RED));
                gc.drawLine(8 + r.x, baseline, 8 + r.x + r.width - 1, baseline);
                index++;
            }
            return 4 + layout.getBounds().height + 4;
        } finally {
            layout.dispose();
        }
    }

    // ---------------------------------------------------------------------------------------------------- layouts

    static TextLayout paragraph(Display display, int alignment, boolean justify) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
        layout.setText(PARAGRAPH);
        layout.setWidth(BOX);
        layout.setAlignment(alignment);
        layout.setJustify(justify);
        return layout;
    }

    static TextLayout indented(Display display) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
        layout.setText(PARAGRAPH);
        layout.setWidth(WIDE_BOX);
        layout.setIndent(INDENT);
        layout.setWrapIndent(WRAP_INDENT);
        layout.setSpacing(SPACING);
        return layout;
    }

    static TextLayout tabs(Display display) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
        layout.setText(TABS);
        layout.setTabs(TAB_STOPS);
        return layout;
    }

    static TextLayout breaks(Display display) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
        layout.setText(BREAKS);
        return layout;
    }

    /**
     * A caption in 8 points ; returns its height.
     */
    private static int caption(GC gc, String text, int x, int y) {
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        gc.setForeground(SwtKit.color(MUTED));
        gc.drawString(text, x, y, true);
        return gc.getFontMetrics().getHeight();
    }

    /**
     * The frame of a layout : its left and right edges.
     */
    private static void frame(GC gc, int x, int y, int width, int height) {
        gc.setForeground(SwtKit.color(FRAME));
        gc.drawLine(x - 1, y, x - 1, y + height);
        gc.drawLine(x + width, y, x + width, y + height);
    }

    /**
     * The largest height of {@code layouts}.
     */
    private static int height(List<TextLayout> layouts) {
        return layouts.stream().mapToInt(l -> l.getBounds().height).max().orElse(0);
    }

    private static void dispose(List<TextLayout> layouts) {
        layouts.forEach(TextLayout::dispose);
    }

    private static int paintLayouts(GC gc) {
        Display display = UiStages.display();
        String[] captions = { "SWT.LEFT", "SWT.CENTER", "SWT.RIGHT", "setJustify(true)" };
        List<TextLayout> paragraphs = new ArrayList<>();
        try {
            for (int i = 0; i < ALIGNMENTS.length; i++) {
                paragraphs.add(paragraph(display, ALIGNMENTS[i], i == 3));
            }
            int top = 0;
            int height = height(paragraphs);
            for (int i = 0; i < ALIGNMENTS.length; i++) {
                int x = 2 + i * 250;
                top = caption(gc, captions[i] + ", width " + BOX, x, 0) + 2;
                frame(gc, x, top, BOX, height + 4);
                gc.setForeground(SwtKit.color(INK));
                paragraphs.get(i).draw(gc, x, top + 2);
            }
            return paintSecondRow(gc, top + height + 16);
        } finally {
            dispose(paragraphs);
        }
    }

    /**
     * Indents and spacing, tab stops, line breaks ; returns the bottom of the drawing.
     */
    private static int paintSecondRow(GC gc, int y) {
        Display display = UiStages.display();
        List<TextLayout> layouts = List.of(indented(display), tabs(display), breaks(display));
        try {
            int top = y + caption(gc, "indent " + INDENT + ", wrap indent " + WRAP_INDENT + ", spacing " + SPACING
                    + ", width " + WIDE_BOX, 2, y) + 2;
            int x = 340;
            caption(gc, "setTabs(" + TAB_STOPS[0] + ", " + TAB_STOPS[1] + ") : tab stops dotted", x, y);
            int x2 = 680;
            caption(gc, "line breaks \\n, \\r\\n, \\r : line offsets", x2, y);
            int height = height(layouts);
            frame(gc, 2, top, WIDE_BOX, height + 4);
            gc.setLineStyle(SWT.LINE_DOT);
            gc.setForeground(SwtKit.color(RULE));
            for (int stop : TAB_STOPS) {
                gc.drawLine(x + stop, top, x + stop, top + height + 4);
            }
            gc.setLineStyle(SWT.LINE_SOLID);
            gc.setForeground(SwtKit.color(INK));
            layouts.get(0).draw(gc, 2, top + 2);
            layouts.get(1).draw(gc, x, top + 2);
            TextLayout breaks = layouts.get(2);
            gc.setFont(SwtKit.font(SWT.NORMAL, 8));
            int offsetsWidth = gc.stringExtent("00").x + 8;
            gc.setForeground(SwtKit.color(INK));
            breaks.draw(gc, x2 + offsetsWidth, top + 2);
            gc.setForeground(SwtKit.color(BLUE));
            int[] offsets = breaks.getLineOffsets();
            for (int line = 0; line < breaks.getLineCount(); line++) {
                gc.drawString(String.valueOf(offsets[line]), x2, top + 2 + breaks.getLineBounds(line).y, true);
            }
            return top + height + 6;
        } finally {
            dispose(layouts);
        }
    }

    // ------------------------------------------------------------------------------------------------------- bidi

    static TextLayout bidi(Display display, String text, int orientation, int width) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, 11));
        layout.setOrientation(orientation);
        layout.setText(text);
        layout.setWidth(width);
        return layout;
    }

    /**
     * The offsets of the segments of {@link #PATH} : before and after every slash.
     */
    static int[] pathSegments() {
        List<Integer> segments = new ArrayList<>();
        for (int i = 0; i < PATH.length(); i++) {
            if (PATH.charAt(i) == '/') {
                segments.add(i);
                segments.add(i + 1);
            }
        }
        return segments.stream().mapToInt(Integer::intValue).toArray();
    }

    static TextLayout path(Display display, boolean segmented) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, 11));
        layout.setText(PATH);
        if (segmented) {
            int[] segments = pathSegments();
            char[] chars = new char[segments.length];
            Arrays.fill(chars, LRM);
            layout.setSegments(segments);
            layout.setSegmentsChars(chars);
        }
        return layout;
    }

    static TextLayout carets(Display display) {
        TextLayout layout = new TextLayout(display);
        layout.setFont(SwtKit.font(SWT.NORMAL, 14));
        layout.setText(CARETS);
        return layout;
    }

    /**
     * The offsets that {@code getNextOffset(offset, movement)} stops at, from 0 to the length of the text.
     */
    static List<Integer> stops(TextLayout layout, int movement) {
        List<Integer> stops = new ArrayList<>();
        int length = layout.getText().length();
        int offset = 0;
        stops.add(offset);
        while (offset < length) {
            int next = layout.getNextOffset(offset, movement);
            if (next <= offset) {
                break;
            }
            offset = next;
            stops.add(offset);
        }
        return stops;
    }

    /**
     * Draws {@code layout}, of the RIGHT_TO_LEFT orientation, in the {@code width} wide area at ({@code x},
     * {@code y}) : through an image drawn with a mirrored {@code GC} ({@code new GC(image, SWT.RIGHT_TO_LEFT)}, the
     * coordinates of a right-to-left widget), where SWT draws the right-to-left layouts. In a left-to-right
     * {@code GC}, an advanced (GDI+) one mirrors their glyphs on Windows.
     */
    private static void drawRightToLeft(GC gc, TextLayout layout, int x, int y, int width) {
        int height = Math.max(1, layout.getBounds().height);
        Image image = new Image(gc.getDevice(), width, height);
        try {
            GC mirrored = new GC(image, SWT.RIGHT_TO_LEFT);
            try {
                mirrored.setBackground(SwtKit.color(SwtKit.WHITE));
                mirrored.fillRectangle(0, 0, width, height);
                mirrored.setAntialias(gc.getAntialias());
                mirrored.setTextAntialias(gc.getTextAntialias());
                mirrored.setForeground(SwtKit.color(INK));
                layout.draw(mirrored, 0, 0);
            } finally {
                mirrored.dispose();
            }
            gc.drawImage(image, x, y);
        } finally {
            image.dispose();
        }
    }

    private static int paintBidi(GC gc) {
        Display display = UiStages.display();
        // an advanced GC : GDI+ on Windows (TextLayout draws with DrawDriverString)
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        int width = 480;
        String[][] captions = { { "MIXED, setOrientation(SWT.LEFT_TO_RIGHT)",
                "MIXED, setOrientation(SWT.RIGHT_TO_LEFT), mirrored GC" },
                { "Arabic, RIGHT_TO_LEFT, mirrored GC", "Hebrew, RIGHT_TO_LEFT, mirrored GC" } };
        String[][] texts = { { MIXED, MIXED }, { ARABIC, HEBREW } };
        int[][] orientations = { { SWT.LEFT_TO_RIGHT, SWT.RIGHT_TO_LEFT }, { SWT.RIGHT_TO_LEFT, SWT.RIGHT_TO_LEFT } };
        int y = 0;
        for (int row = 0; row < captions.length; row++) {
            List<TextLayout> layouts = List.of(bidi(display, texts[row][0], orientations[row][0], width),
                    bidi(display, texts[row][1], orientations[row][1], width));
            try {
                int height = height(layouts);
                int top = y;
                for (int column = 0; column < 2; column++) {
                    int x = 2 + column * 510;
                    top = y + caption(gc, captions[row][column], x, y) + 2;
                    frame(gc, x, top, width, height + 4);
                    TextLayout layout = layouts.get(column);
                    if (layout.getOrientation() == SWT.RIGHT_TO_LEFT) {
                        drawRightToLeft(gc, layout, x, top + 2, width);
                    } else {
                        gc.setForeground(SwtKit.color(INK));
                        layout.draw(gc, x, top + 2);
                    }
                }
                y = top + height + 14;
            } finally {
                dispose(layouts);
            }
        }
        List<TextLayout> paths = List.of(path(display, false), path(display, true));
        try {
            int top = y;
            for (int column = 0; column < 2; column++) {
                int x = 2 + column * 510;
                top = y + caption(gc, column == 0 ? "a path of Hebrew names, no segments : one right-to-left run"
                        : "setSegments, setSegmentsChars(LRM) around the slashes", x, y) + 2;
                gc.setForeground(SwtKit.color(INK));
                paths.get(column).draw(gc, x, top + 2);
            }
            y = top + height(paths) + 14;
        } finally {
            dispose(paths);
        }
        y += caption(gc, "getNextOffset : MOVEMENT_CLUSTER stops (gray, below), MOVEMENT_WORD_START stops (blue,"
                + " above)", 2, y);
        TextLayout layout = carets(display);
        try {
            int x = 12;
            int top = y + 10;
            gc.setForeground(SwtKit.color(INK));
            layout.draw(gc, x, top);
            Rectangle bounds = layout.getBounds();
            gc.setForeground(SwtKit.color(0x78909C));
            for (int offset : stops(layout, SWT.MOVEMENT_CLUSTER)) {
                int cx = x + layout.getLocation(offset, false).x;
                gc.drawLine(cx, top + bounds.height + 1, cx, top + bounds.height + 7);
            }
            gc.setForeground(SwtKit.color(BLUE));
            for (int offset : stops(layout, SWT.MOVEMENT_WORD_START)) {
                int cx = x + layout.getLocation(offset, false).x;
                gc.drawLine(cx, top - 8, cx, top - 1);
            }
            return top + bounds.height + 9;
        } finally {
            layout.dispose();
        }
    }

    // ----------------------------------------------------------------------------------------------------- checks

    /**
     * {@code action} with a {@code GC} on a scratch image, disposed after.
     */
    private static <T> T measure(Display display, Function<GC, T> action) {
        Image image = new Image(display, 1, 1);
        try {
            GC gc = new GC(image);
            try {
                return action.apply(gc);
            } finally {
                gc.dispose();
            }
        } finally {
            image.dispose();
        }
    }

    /**
     * {@code action} with {@code font}, disposed after.
     */
    private static <T> T withFont(Font font, Function<Font, T> action) {
        try {
            return action.apply(font);
        } finally {
            font.dispose();
        }
    }

    /**
     * {@code action} with {@code layout}, disposed after.
     */
    private static <T> T withLayout(TextLayout layout, Function<TextLayout, T> action) {
        try {
            return action.apply(layout);
        } finally {
            layout.dispose();
        }
    }

    private static String styles(FontData data) {
        String font = SwtChecks.font(data);
        return font.substring(data.getName().length() + 1);
    }

    private static String metrics(FontMetrics m) {
        return m.getAscent() + ", " + m.getDescent() + ", " + m.getLeading() + ", " + m.getHeight() + ", "
                + SwtChecks.num(m.getAverageCharacterWidth(), 2);
    }

    private static String join(int[] values) {
        return Arrays.stream(values).mapToObj(String::valueOf).collect(Collectors.joining(" "));
    }

    private static String join(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(" "));
    }

    private static List<Check> fontChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        FontData system = display.getSystemFont().getFontData()[0];
        checks.add(Check.info("Display.getSystemFont()", SwtChecks.font(system)));
        checks.add(SwtChecks.info("FontData.toString() of the system font", system::toString));
        checks.add(SwtChecks.expect("new FontData(FontData.toString()) equals the FontData", true,
                () -> new FontData(system.toString()).equals(system)));
        checks.add(SwtChecks.expect("new Font(display, family, 12, BOLD | ITALIC).getFontData() : height, style",
                "12 bold italic", () -> withFont(new Font(display, system.getName(), 12, SWT.BOLD | SWT.ITALIC),
                        font -> styles(font.getFontData()[0]))));
        checks.add(SwtChecks.expect("new Font(display, FontData[] { 14 pt italic }).getFontData() : height, style",
                "14 italic", () -> withFont(new Font(display, new FontData[] { new FontData(system.getName(), 14,
                        SWT.ITALIC) }), font -> styles(font.getFontData()[0]))));
        checks.add(SwtChecks.info("new Font(display, \"" + MISSING_FONT + "\", 11, NORMAL) : FontData, height",
                () -> withFont(new Font(display, MISSING_FONT, 11, SWT.NORMAL), font -> SwtChecks.font(
                        font.getFontData()[0]) + ", " + measure(display, gc -> {
                            gc.setFont(font);
                            return gc.getFontMetrics().getHeight();
                        }))));
        checks.add(SwtChecks.info("Display.getFontList(null, true) : scalable fonts",
                () -> display.getFontList(null, true).length));
        checks.add(SwtChecks.info("Display.getFontList(null, false) : non-scalable fonts",
                () -> display.getFontList(null, false).length));
        checks.add(SwtChecks.info("Display.getFontList(system family, true) : fonts, styles", () -> {
            FontData[] list = display.getFontList(system.getName(), true);
            TreeSet<String> styles = new TreeSet<>();
            for (FontData data : list) {
                styles.add(styleName(data.getStyle() & (SWT.BOLD | SWT.ITALIC)));
            }
            return list.length + ", " + String.join(" ", styles);
        }));
        checks.add(SwtChecks.info("FontMetrics of the system font : ascent, descent, leading, height, average width",
                () -> measure(display, gc -> {
                    gc.setFont(display.getSystemFont());
                    return metrics(gc.getFontMetrics());
                })));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "FontMetrics : height = ascent + descent + leading", true, () -> measure(display, gc -> {
                    gc.setFont(display.getSystemFont());
                    FontMetrics m = gc.getFontMetrics();
                    return m.getHeight() == m.getAscent() + m.getDescent() + m.getLeading();
                }))));
        int[] heights = measure(display, gc -> Arrays.stream(SIZES).map(points -> {
            gc.setFont(SwtKit.font(SWT.NORMAL, points));
            return gc.getFontMetrics().getHeight();
        }).toArray());
        checks.add(Check.info("FontMetrics height at " + join(SIZES) + " points", join(heights)));
        checks.add(SwtChecks.expect("the height grows with the point size", true,
                () -> IntStream.range(1, heights.length).allMatch(i -> heights[i] > heights[i - 1])));
        checks.add(SwtChecks.expect("GC.getCharWidth : W wider than i", true, () -> measure(display, gc -> {
            gc.setFont(display.getSystemFont());
            return gc.getCharWidth('W') > gc.getCharWidth('i');
        })));
        checks.add(SwtChecks.info("stringExtent, textExtent with TAB, MNEMONIC, DELIMITER, all four",
                () -> measure(display, gc -> {
                    gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
                    List<String> sizes = new ArrayList<>();
                    sizes.add(SwtChecks.size(gc.stringExtent(FLAGS_SAMPLE)));
                    for (int flags : FLAGS) {
                        sizes.add(SwtChecks.size(gc.textExtent(FLAGS_SAMPLE, flags)));
                    }
                    return String.join(", ", sizes);
                })));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "DRAW_DELIMITER : two lines, twice the height of one", true, () -> measure(display, gc -> {
                    gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
                    return gc.textExtent(FLAGS_SAMPLE, SWT.DRAW_DELIMITER).y == 2 * gc.textExtent("x", 0).y;
                }))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "DRAW_MNEMONIC : the & takes no width", true, () -> measure(display, gc -> {
                    gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
                    return gc.textExtent("&Mnemonic", SWT.DRAW_MNEMONIC).x == gc.textExtent("Mnemonic", 0).x;
                }))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "DRAW_TAB : the tab expands to a tab stop", true, () -> measure(display, gc -> {
                    gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
                    return gc.textExtent(FLAGS_SAMPLE, SWT.DRAW_TAB).x > gc.textExtent(FLAGS_SAMPLE, 0).x;
                }))));
        checks.add(SwtChecks.expect("DRAW_TRANSPARENT does not change the extent", true,
                () -> measure(display, gc -> {
                    gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
                    return gc.textExtent(FLAGS_SAMPLE, ALL_FLAGS)
                            .equals(gc.textExtent(FLAGS_SAMPLE, ALL_FLAGS & ~SWT.DRAW_TRANSPARENT));
                })));
        checks.add(SwtChecks.expect("GC.getAdvanced() after setTextAntialias(SWT.ON)", true,
                () -> measure(display, gc -> {
                    gc.setTextAntialias(SWT.ON);
                    return gc.getAdvanced();
                })));
        checks.add(SwtChecks.info("advanced GC : stringExtent, textExtent with all four flags",
                () -> measure(display, gc -> {
                    gc.setTextAntialias(SWT.ON);
                    gc.setFont(SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS));
                    return SwtChecks.size(gc.stringExtent(FLAGS_SAMPLE)) + ", "
                            + SwtChecks.size(gc.textExtent(FLAGS_SAMPLE, ALL_FLAGS));
                })));
        return checks;
    }

    private static List<Check> layoutChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("line breaks \\n, \\r\\n, \\r : getLineCount, getLineOffsets", "4, 0 11 24 35 46",
                () -> withLayout(breaks(display), l -> l.getLineCount() + ", " + join(l.getLineOffsets()))));
        checks.add(SwtChecks.expect("getLineIndex of the line starts", "0 1 2 3", () -> withLayout(breaks(display),
                l -> Arrays.stream(l.getLineOffsets()).limit(l.getLineCount()).map(l::getLineIndex)
                        .mapToObj(String::valueOf).collect(Collectors.joining(" ")))));
        checks.add(SwtChecks.info("wrapped paragraph (width " + BOX + ") : lines, offsets", () -> withLayout(
                paragraph(display, SWT.LEFT, false), l -> l.getLineCount() + ", " + join(l.getLineOffsets()))));
        checks.add(SwtChecks.info("line bounds (LEFT)", () -> withLayout(paragraph(display, SWT.LEFT, false),
                l -> IntStream.range(0, l.getLineCount()).mapToObj(i -> SwtChecks.rect(l.getLineBounds(i)))
                        .collect(Collectors.joining(" ; ")))));
        checks.add(SwtChecks.expect("LEFT, CENTER, RIGHT and justify break at the same offsets", true, () -> {
            List<String> offsets = new ArrayList<>();
            for (int i = 0; i < ALIGNMENTS.length; i++) {
                int index = i;
                offsets.add(withLayout(paragraph(display, ALIGNMENTS[i], index == 3), l -> join(l.getLineOffsets())));
            }
            return offsets.stream().distinct().count() == 1;
        }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("RIGHT : every line ends at the width", true,
                () -> withLayout(paragraph(display, SWT.RIGHT, false), l -> IntStream.range(0, l.getLineCount())
                        .allMatch(i -> {
                            Rectangle b = l.getLineBounds(i);
                            return b.x + b.width == BOX;
                        })))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "CENTER : the margins of every line differ by 1 at most", true, () -> withLayout(
                        paragraph(display, SWT.CENTER, false), l -> IntStream.range(0, l.getLineCount()).allMatch(i -> {
                            Rectangle b = l.getLineBounds(i);
                            return Math.abs(b.x - (BOX - b.x - b.width)) <= 1;
                        })))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "justify : every line but the last spans the width", true, () -> withLayout(
                        paragraph(display, SWT.LEFT, true), l -> IntStream.range(0, l.getLineCount() - 1)
                                .allMatch(i -> l.getLineBounds(i).width == BOX)))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "setIndent / setWrapIndent : x of lines 0 and 1", INDENT + ", " + WRAP_INDENT,
                () -> withLayout(indented(display), l -> l.getLineBounds(0).x + ", " + l.getLineBounds(1).x))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "setSpacing : top of line 1 - bottom of line 0", SPACING, () -> withLayout(indented(display), l -> {
                    Rectangle first = l.getLineBounds(0);
                    return l.getLineBounds(1).y - (first.y + first.height);
                }))));
        checks.add(SwtChecks.expect("getAlignment, getJustify, getIndent, getWrapIndent, getSpacing, getWidth",
                "RIGHT, true, 16, 32, 6, 310", () -> withLayout(indented(display), l -> {
                    l.setAlignment(SWT.RIGHT);
                    l.setJustify(true);
                    return (l.getAlignment() == SWT.RIGHT ? "RIGHT" : String.valueOf(l.getAlignment())) + ", "
                            + l.getJustify() + ", " + l.getIndent() + ", " + l.getWrapIndent() + ", " + l.getSpacing()
                            + ", " + l.getWidth();
                })));
        checks.add(SwtChecks.expect("getTabs", "110 180", () -> withLayout(tabs(display), l -> join(l.getTabs()))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "tab stops : x of the second and third columns", TAB_STOPS[0] + ", " + TAB_STOPS[1],
                () -> withLayout(tabs(display), l -> l.getLocation(TABS.indexOf("12 KB"), false).x + ", "
                        + l.getLocation(TABS.indexOf("PNG image"), false).x))));
        Styled styled = styled();
        checks.add(SwtChecks.expect("setStyle : getStyles and getRanges are the styled runs", true,
                () -> withLayout(styled.layout(display), l -> {
                    int[] expected = styled.runs().stream().flatMapToInt(r -> IntStream.of(r.start(), r.end()))
                            .toArray();
                    return l.getStyles().length == styled.runs().size() && Arrays.equals(l.getRanges(), expected);
                })));
        checks.add(SwtChecks.expect("getStyle at a styled offset is the style set", true,
                () -> withLayout(styled.layout(display), l -> {
                    Run run = styled.runs().get(3);
                    return l.getStyle(run.start()).equals(run.style()) && l.getStyle(0) == null;
                })));
        checks.add(SwtChecks.info("styled layout : getLineCount, getBounds",
                () -> withLayout(styled.layout(display),
                        l -> l.getLineCount() + ", " + SwtChecks.rect(l.getBounds()))));
        checks.add(SwtChecks.info("styled layout : getLineMetrics(0) ascent, descent, leading, height",
                () -> withLayout(styled.layout(display), l -> {
                    FontMetrics m = l.getLineMetrics(0);
                    return m.getAscent() + ", " + m.getDescent() + ", " + m.getLeading() + ", " + m.getHeight();
                })));
        // macOS : Core Text places the objects at a fractional x (the advances of the system font at 10 points before
        // them), and getBounds rounds the left edge down and the right edge up (TextLayout.getBounds, "the smallest
        // rectangle that encompasses all characters") : a 40 point object spans 41 points, at every display scale
        int objectBounds = SwtMode.pick(OBJECT_WIDTH + 1, OBJECT_WIDTH, OBJECT_WIDTH);
        checks.add(SwtChecks.expect("GlyphMetrics objects : width of getBounds(offset, offset)",
                objectBounds + " " + objectBounds, () -> withLayout(styled.layout(display),
                        l -> styled.objects().stream().map(o -> String.valueOf(l.getBounds(o, o).width))
                                .collect(Collectors.joining(" ")))));
        checks.add(SwtChecks.expect("bidi levels, LEFT_TO_RIGHT : \"" + LEVELS + "\"", "00001111222",
                () -> withLayout(bidi(display, LEVELS, SWT.LEFT_TO_RIGHT, -1), SwtTextFontsPage::levels)));
        checks.add(SwtChecks.expect("bidi levels, RIGHT_TO_LEFT", "22211111222",
                () -> withLayout(bidi(display, LEVELS, SWT.RIGHT_TO_LEFT, -1), SwtTextFontsPage::levels)));
        checks.add(SwtChecks.expect("getOrientation after setOrientation(RIGHT_TO_LEFT)", true,
                () -> withLayout(bidi(display, LEVELS, SWT.RIGHT_TO_LEFT, -1),
                        l -> l.getOrientation() == SWT.RIGHT_TO_LEFT)));
        checks.add(SwtChecks.info("MIXED, LEFT_TO_RIGHT / RIGHT_TO_LEFT : line bounds", () -> withLayout(
                bidi(display, MIXED, SWT.LEFT_TO_RIGHT, 480), l -> SwtChecks.rect(l.getLineBounds(0))) + " / "
                + withLayout(bidi(display, MIXED, SWT.RIGHT_TO_LEFT, 480), l -> SwtChecks.rect(l.getLineBounds(0)))));
        checks.add(SwtChecks.expect("segments : getSegments, getText without the LRM", "6 7 13 14, true",
                () -> withLayout(path(display, true), l -> join(l.getSegments()) + ", " + l.getText().equals(PATH))));
        checks.add(SwtChecks.expect("no segments : the three names from right to left", true,
                () -> withLayout(path(display, false), l -> nameOrder(l) < 0)));
        checks.add(SwtChecks.expect("segments (LRM) : the three names from left to right", true,
                () -> withLayout(path(display, true), l -> nameOrder(l) > 0)));
        int acute = CARETS.indexOf('́');
        // macOS : the TextLayout of Cocoa steps over the surrogate pairs only ("TODO cluster" in _getOffset), so the
        // caret stops between the e and the combining acute : a Cocoa SWT bug against the Javadoc of
        // SWT.MOVEMENT_CLUSTER ("a caret offset can not be placed in the middle of a cluster")
        checks.add(SwtChecks.expect("MOVEMENT_CLUSTER : e + combining acute is one cluster",
                SwtMode.pick(acute, acute + 1, acute + 1),
                () -> withLayout(carets(display), l -> l.getNextOffset(acute - 1, SWT.MOVEMENT_CLUSTER))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("MOVEMENT_CLUSTER stops",
                "0 1 2 3 5 6 7 8 9 10 11 12 13 16 17 19 20 21 22 23 24 25 26 27 28 29 30",
                () -> withLayout(carets(display), l -> join(stops(l, SWT.MOVEMENT_CLUSTER))))));
        // Windows : Uniscribe breaks the words of the complex scripts only ; SWT stops at every change between letters
        // and non-letters elsewhere, combining marks included
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("MOVEMENT_WORD_START stops",
                "0 4 6 11 13 14 15 16 18 19 21 25 30",
                () -> withLayout(carets(display), l -> join(stops(l, SWT.MOVEMENT_WORD_START))))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("MOVEMENT_WORD_END stops",
                "0 4 11 14 18 20 24 30",
                () -> withLayout(carets(display), l -> join(stops(l, SWT.MOVEMENT_WORD_END))))));
        checks.add(SwtChecks.info("getOffset at the middle of line 1 of the paragraph : offset, trailing",
                () -> withLayout(paragraph(display, SWT.LEFT, false), l -> {
                    Rectangle line = l.getLineBounds(1);
                    int[] trailing = new int[1];
                    int offset = l.getOffset(line.x + line.width / 2, line.y + line.height / 2, trailing);
                    return offset + ", " + trailing[0];
                })));
        return checks;
    }

    /**
     * The bidi level of every character of the text of {@code layout}.
     */
    private static String levels(TextLayout layout) {
        return IntStream.range(0, layout.getText().length()).mapToObj(i -> String.valueOf(layout.getLevel(i)))
                .collect(Collectors.joining());
    }

    /**
     * {@code 1} when the names of {@link #PATH} are drawn in logical order from left to right, {@code -1} from right to
     * left, {@code 0} otherwise : compares the x of the first character of each name.
     */
    private static int nameOrder(TextLayout layout) {
        int[] starts = { 0, PATH.indexOf('/') + 1, PATH.lastIndexOf('/') + 1 };
        int[] xs = Arrays.stream(starts).map(offset -> layout.getLocation(offset, false).x).toArray();
        if (xs[0] < xs[1] && xs[1] < xs[2]) {
            return 1;
        }
        return xs[0] > xs[1] && xs[1] > xs[2] ? -1 : 0;
    }
}
