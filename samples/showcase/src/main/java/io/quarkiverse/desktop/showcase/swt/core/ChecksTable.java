package io.quarkiverse.desktop.showcase.swt.core;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

import io.quarkiverse.desktop.showcase.core.Check;

/**
 * A table of checks (status, name, value) painted with a {@code GC} : the SWT counterpart of {@code core/ChecksView}.
 * The checks are attached to it ({@link SwtChecks#attach}), so they end up in report.json.
 * <p>
 * Status marks : green check = passed, red cross = failed, gray dot = informational. Names and values are wrapped,
 * with the system font family at {@link SwtKit#TEXT_POINTS} points. The {@link Check#attempts} checks are not painted
 * (they may differ from run to run). Deterministic : no caret, no focus (it never takes the focus), no hover.
 * <p>
 * The usual pattern : built with {@code List.of(Check.info("state", "pending"))}, then {@link #setChecks} once the page
 * is ready.
 */
public class ChecksTable extends Canvas {

    /** Default width of the table. */
    public static final int WIDTH = 1000;
    /** Default width of the name column. */
    public static final int NAME_WIDTH = 280;

    private static final int PASS = 0x1B7F3A;
    private static final int FAIL = 0xC62828;
    private static final int INFO = 0x666666;
    private static final int TEXT = 0x222222;
    private static final int RULE = 0xE0E0E0;
    private static final int STATUS_WIDTH = 24;
    private static final int GAP = 12;
    private static final int ROW_GAP = 4;
    private static final int TITLE_GAP = 8;

    private final String title;
    private final int nameWidth;
    private final int width;
    private List<Check> checks;
    private List<Row> rows;
    private int titleHeight;
    private int lineHeight;
    private int height;

    private record Row(Check check, List<String> names, List<String> values, int y, int height) {
    }

    /**
     * A table of {@code checks}, {@code width} points wide, its name column {@code nameWidth} points wide.
     */
    public ChecksTable(Composite parent, String title, List<Check> checks, int nameWidth, int width) {
        super(parent, SWT.NO_FOCUS);
        this.title = title;
        this.nameWidth = nameWidth;
        this.width = width;
        setBackground(SwtKit.color(SwtKit.WHITE));
        addListener(SWT.Paint, this::paint);
        setChecks(checks);
    }

    /**
     * A table of {@code checks} with the default widths.
     */
    public static ChecksTable table(Composite parent, String title, List<Check> checks) {
        return new ChecksTable(parent, title, checks, NAME_WIDTH, WIDTH);
    }

    public static ChecksTable table(Composite parent, String title, List<Check> checks, int nameWidth, int width) {
        return new ChecksTable(parent, title, checks, nameWidth, width);
    }

    public List<Check> getChecks() {
        return checks;
    }

    /**
     * Replaces the checks shown and attached (e.g. checks computed once the page is ready), and lays the page out
     * again : call it on the user interface thread.
     */
    public void setChecks(List<Check> checks) {
        checkWidget();
        this.checks = List.copyOf(checks);
        SwtChecks.attach(this, this.checks);
        rows = null;
        requestLayout();
        redraw();
    }

    private Font font() {
        return SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS);
    }

    private Font titleFont() {
        return SwtKit.font(SWT.BOLD, SwtKit.TEXT_POINTS + 1);
    }

    private void measure() {
        if (rows != null) {
            return;
        }
        GC gc = new GC(this);
        try {
            titleHeight = 0;
            if (title != null && !title.isEmpty()) {
                gc.setFont(titleFont());
                titleHeight = gc.getFontMetrics().getHeight() + TITLE_GAP;
            }
            gc.setFont(font());
            lineHeight = gc.getFontMetrics().getHeight();
            int valueWidth = width - STATUS_WIDTH - nameWidth - 2 * GAP;
            int y = titleHeight;
            List<Row> list = new ArrayList<>();
            for (Check check : checks) {
                if (check.isAttempts()) {
                    // may differ from run to run : report.json only
                    continue;
                }
                List<String> names = wrap(gc, check.name(), nameWidth);
                List<String> values = wrap(gc, check.value(), valueWidth);
                int h = Math.max(names.size(), values.size()) * lineHeight;
                list.add(new Row(check, names, values, y, h));
                y += h + ROW_GAP;
            }
            rows = list;
            height = y;
        } finally {
            gc.dispose();
        }
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        checkWidget();
        measure();
        return new Point(width, Math.max(1, height));
    }

    private void paint(Event event) {
        measure();
        GC gc = event.gc;
        gc.setForeground(SwtKit.color(TEXT));
        if (title != null && !title.isEmpty()) {
            gc.setFont(titleFont());
            gc.drawString(title, 0, 0, true);
        }
        gc.setFont(font());
        int valueX = STATUS_WIDTH + nameWidth + 2 * GAP;
        for (Row row : rows) {
            int ruleY = row.y() + row.height() + ROW_GAP / 2;
            gc.setForeground(SwtKit.color(RULE));
            gc.drawLine(0, ruleY, width - 1, ruleY);
            gc.setForeground(SwtKit.color(TEXT));
            int y = row.y();
            for (String line : row.names()) {
                gc.drawString(line, STATUS_WIDTH + GAP, y, true);
                y += lineHeight;
            }
            gc.setForeground(SwtKit.color(Boolean.FALSE.equals(row.check().ok()) ? FAIL : TEXT));
            y = row.y();
            for (String line : row.values()) {
                gc.drawString(line, valueX, y, true);
                y += lineHeight;
            }
        }
        // the marks last : anti-aliasing switches the GC to advanced graphics
        gc.setAntialias(SWT.ON);
        gc.setLineWidth(2);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineJoin(SWT.JOIN_ROUND);
        for (Row row : rows) {
            paintStatus(gc, row.check().ok(), row.y() + lineHeight / 2);
        }
    }

    private static void paintStatus(GC gc, Boolean ok, int centerY) {
        int cx = STATUS_WIDTH / 2;
        if (ok == null) {
            gc.setBackground(SwtKit.color(INFO));
            gc.fillOval(cx - 3, centerY - 3, 6, 6);
        } else if (ok) {
            gc.setForeground(SwtKit.color(PASS));
            gc.drawPolyline(new int[] { cx - 5, centerY, cx - 2, centerY + 4, cx + 5, centerY - 5 });
        } else {
            gc.setForeground(SwtKit.color(FAIL));
            gc.drawLine(cx - 4, centerY - 4, cx + 4, centerY + 4);
            gc.drawLine(cx + 4, centerY - 4, cx - 4, centerY + 4);
        }
    }

    /**
     * The lines of {@code text} wrapped at {@code width} : at the spaces, or inside a word longer than the width.
     */
    static List<String> wrap(GC gc, String text, int width) {
        List<String> lines = new ArrayList<>();
        String clean = text == null ? "null" : text.replace("\r", "").replace("\t", "    ");
        for (String paragraph : clean.split("\n", -1)) {
            if (gc.stringExtent(paragraph).x <= width) {
                lines.add(paragraph);
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (gc.stringExtent(candidate).x <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (rest.length() > 1 && gc.stringExtent(rest).x > width) {
                    int fit = fit(gc, rest, width);
                    lines.add(rest.substring(0, fit));
                    rest = rest.substring(fit);
                }
                line.append(rest);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * The length of the longest prefix of {@code text} narrower than {@code width} (at least 1).
     */
    private static int fit(GC gc, String text, int width) {
        int low = 1;
        int high = text.length() - 1;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (gc.stringExtent(text.substring(0, middle)).x <= width) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }
}
