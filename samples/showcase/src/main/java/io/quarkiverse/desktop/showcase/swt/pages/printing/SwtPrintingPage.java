package io.quarkiverse.desktop.showcase.swt.pages.printing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;

/**
 * Printing with SWT, without printing : the printers of the system ({@link Printer#getPrinterList()} and
 * {@link Printer#getDefaultPrinterData()}, informational : only their count, their drivers and the fields of their
 * {@link PrinterData} are shown, never their names), the fields and defaults of {@link PrinterData}, and a print
 * preview of a two page document : the drawing code a print job would run on the {@code GC} of a {@code Printer}
 * (points scaled by the resolution of the device), run on the {@code GC} of an {@code Image} at the proportions of an
 * A4 sheet, with text, rules, an image, a table, a vector figure and the page margins. The pages are also rendered at
 * one pixel per point as extra snapshots. No {@code Printer} is created, no {@code PrintDialog} is opened, no print
 * job is started.
 * <p>
 * Native code paths. Windows : the printers of the profile ({@code GetProfileString} of the {@code devices} and
 * {@code windows} sections, as SWT reads them), GDI+ for the preview : a {@code Graphics} on the device context of a
 * DIB section, a world transform ({@code Matrix}) for the scale, the text of the {@code GC} drawn by GDI+ under that
 * transform with the fonts of the {@code Display}, dashed pens, arcs, images scaled with interpolation. GTK : the
 * print backends of GTK ({@code gtk_enumerate_printers}, CUPS), Cairo for the drawing. Cocoa : {@code NSPrinter} and
 * Core Graphics.
 * <p>
 * Why it matters for a native executable : the advanced graphics of the {@code GC} (GDI+ on Windows) are other
 * libraries and other JNI functions than the plain {@code GC} drawing of the other pages, loaded on first use, and the
 * printer enumeration runs code (and on GTK, callbacks) that an application reaches only when it prints. The preview
 * depends only on the resolution of the display, which is the same in both runs : every run renders the same pixels.
 */
@Singleton
public class SwtPrintingPage implements SwtPage {

    /** A4, in points (1/72 inch). */
    static final int PAGE_WIDTH = 595;
    static final int PAGE_HEIGHT = 842;
    /** The margins, in points (2 cm). */
    static final int MARGIN = 57;
    /** Preview pixels per point. */
    static final double PREVIEW_SCALE = 0.5;
    static final int PAGES = 2;

    private static final int SHADOW = 6;
    private static final int SHADOW_COLOR = 0xB0BEC5;
    private static final int PAPER = 0xFFFFFF;
    private static final int PAPER_BORDER = 0x90A4AE;
    private static final int MARGIN_COLOR = 0x64B5F6;
    private static final int ACCENT = 0x1A237E;
    private static final int TABLE_HEADER = 0xE8EAF6;
    private static final int TABLE_RULE = 0x9FA8DA;
    private static final int[] BAR_COLORS = { 0x3949AB, 0x00897B, 0xF4511E, 0x8E24AA, 0xFDD835, 0x6D4C41 };
    private static final int[] BAR_VALUES = { 62, 85, 47, 93, 71, 38 };
    private static final int[] PIE_VALUES = { 40, 25, 20, 15 };
    private static final int CHART_WIDTH = 360;
    private static final int CHART_HEIGHT = 180;
    private static final int LEGEND_WIDTH = 330;

    static final String[] PARAGRAPHS_1 = {
            "This document is drawn with a GC, by the code that would print it: a Printer is a Device, and a GC created"
                    + " on a Printer draws on the current page of a print job. Here the same code draws into an Image,"
                    + " at the proportions of an A4 sheet, so the preview needs no printer and starts no print job.",
            "The page is laid out in points (1/72 inch), scaled by the resolution of the device: the display for the"
                    + " preview, the printer for a real job. The dashed rectangle shows the margins of 2 cm; the"
                    + " header, the figure, the table and the footer stay inside them." };
    static final String[] PARAGRAPHS_2 = {
            "The second page continues the document with a vector figure: arcs filled by the GC, scaled by the same"
                    + " transform as the text. A printer of 600 dpi draws this page with about 8 device pixels per"
                    + " point, the preview with half a pixel.",
            "Fonts are given in points, and a GC converts them with the resolution of its device: the layout of the"
                    + " page, the wrapping of these paragraphs included, follows the resolution of the device it is"
                    + " drawn on. A real job would call startJob, startPage, endPage and endJob around this drawing.",
            "Nothing here is sent to a printer: the printers of the system are only listed." };
    static final String[][] TABLE = { { "Quality", "Resolution", "Pixels per point" },
            { "Draft", "150 dpi", "2.08" }, { "Normal", "300 dpi", "4.17" }, { "High", "600 dpi", "8.33" } };

    @Override
    public String id() {
        return "swt-printing";
    }

    @Override
    public String title() {
        return "Printers and a print preview";
    }

    @Override
    public String category() {
        return SwtCategories.PRINTING;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Display display = parent.getDisplay();
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "The printers of the system (informational, only counted) and a print preview: the drawing"
                + " code of a print job, run on the GC of an image at the proportions of an A4 sheet. No Printer is"
                + " created, no print dialog is opened and nothing is printed.", SwtKit.TEXT_WIDTH);
        Composite row = SwtKit.row(page, 24);
        List<ImageData> previews = new ArrayList<>();
        for (int number = 1; number <= PAGES; number++) {
            Composite column = SwtKit.column(row, 4);
            ImageData preview = preview(display, number);
            previews.add(preview);
            SwtKit.image(column, preview);
            SwtKit.caption(column, "page " + number + " of " + PAGES + " (preview, 50 %)");
        }
        Composite legend = SwtKit.column(row, 6);
        SwtKit.title(legend, "The preview");
        SwtKit.text(legend, "Each page is drawn in points under a Transform that scales them to the preview (half a"
                + " pixel per point); the extra snapshots render the same pages at one pixel per point.", LEGEND_WIDTH);
        SwtKit.text(legend, "Dashed blue: the margins. Page 1: a header with a rule, wrapped paragraphs, a chart"
                + " drawn into an Image and scaled into the page, a table. Page 2: paragraphs and a vector figure."
                + " Both: a footer with the page number.", LEGEND_WIDTH);

        Composite tables = SwtKit.row(page, 12);
        ChecksTable.table(tables, "Printers of the system", printerChecks(), 230, 494);
        ChecksTable.table(tables, "PrinterData and the preview", dataChecks(display, previews.getFirst()), 230, 494);
        return page;
    }

    @Override
    public CompletionStage<Map<String, ImageData>> extraSnapshots(Control content) {
        Display display = content.getDisplay();
        Map<String, ImageData> pages = new LinkedHashMap<>();
        for (int number = 1; number <= PAGES; number++) {
            int page = number;
            // one pixel per point : the page as a 72 dpi device would receive it
            pages.put("page-" + number, SwtSnapshots.offscreen(PAGE_WIDTH, PAGE_HEIGHT,
                    gc -> paintPage(gc, display, page, 0, 0, 1.0)));
        }
        return CompletableFuture.completedFuture(pages);
    }

    // ------------------------------------------------------------------------------------------------------ checks

    private static List<Check> printerChecks() {
        List<Check> checks = new ArrayList<>();
        PrinterData[] printers;
        try {
            printers = Printer.getPrinterList();
        } catch (Throwable t) {
            return List.of(Check.fail("Printer.getPrinterList()", SwtChecks.describe(t)));
        }
        PrinterData[] list = printers;
        // the names of the printers stay out of the page (they may name people or places) : counted only
        checks.add(Check.info("Printer.getPrinterList()", list.length + " printer(s)"));
        checks.add(Check.info("drivers of the printers", list.length == 0 ? "none"
                : String.join(" ", new TreeSet<>(Arrays.stream(list).map(p -> String.valueOf(p.driver)).toList()))));
        checks.add(SwtChecks.expect("every PrinterData of the list has a name", true,
                () -> Arrays.stream(list).allMatch(p -> p.name != null && !p.name.isEmpty())));
        PrinterData defaultPrinter;
        try {
            defaultPrinter = Printer.getDefaultPrinterData();
        } catch (Throwable t) {
            checks.add(Check.fail("Printer.getDefaultPrinterData()", SwtChecks.describe(t)));
            return checks;
        }
        PrinterData data = defaultPrinter;
        checks.add(Check.info("Printer.getDefaultPrinterData()", data == null ? "none" : "a printer"));
        checks.add(SwtChecks.expect("the default printer is in the list (or there is none)", true,
                () -> data == null || Arrays.stream(list).anyMatch(p -> Objects.equals(p.name, data.name))));
        if (data != null) {
            checks.add(Check.info("fields of the default PrinterData", fields(data)));
        }
        checks.add(Check.info("print job", "never started : no Printer is created, no PrintDialog is opened"));
        return checks;
    }

    private static List<Check> dataChecks(Display display, ImageData preview) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("new PrinterData() fields",
                "scope ALL_PAGES, pages 1-1, copies 1, collate false, PORTRAIT, duplex DEFAULT, print to file false"
                        + " (null)",
                () -> fields(new PrinterData())));
        checks.add(SwtChecks.expect("new PrinterData(driver, name).toString()",
                "PrinterData {driver = showcase-driver, name = Showcase printer}",
                () -> new PrinterData("showcase-driver", "Showcase printer").toString()));
        checks.add(SwtChecks.expect("PrinterData constants (scope, orientation, duplex)", "0 1 2, 1 2, 0 1 2",
                () -> PrinterData.ALL_PAGES + " " + PrinterData.PAGE_RANGE + " " + PrinterData.SELECTION + ", "
                        + PrinterData.PORTRAIT + " " + PrinterData.LANDSCAPE + ", " + PrinterData.DUPLEX_NONE + " "
                        + PrinterData.DUPLEX_LONG_EDGE + " " + PrinterData.DUPLEX_SHORT_EDGE));
        checks.add(SwtChecks.info("device pixels per point of the preview (Display.getDPI)",
                () -> SwtChecks.num(display.getDPI().y / 72.0, 2)));
        checks.add(SwtChecks.expect("preview size (A4 at 50 %, with its shadow)", "304x427",
                () -> preview.width + "x" + preview.height));
        int pageWidth = previewSize(PAGE_WIDTH);
        int pageHeight = previewSize(PAGE_HEIGHT);
        // solid areas : the paper inside the left margin, the shadow, the header rule, the header row of the table
        checks.add(SwtChecks.expect("preview pixels : paper, shadow, rule, table header",
                SwtChecks.argb(0xFF000000 | PAPER) + " " + SwtChecks.argb(0xFF000000 | SHADOW_COLOR) + " "
                        + SwtChecks.argb(0xFF000000 | ACCENT) + " " + SwtChecks.argb(0xFF000000 | TABLE_HEADER),
                () -> probe(preview, previewSize(20), previewSize(420)) + " "
                        + probe(preview, pageWidth + SHADOW / 2, pageHeight / 2) + " "
                        + probe(preview, previewSize(PAGE_WIDTH / 2), previewSize(MARGIN + RULE_Y + 1)) + " "
                        + probe(preview, previewSize(PAGE_WIDTH - MARGIN - 4), previewSize(tableTop() + 3))));
        checks.add(SwtChecks.info("preview digests (SHA-256 of the pixels)", () -> SwtChecks.sha256(preview)));
        return checks;
    }

    private static String probe(ImageData image, int x, int y) {
        return SwtChecks.argb(SwtSnapshots.pixel(image, x, y));
    }

    /**
     * {@code scope, pages, copies, collate, orientation, duplex, print to file (file name)}.
     */
    static String fields(PrinterData data) {
        String scope = switch (data.scope) {
            case PrinterData.ALL_PAGES -> "ALL_PAGES";
            case PrinterData.PAGE_RANGE -> "PAGE_RANGE";
            case PrinterData.SELECTION -> "SELECTION";
            default -> String.valueOf(data.scope);
        };
        String orientation = data.orientation == PrinterData.LANDSCAPE ? "LANDSCAPE"
                : data.orientation == PrinterData.PORTRAIT ? "PORTRAIT" : String.valueOf(data.orientation);
        String duplex = switch (data.duplex) {
            case SWT.DEFAULT -> "DEFAULT";
            case PrinterData.DUPLEX_NONE -> "NONE";
            case PrinterData.DUPLEX_LONG_EDGE -> "LONG_EDGE";
            case PrinterData.DUPLEX_SHORT_EDGE -> "SHORT_EDGE";
            default -> String.valueOf(data.duplex);
        };
        return "scope " + scope + ", pages " + data.startPage + "-" + data.endPage + ", copies " + data.copyCount
                + ", collate " + data.collate + ", " + orientation + ", duplex " + duplex + ", print to file "
                + data.printToFile + " (" + data.fileName + ")";
    }

    // ----------------------------------------------------------------------------------------------------- preview

    private static int previewSize(double points) {
        return (int) Math.round(points * PREVIEW_SCALE);
    }

    /**
     * Page {@code number} at 50 %, on a shadow.
     */
    private static ImageData preview(Display display, int number) {
        int width = previewSize(PAGE_WIDTH);
        int height = previewSize(PAGE_HEIGHT);
        return SwtSnapshots.offscreen(width + SHADOW, height + SHADOW, gc -> {
            gc.setBackground(SwtKit.color(SHADOW_COLOR));
            gc.fillRectangle(SHADOW, SHADOW, width, height);
            paintPage(gc, display, number, 0, 0, PREVIEW_SCALE);
            gc.setForeground(SwtKit.color(PAPER_BORDER));
            gc.drawRectangle(0, 0, width - 1, height - 1);
        });
    }

    /** The top of the header rule, below the top margin, in points. */
    static final int RULE_Y = 44;

    private static int tableTop() {
        return MARGIN + 500;
    }

    /**
     * Paints page {@code number} at ({@code x}, {@code y}), {@code scale} pixels per point : the drawing of a print
     * job, in points converted to the pixels of the device (its resolution) by a {@link Transform}, the fonts in
     * points converted by the {@code GC}.
     */
    static void paintPage(GC gc, Display display, int number, int x, int y, double scale) {
        double unit = display.getDPI().y / 72.0;
        Transform transform = new Transform(display);
        Image chart = number == 1 ? new Image(display, chartImage()) : null;
        try {
            gc.setAdvanced(true);
            gc.setAntialias(SWT.ON);
            gc.setTextAntialias(SWT.ON);
            gc.setInterpolation(SWT.HIGH);
            transform.translate(x, y);
            // device pixels (of the display) to the pixels of the image
            transform.scale((float) (scale / unit), (float) (scale / unit));
            gc.setTransform(transform);
            new PageDrawing(gc, unit).paint(number, chart);
        } finally {
            gc.setTransform(null);
            transform.dispose();
            if (chart != null) {
                chart.dispose();
            }
        }
    }

    /**
     * Draws one page in points ({@link #p} : points to device pixels).
     */
    private record PageDrawing(GC gc, double unit) {

        int p(double points) {
            return (int) Math.round(points * unit);
        }

        void paint(int number, Image chart) {
            gc.setBackground(SwtKit.color(PAPER));
            gc.fillRectangle(0, 0, p(PAGE_WIDTH), p(PAGE_HEIGHT));
            margins();
            int top = header(number);
            if (number == 1) {
                top = paragraphs(PARAGRAPHS_1, top);
                top = figure(chart, top);
                table(tableTop());
            } else {
                top = paragraphs(PARAGRAPHS_2, top);
                pie(top + 16);
            }
            footer(number);
        }

        private void margins() {
            gc.setForeground(SwtKit.color(MARGIN_COLOR));
            gc.setLineWidth(Math.max(1, p(0.75)));
            gc.setLineStyle(SWT.LINE_DASH);
            gc.drawRectangle(p(MARGIN), p(MARGIN), p(PAGE_WIDTH - 2 * MARGIN), p(PAGE_HEIGHT - 2 * MARGIN));
            gc.setLineStyle(SWT.LINE_SOLID);
            gc.setLineWidth(1);
        }

        /**
         * The title, the subtitle and the rule : returns the top of the body, in points.
         */
        private int header(int number) {
            gc.setForeground(SwtKit.color(ACCENT));
            gc.setFont(SwtKit.font(SWT.BOLD, 16));
            gc.drawString(number == 1 ? "Print preview" : "Print preview (continued)", p(MARGIN), p(MARGIN + 4),
                    true);
            gc.setBackground(SwtKit.color(ACCENT));
            gc.fillRectangle(p(MARGIN), p(MARGIN + RULE_Y), p(PAGE_WIDTH - 2 * MARGIN), p(4));
            gc.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
            gc.setFont(SwtKit.font(SWT.NORMAL, 8));
            String subtitle = "Quarkus Desktop showcase : SWT printing";
            Point extent = gc.stringExtent(subtitle);
            gc.drawString(subtitle, p(PAGE_WIDTH - MARGIN) - extent.x, p(MARGIN + RULE_Y - 4) - extent.y, true);
            return MARGIN + RULE_Y + 16;
        }

        /**
         * The paragraphs, wrapped at the width between the margins : returns the top of what follows, in points.
         */
        private int paragraphs(String[] paragraphs, int top) {
            gc.setFont(SwtKit.font(SWT.NORMAL, 10));
            gc.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
            int lineHeight = gc.getFontMetrics().getHeight();
            int y = p(top);
            for (String paragraph : paragraphs) {
                for (String line : wrap(gc, paragraph, p(PAGE_WIDTH - 2 * MARGIN))) {
                    gc.drawString(line, p(MARGIN), y, true);
                    y += lineHeight;
                }
                y += lineHeight / 2;
            }
            return (int) Math.ceil(y / unit);
        }

        /**
         * The chart, an image scaled into the page, and its caption : returns the top of what follows, in points.
         */
        private int figure(Image chart, int top) {
            int width = 400;
            int height = 200;
            int left = (PAGE_WIDTH - width) / 2;
            gc.drawImage(chart, 0, 0, CHART_WIDTH, CHART_HEIGHT, p(left), p(top + 8), p(width), p(height));
            gc.setFont(SwtKit.font(SWT.ITALIC, 8));
            gc.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
            String caption = "Figure 1 : an Image drawn offscreen, scaled into the page";
            Point extent = gc.stringExtent(caption);
            gc.drawString(caption, p(PAGE_WIDTH / 2.0) - extent.x / 2, p(top + 8 + height + 6), true);
            return top + 8 + height + 6 + 24;
        }

        private void table(int top) {
            int[] columns = { 0, 160, 300, PAGE_WIDTH - 2 * MARGIN };
            int rowHeight = 18;
            gc.setBackground(SwtKit.color(TABLE_HEADER));
            gc.fillRectangle(p(MARGIN), p(top), p(PAGE_WIDTH - 2 * MARGIN), p(rowHeight));
            gc.setForeground(SwtKit.color(TABLE_RULE));
            for (int row = 0; row <= TABLE.length; row++) {
                gc.drawLine(p(MARGIN), p(top + row * rowHeight), p(PAGE_WIDTH - MARGIN), p(top + row * rowHeight));
            }
            gc.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
            for (int row = 0; row < TABLE.length; row++) {
                gc.setFont(SwtKit.font(row == 0 ? SWT.BOLD : SWT.NORMAL, 9));
                int textY = p(top + row * rowHeight + rowHeight / 2.0) - gc.getFontMetrics().getHeight() / 2;
                for (int column = 0; column < TABLE[row].length; column++) {
                    gc.drawString(TABLE[row][column], p(MARGIN + columns[column] + 6), textY, true);
                }
            }
        }

        /**
         * A pie chart drawn with arcs (vector), with its legend.
         */
        private void pie(int top) {
            int diameter = 180;
            int left = MARGIN + 40;
            int angle = 90;
            for (int i = 0; i < PIE_VALUES.length; i++) {
                int sweep = -PIE_VALUES[i] * 360 / 100;
                gc.setBackground(SwtKit.color(BAR_COLORS[i]));
                gc.fillArc(p(left), p(top), p(diameter), p(diameter), angle, sweep);
                angle += sweep;
            }
            gc.setForeground(SwtKit.color(0xFFFFFF));
            gc.setLineWidth(p(1.5));
            gc.drawOval(p(left), p(top), p(diameter), p(diameter));
            gc.setLineWidth(1);
            gc.setFont(SwtKit.font(SWT.NORMAL, 9));
            int lineHeight = gc.getFontMetrics().getHeight();
            for (int i = 0; i < PIE_VALUES.length; i++) {
                int y = p(top + 30) + i * (lineHeight + p(6));
                gc.setBackground(SwtKit.color(BAR_COLORS[i]));
                gc.fillRectangle(p(left + diameter + 50), y + lineHeight / 2 - p(5), p(10), p(10));
                gc.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
                gc.drawString("Part " + (char) ('A' + i) + " : " + PIE_VALUES[i] + " %", p(left + diameter + 68), y,
                        true);
            }
        }

        private void footer(int number) {
            int y = PAGE_HEIGHT - MARGIN - 24;
            gc.setForeground(SwtKit.color(TABLE_RULE));
            gc.drawLine(p(MARGIN), p(y), p(PAGE_WIDTH - MARGIN), p(y));
            gc.setFont(SwtKit.font(SWT.NORMAL, 8));
            gc.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
            String text = "Page " + number + " of " + PAGES;
            Point extent = gc.stringExtent(text);
            gc.drawString(text, p(PAGE_WIDTH / 2.0) - extent.x / 2, p(y + 8), true);
        }
    }

    /**
     * The words of {@code text} on lines narrower than {@code width} (the font of the {@code GC}).
     */
    static List<String> wrap(GC gc, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || gc.textExtent(candidate).x <= width) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * A bar chart drawn offscreen : the image of the figure.
     */
    static ImageData chartImage() {
        return SwtSnapshots.offscreen(CHART_WIDTH, CHART_HEIGHT, gc -> {
            gc.setAntialias(SWT.ON);
            gc.setBackground(SwtKit.color(0xF5F7FB));
            gc.fillRectangle(0, 0, CHART_WIDTH, CHART_HEIGHT);
            gc.setForeground(SwtKit.color(0xCFD8DC));
            for (int y = 30; y < CHART_HEIGHT - 20; y += 30) {
                gc.drawLine(30, y, CHART_WIDTH - 10, y);
            }
            int barWidth = 36;
            int gap = (CHART_WIDTH - 40 - BAR_VALUES.length * barWidth) / BAR_VALUES.length;
            int base = CHART_HEIGHT - 20;
            for (int i = 0; i < BAR_VALUES.length; i++) {
                int height = BAR_VALUES[i] * (base - 20) / 100;
                gc.setBackground(SwtKit.color(BAR_COLORS[i]));
                gc.fillRoundRectangle(34 + i * (barWidth + gap) + gap / 2, base - height, barWidth, height, 6, 6);
            }
            gc.setForeground(SwtKit.color(0x455A64));
            gc.setLineWidth(2);
            gc.drawLine(30, base, CHART_WIDTH - 10, base);
            gc.drawLine(30, 10, 30, base);
        });
    }
}
