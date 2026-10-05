package io.quarkiverse.desktop.showcase.swt.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;

/**
 * Building blocks of the SWT pages : text, columns and rows, images, painted areas, fonts and colors, and the content
 * of a page that failed to build. The SWT counterpart of {@code core/Ui}. Call them on the user interface thread.
 * <p>
 * The page frame paints a white background that its children inherit ({@code SWT.INHERIT_DEFAULT}) : labels,
 * composites, buttons and groups are drawn on white, the controls with a background of their own (text fields, lists,
 * tables) keep it. Sizes are in points (pixels at 100 %).
 * <p>
 * Fonts and colors are created when a page is built, never in a static initializer (Quarkus initializes the
 * application classes at build time, SWT loads its native library in static initializers). The fonts of
 * {@link #font} are shared and disposed with the {@code Display} : never dispose them.
 */
public final class SwtKit {

    /** The usual width of wrapped text. */
    public static final int TEXT_WIDTH = 1000;
    /** Font height of the body text, in points. */
    public static final int TEXT_POINTS = 9;
    /** Text color. */
    public static final int TEXT_COLOR = 0x222222;
    /** Secondary text color. */
    public static final int MUTED_COLOR = 0x555555;
    /** Error color. */
    public static final int ERROR_COLOR = 0xC62828;
    /** Background of the pages. */
    public static final int WHITE = 0xFFFFFF;

    /** The key of the shared fonts in the data of the {@code Display}. */
    private static final String FONTS = "io.quarkiverse.desktop.showcase.fonts";

    private SwtKit() {
    }

    // ------------------------------------------------------------------------------------------- colors and fonts

    /**
     * The color {@code 0xRRGGBB} (an SWT color needs no disposal).
     */
    public static Color color(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /**
     * The font of the system font family ({@code Display.getSystemFont()}) with {@code style} ({@code SWT.NORMAL},
     * {@code SWT.BOLD}, {@code SWT.ITALIC}) and {@code points} : shared, disposed with the {@code Display}.
     */
    public static Font font(int style, int points) {
        Display display = UiStages.display();
        return font(display.getSystemFont().getFontData()[0].getName(), style, points);
    }

    /**
     * The monospaced font of the platform (Menlo, Consolas, Monospace) : shared, disposed with the {@code Display}.
     */
    public static Font monospace(int points) {
        return font(SwtMode.pick("Menlo", "Consolas", "Monospace"), SWT.NORMAL, points);
    }

    /**
     * The font {@code name} (the default font of the platform when it is not installed) with {@code style} and
     * {@code points} : shared, disposed with the {@code Display}.
     */
    public static Font font(String name, int style, int points) {
        Display display = UiStages.display();
        @SuppressWarnings("unchecked")
        Map<String, Font> fonts = (Map<String, Font>) display.getData(FONTS);
        if (fonts == null) {
            Map<String, Font> created = new HashMap<>();
            display.setData(FONTS, created);
            display.disposeExec(() -> created.values().forEach(Font::dispose));
            fonts = created;
        }
        return fonts.computeIfAbsent(name + "/" + style + "/" + points,
                key -> new Font(display, new FontData(name, points, style)));
    }

    // ------------------------------------------------------------------------------------------------------ layout

    /**
     * The content of a page : a column of {@code gap} points, on a white background.
     */
    public static Composite page(Composite parent, int gap) {
        Composite page = column(parent, gap);
        page.setBackground(color(WHITE));
        page.setBackgroundMode(SWT.INHERIT_DEFAULT);
        return page;
    }

    /**
     * A composite stacking its children vertically at their preferred size, left aligned ({@code GridLayout} of one
     * column, no margin, {@code gap} points between the children).
     */
    public static Composite column(Composite parent, int gap) {
        Composite column = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = gap;
        column.setLayout(layout);
        return column;
    }

    /**
     * A composite placing its children side by side at their preferred size, top aligned ({@code RowLayout}, no margin,
     * no wrap, {@code gap} points between the children).
     */
    public static Composite row(Composite parent, int gap) {
        Composite row = new Composite(parent, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginLeft = 0;
        layout.marginTop = 0;
        layout.marginRight = 0;
        layout.marginBottom = 0;
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.spacing = gap;
        layout.wrap = false;
        layout.pack = true;
        row.setLayout(layout);
        return row;
    }

    /**
     * Gives {@code control} a width and a height ({@code SWT.DEFAULT} : its preferred one) through the layout data of
     * the layout of its parent ({@code GridLayout}, {@code RowLayout}, {@code FormLayout}), or its size otherwise :
     * e.g. the wrap width of a label.
     */
    public static <C extends Control> C size(C control, int width, int height) {
        Layout layout = control.getParent().getLayout();
        if (layout instanceof GridLayout) {
            GridData data = control.getLayoutData() instanceof GridData d ? d : new GridData();
            data.widthHint = width;
            data.heightHint = height;
            control.setLayoutData(data);
        } else if (layout instanceof RowLayout) {
            control.setLayoutData(new RowData(width, height));
        } else if (layout instanceof FormLayout) {
            FormData data = control.getLayoutData() instanceof FormData d ? d : new FormData();
            data.width = width;
            data.height = height;
            control.setLayoutData(data);
        } else {
            control.setSize(control.computeSize(width, height));
        }
        return control;
    }

    // --------------------------------------------------------------------------------------------------------- text

    /**
     * A label on one line (or on the lines of {@code text}).
     */
    public static Label text(Composite parent, String text) {
        return text(parent, text, font(SWT.NORMAL, TEXT_POINTS), TEXT_COLOR, 0);
    }

    /**
     * A label wrapped at {@code wrapWidth} points.
     */
    public static Label text(Composite parent, String text, int wrapWidth) {
        return text(parent, text, font(SWT.NORMAL, TEXT_POINTS), TEXT_COLOR, wrapWidth);
    }

    /**
     * A label with {@code font} and the color {@code 0xRRGGBB}, wrapped at {@code wrapWidth} points ({@code 0} : not
     * wrapped).
     */
    public static Label text(Composite parent, String text, Font font, int rgb, int wrapWidth) {
        Label label = new Label(parent, wrapWidth > 0 ? SWT.WRAP : SWT.NONE);
        label.setFont(font);
        label.setForeground(color(rgb));
        label.setText(Objects.requireNonNullElse(text, ""));
        if (wrapWidth > 0) {
            size(label, wrapWidth, SWT.DEFAULT);
        }
        return label;
    }

    /**
     * Page heading (bold, 16 points).
     */
    public static Label heading(Composite parent, String text) {
        return text(parent, text, font(SWT.BOLD, 16), TEXT_COLOR, 0);
    }

    /**
     * Section title (bold, 11 points).
     */
    public static Label title(Composite parent, String text) {
        return text(parent, text, font(SWT.BOLD, 11), TEXT_COLOR, 0);
    }

    /**
     * Small secondary text (8 points), e.g. the caption of an image.
     */
    public static Label caption(Composite parent, String text) {
        return text(parent, text, font(SWT.NORMAL, 8), MUTED_COLOR, 0);
    }

    // -------------------------------------------------------------------------------------------- images and paint

    /**
     * Shows {@code data} at its size : a label with an image disposed with the label.
     */
    public static Label image(Composite parent, ImageData data) {
        Label label = new Label(parent, SWT.NONE);
        Image image = new Image(parent.getDisplay(), data);
        label.setImage(image);
        label.addListener(SWT.Dispose, event -> image.dispose());
        return label;
    }

    /**
     * A {@code width x height} image drawn offscreen by {@code painter} on a white background
     * ({@link SwtSnapshots#offscreen}) : drawn once, independently of the screen. Prefer it to {@link #canvas} for the
     * {@code GC} drawings.
     */
    public static Label painted(Composite parent, int width, int height, Consumer<GC> painter) {
        return image(parent, SwtSnapshots.offscreen(width, height, painter));
    }

    /**
     * A {@code width x height} canvas painted by {@code painter} on screen, every time it is painted (and when it is
     * rendered with {@code Control.print}, {@link SwtSnapshots#render}), on a white background.
     */
    public static Canvas canvas(Composite parent, int width, int height, Consumer<GC> painter) {
        Canvas canvas = new Canvas(parent, SWT.NONE);
        canvas.setBackground(color(WHITE));
        canvas.addListener(SWT.Paint, event -> painter.accept(event.gc));
        size(canvas, width, height);
        return canvas;
    }

    /**
     * The content shown instead of a page whose {@code build} failed : the exception and its stack trace.
     */
    public static Composite error(Composite parent, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        Composite content = page(parent, 8);
        text(content, "This page failed to build: " + SwtChecks.describe(error), font(SWT.BOLD, 10), ERROR_COLOR,
                TEXT_WIDTH);
        text(content, trace.toString().replace("\t", "    ").replace("\r", ""), monospace(8), TEXT_COLOR, TEXT_WIDTH);
        return content;
    }

    // ---------------------------------------------------------------------------------------------------- resources

    /**
     * The bytes of a classpath resource, {@code path} being absolute (e.g. {@code /showcase/swt/images/sample.png} :
     * the SWT assets live under {@code src/main/resources/showcase/swt/}). Its URL is {@code jar:} or {@code file:} on
     * the JVM and {@code resource:} in a native executable : never show or check a resource URL.
     */
    public static byte[] resourceBytes(String path) {
        try (InputStream in = SwtKit.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Resource not found: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
