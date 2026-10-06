package io.quarkiverse.desktop.showcase.swt.pages.layouts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * The layouts of {@code org.eclipse.swt.layout} and the {@link StackLayout} of {@code org.eclipse.swt.custom} :
 * {@link FillLayout} (horizontal and vertical, margins and spacing), {@link RowLayout} (wrap, pack, justify, center,
 * fill, vertical, {@link RowData}), {@link GridLayout} (spans, alignments, grabbing the excess space, width and height
 * hints, margins, indents, exclusion, columns of equal width), {@link FormLayout} (attachments to the parent in
 * percentages and fractions, to other controls with alignments, offsets, margins and spacing) and {@link StackLayout}
 * (the top control).
 * <p>
 * The layouts are pure Java : they read the client area of the composite and the preferred sizes of the children
 * ({@code Control.computeSize}), and place the children with {@code Control.setBounds}. The demo boxes are canvases
 * with a fixed preferred size, in containers without trim : the bounds of every box are exact, the same on every
 * platform and in both runtimes, and they are checked against expected values. The native code paths are those of
 * {@code setBounds}, {@code getBounds} and {@code getClientArea} (SetWindowPos, DeferWindowPos and GetClientRect on
 * Windows, the size allocation of a GtkWidget on GTK, the frame of an NSView on macOS), whose structures (RECT,
 * WINDOWPOS, GtkAllocation, NSRect) SWT copies through JNI : a native executable that misses a registration shows a
 * crash, misplaced boxes or failed checks where the JVM works. The last two containers hold native controls (Label,
 * Text, Combo, Spinner, Button), whose preferred sizes come from the platform (the text extents of their font and the
 * metrics of the theme) : their bounds are informational, compared between the runs of the same machine.
 */
@Singleton
public class SwtLayoutsPage implements SwtPage {

    private static final int TILE_WIDTH = 240;
    private static final int TILE_HEIGHT = 116;
    private static final int GAP = 12;
    private static final int WIDE_WIDTH = 2 * TILE_WIDTH + GAP;
    private static final int CONTAINER_BACKGROUND = 0xF5F7FA;
    private static final int CONTAINER_BORDER = 0xB0BEC5;
    private static final int[] COLORS = { 0xFF8A65, 0x4FC3F7, 0xAED581, 0xFFD54F, 0xBA68C8, 0x4DB6AC, 0xF06292,
            0x90A4AE };
    /** The key of the name of a child of a demo ({@code Widget.getData(String)}). */
    private static final String NAME = "io.quarkiverse.desktop.showcase.swt.layouts.name";

    // per build state
    private Demos demos;

    /** A demo : its name, its container and whether the bounds of its children are exact (fixed size boxes). */
    private record Demo(String name, Composite container, boolean exact) {
    }

    /** The demos of one build, checked once laid out. */
    private static final class Demos {
        final List<Demo> list = new ArrayList<>();
        ChecksTable boundsTable;
        ChecksTable apiTable;
        CompletionStage<Void> checked;

        Demo get(String name) {
            return list.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
        }
    }

    @Override
    public String id() {
        return "swt-layouts";
    }

    @Override
    public String title() {
        return "Layouts";
    }

    @Override
    public String category() {
        return SwtCategories.LAYOUTS;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Demos d = new Demos();
        demos = d;
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "Boxes with fixed preferred sizes, laid out in " + TILE_WIDTH + " x " + TILE_HEIGHT
                + " containers without trim by the layouts of org.eclipse.swt.layout and by StackLayout. The layouts"
                + " are pure Java and the boxes do not depend on the fonts: their bounds are exact on every platform."
                + " The last two containers hold native controls, whose preferred sizes come from the fonts and the"
                + " theme of the platform.", SwtKit.TEXT_WIDTH);

        Composite fillAndRow = SwtKit.row(page, GAP);
        d.list.add(fillHorizontal(fillAndRow));
        d.list.add(fillVertical(fillAndRow));
        d.list.add(rowDemo(fillAndRow, "RowLayout wrap, pack", "default margins (3), spacing 4", layout -> {
        }));
        d.list.add(rowDemo(fillAndRow, "RowLayout pack false", "every box at the largest size",
                layout -> layout.pack = false));
        Composite rows = SwtKit.row(page, GAP);
        d.list.add(rowDemo(rows, "RowLayout justify", "extra space spread in the rows",
                layout -> layout.justify = true));
        d.list.add(rowDemo(rows, "RowLayout center", "centered in the height of the row",
                layout -> layout.center = true));
        d.list.add(rowDemo(rows, "RowLayout fill", "the height of the row", layout -> layout.fill = true));
        d.list.add(rowVertical(rows));
        Composite grids = SwtKit.row(page, GAP);
        d.list.add(gridSpans(grids));
        d.list.add(gridGrab(grids));
        d.list.add(gridEqualWidth(grids));
        d.list.add(gridIndents(grids));
        Composite forms = SwtKit.row(page, GAP);
        d.list.add(formPercentages(forms));
        d.list.add(formControls(forms));
        d.list.add(formFractions(forms));
        d.list.add(stack(forms));
        Composite natives = SwtKit.row(page, GAP);
        d.list.add(nativeForm(natives));
        d.list.add(nativeRow(natives));

        d.boundsTable = ChecksTable.table(page, "Bounds of the children (x,y wxh)",
                List.of(Check.info("state", "pending")), 240, SwtKit.TEXT_WIDTH);
        d.apiTable = ChecksTable.table(page, "Layout computations", List.of(Check.info("state", "pending")), 380,
                SwtKit.TEXT_WIDTH);

        // build returns before the page frame lays the content out : the checks read the final bounds
        d.checked = UiStages.rounds(2).thenAccept(v -> {
            if (!d.boundsTable.isDisposed()) {
                d.boundsTable.setChecks(boundsChecks(d));
                d.apiTable.setChecks(apiChecks(d));
            }
        });
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        Demos d = demos;
        return d == null ? CompletableFuture.completedFuture(null) : d.checked;
    }

    @Override
    public void dispose(Control content) {
        demos = null;
    }

    // ------------------------------------------------------------------------------------------------------- tiles

    /**
     * A tile : the container of a demo ({@code width x height}, {@code SWT.DEFAULT} : its preferred height, a light
     * background and a 1 point border painted over its client area, no trim) with {@code layout}, then its name and
     * {@code details} under it.
     */
    private static Composite container(Composite parent, String name, String details, Layout layout, int width,
            int height) {
        Composite tile = SwtKit.column(parent, 2);
        Composite container = new Composite(tile, SWT.NONE);
        container.setBackground(SwtKit.color(CONTAINER_BACKGROUND));
        container.addListener(SWT.Paint, event -> {
            Rectangle area = container.getClientArea();
            event.gc.setForeground(SwtKit.color(CONTAINER_BORDER));
            event.gc.drawRectangle(0, 0, area.width - 1, area.height - 1);
        });
        container.setLayout(layout);
        SwtKit.size(container, width, height);
        SwtKit.text(tile, name, SwtKit.font(SWT.BOLD, 8), SwtKit.TEXT_COLOR, 0);
        SwtKit.text(tile, details, SwtKit.font(SWT.NORMAL, 8), SwtKit.MUTED_COLOR, width);
        return container;
    }

    private static Demo demo(Composite parent, String name, String details, Layout layout, Consumer<Composite> boxes) {
        Composite container = container(parent, name, details, layout, TILE_WIDTH, TILE_HEIGHT);
        boxes.accept(container);
        return new Demo(name, container, true);
    }

    private static Box box(Composite parent, String name, int width, int height, int color) {
        return new Box(parent, name, width, height, COLORS[color % COLORS.length]);
    }

    private static <C extends Control> C named(C control, String name) {
        control.setData(NAME, name);
        return control;
    }

    // -------------------------------------------------------------------------------------------- FillLayout, Row

    private static Demo fillHorizontal(Composite parent) {
        FillLayout layout = new FillLayout(SWT.HORIZONTAL);
        layout.marginWidth = 4;
        layout.marginHeight = 4;
        layout.spacing = 4;
        return demo(parent, "FillLayout HORIZONTAL", "margins 4, spacing 4", layout, c -> {
            box(c, "A", 30, 20, 0);
            box(c, "B", 60, 20, 1);
            box(c, "C", 40, 20, 2);
        });
    }

    private static Demo fillVertical(Composite parent) {
        FillLayout layout = new FillLayout(SWT.VERTICAL);
        layout.marginWidth = 8;
        layout.marginHeight = 6;
        layout.spacing = 2;
        return demo(parent, "FillLayout VERTICAL", "margins 8 and 6, spacing 2", layout, c -> {
            for (int i = 0; i < 4; i++) {
                box(c, String.valueOf((char) ('A' + i)), 40, 20, i);
            }
        });
    }

    /**
     * A horizontal RowLayout (wrap and pack by default, the default margins, spacing 4) of 5 boxes of different sizes :
     * 3 boxes in the first row, 2 in the second.
     */
    private static Demo rowDemo(Composite parent, String name, String details, Consumer<RowLayout> config) {
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.spacing = 4;
        config.accept(layout);
        return demo(parent, name, details, layout, c -> {
            box(c, "A", 70, 24, 0);
            box(c, "B", 50, 36, 1);
            box(c, "C", 60, 20, 2);
            box(c, "D", 60, 30, 3);
            box(c, "E", 40, 24, 4);
        });
    }

    private static Demo rowVertical(Composite parent) {
        RowLayout layout = new RowLayout(SWT.VERTICAL);
        layout.spacing = 4;
        return demo(parent, "RowLayout VERTICAL, wrap", "RowData(60, 20) for C (30 x 30)", layout, c -> {
            box(c, "A", 50, 24, 0);
            box(c, "B", 40, 30, 1);
            box(c, "C", 30, 30, 2).setLayoutData(new RowData(60, 20));
            box(c, "D", 50, 24, 3);
            box(c, "E", 40, 36, 4);
        });
    }

    // -------------------------------------------------------------------------------------------------- GridLayout

    private static GridLayout grid(int columns, boolean equalWidth, int margin, int spacing) {
        GridLayout layout = new GridLayout(columns, equalWidth);
        layout.marginWidth = margin;
        layout.marginHeight = margin;
        layout.horizontalSpacing = spacing;
        layout.verticalSpacing = spacing;
        return layout;
    }

    private static GridData data(int horizontal, int vertical, boolean grabHorizontal, boolean grabVertical,
            int columns, int rows) {
        return new GridData(horizontal, vertical, grabHorizontal, grabVertical, columns, rows);
    }

    private static Demo gridSpans(Composite parent) {
        return demo(parent, "GridLayout spans, alignments", "F spans 2 rows, G 2 columns",
                grid(3, false, 4, 4), c -> {
                    box(c, "A", 70, 20, 0).setLayoutData(data(SWT.BEGINNING, SWT.CENTER, false, false, 1, 1));
                    box(c, "B", 50, 32, 1).setLayoutData(data(SWT.BEGINNING, SWT.BEGINNING, false, false, 1, 1));
                    box(c, "C", 60, 20, 2).setLayoutData(data(SWT.BEGINNING, SWT.END, false, false, 1, 1));
                    box(c, "D", 30, 20, 3).setLayoutData(data(SWT.END, SWT.CENTER, false, false, 1, 1));
                    box(c, "E", 30, 20, 4).setLayoutData(data(SWT.CENTER, SWT.CENTER, false, false, 1, 1));
                    box(c, "F", 40, 20, 5).setLayoutData(data(SWT.FILL, SWT.FILL, false, false, 1, 2));
                    box(c, "G", 40, 20, 6).setLayoutData(data(SWT.FILL, SWT.CENTER, false, false, 2, 1));
                });
    }

    private static Demo gridGrab(Composite parent) {
        GridLayout layout = grid(2, false, 10, 6);
        layout.marginHeight = 8;
        return demo(parent, "GridLayout grab, hints", "margins 10 and 8, spacing 6", layout, c -> {
            box(c, "L1", 40, 20, 0).setLayoutData(data(SWT.BEGINNING, SWT.CENTER, false, false, 1, 1));
            box(c, "grab", 40, 20, 1).setLayoutData(data(SWT.FILL, SWT.CENTER, true, false, 1, 1));
            GridData widthHint = data(SWT.BEGINNING, SWT.FILL, false, false, 1, 1);
            widthHint.widthHint = 60;
            box(c, "L2", 40, 20, 2).setLayoutData(widthHint);
            GridData both = data(SWT.FILL, SWT.FILL, true, true, 1, 1);
            both.heightHint = 30;
            box(c, "both", 40, 20, 3).setLayoutData(both);
            GridData hints = data(SWT.END, SWT.END, false, false, 2, 1);
            hints.widthHint = 50;
            hints.heightHint = 18;
            box(c, "hint", 40, 20, 4).setLayoutData(hints);
        });
    }

    private static Demo gridEqualWidth(Composite parent) {
        return demo(parent, "GridLayout equal width", "3 equal columns, a grabbing span", grid(3, true, 6, 6), c -> {
            box(c, "20", 20, 20, 0).setLayoutData(data(SWT.BEGINNING, SWT.CENTER, false, false, 1, 1));
            box(c, "40", 40, 20, 1).setLayoutData(data(SWT.CENTER, SWT.CENTER, false, false, 1, 1));
            box(c, "60", 60, 20, 2).setLayoutData(data(SWT.END, SWT.CENTER, false, false, 1, 1));
            box(c, "span 3", 30, 20, 3).setLayoutData(data(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            for (int i = 0; i < 3; i++) {
                box(c, "r" + (i + 1), 20, 20, 4 + i).setLayoutData(data(SWT.FILL, SWT.FILL, false, true, 1, 1));
            }
        });
    }

    private static Demo gridIndents(Composite parent) {
        return demo(parent, "GridLayout indents, exclude", "indents 16 and 10, X excluded", grid(2, false, 4, 4),
                c -> {
                    box(c, "A", 50, 20, 0);
                    GridData b = new GridData();
                    b.horizontalIndent = 16;
                    box(c, "B", 50, 20, 1).setLayoutData(b);
                    GridData cData = new GridData();
                    cData.verticalIndent = 10;
                    box(c, "C", 50, 20, 2).setLayoutData(cData);
                    GridData dData = new GridData();
                    dData.horizontalIndent = 16;
                    dData.verticalIndent = 10;
                    box(c, "D", 50, 20, 3).setLayoutData(dData);
                    // excluded : the layout neither places it nor counts it, it keeps its own bounds
                    GridData excluded = new GridData();
                    excluded.exclude = true;
                    Box x = box(c, "X", 40, 24, 4);
                    x.setLayoutData(excluded);
                    x.setBounds(180, 80, 40, 24);
                });
    }

    // -------------------------------------------------------------------------------------------------- FormLayout

    private static FormData form(FormAttachment left, FormAttachment top, FormAttachment right,
            FormAttachment bottom) {
        FormData data = new FormData();
        data.left = left;
        data.top = top;
        data.right = right;
        data.bottom = bottom;
        return data;
    }

    private static Demo formPercentages(Composite parent) {
        return demo(parent, "FormLayout percentages", "50 % and 25 %, offsets 2 and 4", new FormLayout(), c -> {
            box(c, "TL", 20, 20, 0).setLayoutData(form(new FormAttachment(0, 4), new FormAttachment(0, 4),
                    new FormAttachment(50, -2), new FormAttachment(50, -2)));
            box(c, "TR", 20, 20, 1).setLayoutData(form(new FormAttachment(50, 2), new FormAttachment(0, 4),
                    new FormAttachment(100, -4), new FormAttachment(50, -2)));
            box(c, "BL", 20, 20, 2).setLayoutData(form(new FormAttachment(0, 4), new FormAttachment(50, 2),
                    new FormAttachment(25, -2), new FormAttachment(100, -4)));
            box(c, "BR", 20, 20, 3).setLayoutData(form(new FormAttachment(25, 2), new FormAttachment(50, 2),
                    new FormAttachment(100, -4), new FormAttachment(100, -4)));
        });
    }

    private static Demo formControls(Composite parent) {
        FormLayout layout = new FormLayout();
        layout.marginWidth = 6;
        layout.marginHeight = 6;
        return demo(parent, "FormLayout to controls", "aligned TOP, LEFT, RIGHT, CENTER", layout, c -> {
            Box a = box(c, "A", 60, 24, 0);
            FormData aData = form(new FormAttachment(0, 0), new FormAttachment(0, 0), null, null);
            a.setLayoutData(aData);
            Box b = box(c, "B", 50, 24, 1);
            b.setLayoutData(form(new FormAttachment(a, 8), new FormAttachment(a, 0, SWT.TOP), null, null));
            Box cBox = box(c, "C", 20, 20, 2);
            FormData cData = form(new FormAttachment(a, 0, SWT.LEFT), new FormAttachment(a, 6),
                    new FormAttachment(b, 0, SWT.RIGHT), null);
            cData.height = 20;
            cBox.setLayoutData(cData);
            box(c, "D", 40, 20, 3).setLayoutData(form(new FormAttachment(cBox, 0, SWT.CENTER),
                    new FormAttachment(cBox, 6), null, null));
            FormData eData = form(null, null, new FormAttachment(100, 0), new FormAttachment(100, 0));
            eData.width = 50;
            eData.height = 30;
            box(c, "E", 20, 20, 4).setLayoutData(eData);
        });
    }

    private static Demo formFractions(Composite parent) {
        FormLayout layout = new FormLayout();
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.spacing = 6;
        return demo(parent, "FormLayout thirds, spacing", "margins 8, spacing 6, 1/3 and 2/3", layout, c -> {
            Box a = box(c, "1/3", 20, 20, 0);
            a.setLayoutData(form(new FormAttachment(0), new FormAttachment(0), new FormAttachment(1, 3, 0),
                    new FormAttachment(100)));
            Box b = box(c, "2/3", 20, 20, 1);
            b.setLayoutData(form(new FormAttachment(a), new FormAttachment(0), new FormAttachment(2, 3, 0),
                    new FormAttachment(100)));
            Box top = box(c, "top", 20, 40, 2);
            top.setLayoutData(form(new FormAttachment(b), new FormAttachment(0), new FormAttachment(100), null));
            box(c, "rest", 20, 20, 3).setLayoutData(form(new FormAttachment(b), new FormAttachment(top),
                    new FormAttachment(100), new FormAttachment(100)));
        });
    }

    private static Demo stack(Composite parent) {
        StackLayout layout = new StackLayout();
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        return demo(parent, "StackLayout", "topControl Two of One, Two, Three", layout, c -> {
            box(c, "One", 30, 20, 0);
            Box two = box(c, "Two", 30, 20, 1);
            box(c, "Three", 30, 20, 2);
            layout.topControl = two;
        });
    }

    // ---------------------------------------------------------------------------------------------- native controls

    private static Demo nativeForm(Composite parent) {
        GridLayout layout = grid(2, false, 8, 6);
        layout.horizontalSpacing = 8;
        Composite c = container(parent, "GridLayout of native controls",
                "Label, Text, Combo and Button, sized by the platform", layout, WIDE_WIDTH, SWT.DEFAULT);
        named(new Label(c, SWT.NONE), "name label").setText("Name");
        Text name = named(new Text(c, SWT.BORDER), "name text");
        name.setText("Ada Lovelace");
        name.setLayoutData(data(SWT.FILL, SWT.CENTER, true, false, 1, 1));
        named(new Label(c, SWT.NONE), "role label").setText("Role");
        Combo role = named(new Combo(c, SWT.READ_ONLY), "role combo");
        role.setItems("Engineer", "Mathematician");
        role.select(1);
        role.setLayoutData(data(SWT.FILL, SWT.CENTER, true, false, 1, 1));
        Composite buttons = named(new Composite(c, SWT.NONE), "buttons");
        RowLayout row = new RowLayout(SWT.HORIZONTAL);
        row.marginLeft = 0;
        row.marginTop = 0;
        row.marginRight = 0;
        row.marginBottom = 0;
        row.spacing = 6;
        buttons.setLayout(row);
        buttons.setLayoutData(data(SWT.END, SWT.CENTER, false, false, 2, 1));
        new Button(buttons, SWT.PUSH).setText("Cancel");
        new Button(buttons, SWT.PUSH).setText("Save");
        return new Demo("GridLayout of native controls", c, false);
    }

    private static Demo nativeRow(Composite parent) {
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginWidth = 4;
        layout.spacing = 6;
        layout.center = true;
        Composite c = container(parent, "RowLayout of native controls", "wrapped and centered in the rows", layout,
                WIDE_WIDTH, SWT.DEFAULT);
        named(new Button(c, SWT.PUSH), "push").setText("Push");
        Button check = named(new Button(c, SWT.CHECK), "check");
        check.setText("Check");
        check.setSelection(true);
        Button radio = named(new Button(c, SWT.RADIO), "radio");
        radio.setText("Radio");
        radio.setSelection(true);
        Button toggle = named(new Button(c, SWT.TOGGLE), "toggle");
        toggle.setText("Toggle");
        toggle.setSelection(true);
        named(new Button(c, SWT.ARROW | SWT.RIGHT), "arrow");
        named(new Label(c, SWT.NONE), "label").setText("A label");
        Spinner spinner = named(new Spinner(c, SWT.BORDER), "spinner");
        spinner.setValues(5, 0, 10, 0, 1, 5);
        Text text = named(new Text(c, SWT.BORDER), "text");
        text.setText("A text field");
        text.setLayoutData(new RowData(160, SWT.DEFAULT));
        return new Demo("RowLayout of native controls", c, false);
    }

    // ------------------------------------------------------------------------------------------------------ checks

    /**
     * The bounds of the fixed size boxes : the layouts are pure Java, the same everywhere.
     */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("FillLayout HORIZONTAL", "A:4,4 75x108 B:83,4 74x108 C:161,4 75x108"),
            Map.entry("FillLayout VERTICAL", "A:8,6 224x25 B:8,33 224x24 C:8,59 224x24 D:8,85 224x25"),
            Map.entry("RowLayout wrap, pack", "A:3,3 70x24 B:77,3 50x36 C:131,3 60x20 D:3,43 60x30 E:67,43 40x24"),
            Map.entry("RowLayout pack false", "A:3,3 70x36 B:77,3 70x36 C:151,3 70x36 D:3,43 70x36 E:77,43 70x36"),
            Map.entry("RowLayout justify", "A:15,3 70x24 B:101,3 50x36 C:167,3 60x20 D:47,43 60x30 E:155,43 40x24"),
            Map.entry("RowLayout center", "A:3,9 70x24 B:77,3 50x36 C:131,11 60x20 D:3,43 60x30 E:67,46 40x24"),
            Map.entry("RowLayout fill", "A:3,3 70x36 B:77,3 50x36 C:131,3 60x36 D:3,43 60x30 E:67,43 40x30"),
            Map.entry("RowLayout VERTICAL, wrap", "A:3,3 50x24 B:3,31 40x30 C:3,65 60x20 D:3,89 50x24 E:67,3 40x36"),
            Map.entry("GridLayout spans, alignments",
                    "A:4,10 70x20 B:78,4 50x32 C:132,16 60x20 D:44,40 30x20 E:88,40 30x20 F:132,40 60x44"
                            + " G:4,64 124x20"),
            Map.entry("GridLayout grab, hints",
                    "L1:10,8 40x20 grab:76,8 154x20 L2:10,34 60x50 both:76,34 154x50 hint:180,90 50x18"),
            Map.entry("GridLayout equal width",
                    "20:6,6 20x20 40:100,6 40x20 60:174,6 60x20 span 3:6,32 228x20 r1:6,58 72x52 r2:84,58 72x52"
                            + " r3:162,58 72x52"),
            Map.entry("GridLayout indents, exclude",
                    "A:4,4 50x20 B:74,4 50x20 C:4,38 50x20 D:74,38 50x20 X:180,80 40x24"),
            Map.entry("FormLayout percentages", "TL:4,4 114x52 TR:122,4 114x52 BL:4,60 54x52 BR:62,60 174x52"),
            Map.entry("FormLayout to controls", "A:6,6 60x24 B:74,6 50x24 C:6,36 118x20 D:45,62 40x20 E:184,80 50x30"),
            Map.entry("FormLayout thirds, spacing", "1/3:8,8 74x100 2/3:88,8 69x100 top:163,8 69x40 rest:163,54 69x54"),
            Map.entry("StackLayout", "One:8,8 224x100 Two:8,8 224x100 Three:8,8 224x100"));

    private static List<Check> boundsChecks(Demos d) {
        List<Check> checks = new ArrayList<>();
        for (Demo demo : d.list) {
            String expected = EXPECTED.get(demo.name());
            if (demo.exact() && expected != null) {
                checks.add(SwtChecks.expect(demo.name(), expected, () -> dump(demo.container())));
            } else {
                checks.add(SwtChecks.info(demo.name() + " (font dependent)", () -> dump(demo.container())));
            }
        }
        return checks;
    }

    private static List<Check> apiChecks(Demos d) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("FillLayout HORIZONTAL : preferred size", "196x28",
                () -> preferred(d, "FillLayout HORIZONTAL", SWT.DEFAULT)));
        checks.add(SwtChecks.expect("RowLayout wrap, pack : preferred size, at 130", "302x42 / 130x104",
                () -> preferred(d, "RowLayout wrap, pack", SWT.DEFAULT) + " / "
                        + preferred(d, "RowLayout wrap, pack", 130)));
        checks.add(SwtChecks.expect("GridLayout spans, alignments : preferred size", "196x88",
                () -> preferred(d, "GridLayout spans, alignments", SWT.DEFAULT)));
        checks.add(SwtChecks.expect("GridLayout grab, hints : preferred size", "126x96",
                () -> preferred(d, "GridLayout grab, hints", SWT.DEFAULT)));
        checks.add(SwtChecks.expect("FormLayout to controls : preferred size", "130x88",
                () -> preferred(d, "FormLayout to controls", SWT.DEFAULT)));
        checks.add(SwtChecks.expect("StackLayout : preferred size, visible children", "46x36 / false true false",
                () -> preferred(d, "StackLayout", SWT.DEFAULT) + " / "
                        + visible(d.get("StackLayout").container())));
        checks.add(SwtChecks.expect("StackLayout : topControl One, layout()", "true false false",
                SwtLayoutsPage::stackSwitch));
        checks.add(SwtChecks.expect("GridData.exclude : X keeps its own bounds", "180,80 40x24",
                () -> SwtChecks.rect(d.get("GridLayout indents, exclude").container().getChildren()[4].getBounds())));
        checks.add(SwtChecks.expect("layout(true, true) again : the same bounds", true, () -> {
            List<String> before = d.list.stream().map(demo -> dump(demo.container())).toList();
            d.list.forEach(demo -> demo.container().layout(true, true));
            return before.equals(d.list.stream().map(demo -> dump(demo.container())).toList());
        }));
        checks.add(SwtChecks.info("preferred sizes of the form controls (font)", () -> {
            Composite c = d.get("GridLayout of native controls").container();
            return List.of(c.getChildren()).stream()
                    .map(child -> name(child) + ":" + SwtChecks.size(child.computeSize(SWT.DEFAULT, SWT.DEFAULT)))
                    .collect(Collectors.joining(" "));
        }));
        checks.add(SwtChecks.info("sizes of the native containers (font)",
                () -> SwtChecks.size(d.get("GridLayout of native controls").container().getSize()) + " / "
                        + SwtChecks.size(d.get("RowLayout of native controls").container().getSize())));
        return checks;
    }

    /**
     * {@code wxh} : the preferred size of the container of the demo {@code name} at the width {@code wHint}
     * ({@code SWT.DEFAULT} : its preferred width), computed by its layout.
     */
    private static String preferred(Demos d, String name, int wHint) {
        return SwtChecks.size(d.get(name).container().computeSize(wHint, SWT.DEFAULT));
    }

    /**
     * {@code true} or {@code false} for every child of {@code container}, in creation order.
     */
    private static String visible(Composite container) {
        return List.of(container.getChildren()).stream().map(child -> String.valueOf(child.getVisible()))
                .collect(Collectors.joining(" "));
    }

    /**
     * The visible children of a StackLayout once its top control changed, in a composite of a hidden shell (the shown
     * demo is never changed).
     */
    private static String stackSwitch() {
        Shell shell = new Shell(UiStages.display(), SWT.NO_TRIM);
        try {
            Composite c = new Composite(shell, SWT.NONE);
            StackLayout layout = new StackLayout();
            c.setLayout(layout);
            Box one = box(c, "One", 30, 20, 0);
            Box two = box(c, "Two", 30, 20, 1);
            box(c, "Three", 30, 20, 2);
            c.setSize(100, 60);
            layout.topControl = two;
            c.layout();
            layout.topControl = one;
            c.layout();
            return visible(c);
        } finally {
            shell.dispose();
        }
    }

    /**
     * {@code name:x,y wxh} of every child of {@code container}, in creation order.
     */
    private static String dump(Composite container) {
        return List.of(container.getChildren()).stream()
                .map(child -> name(child) + ":" + SwtChecks.rect(child.getBounds()))
                .collect(Collectors.joining(" "));
    }

    private static String name(Control control) {
        return control.getData(NAME) instanceof String name ? name : control.getClass().getSimpleName();
    }

    private static int darker(int rgb) {
        int r = (rgb >> 16 & 0xFF) * 7 / 10;
        int g = (rgb >> 8 & 0xFF) * 7 / 10;
        int b = (rgb & 0xFF) * 7 / 10;
        return r << 16 | g << 8 | b;
    }

    /**
     * A demo box : a canvas with a fixed preferred size (the hints of {@code computeSize} when given), its color, a
     * darker border and its name centered, painted with a {@code GC}. {@code Canvas} is one of the two widget classes
     * that SWT lets subclass, with {@code Composite}.
     */
    static final class Box extends Canvas {

        private final String name;
        private final int width;
        private final int height;
        private final int rgb;

        Box(Composite parent, String name, int width, int height, int rgb) {
            super(parent, SWT.NO_FOCUS);
            this.name = name;
            this.width = width;
            this.height = height;
            this.rgb = rgb;
            setData(NAME, name);
            setBackground(SwtKit.color(rgb));
            addListener(SWT.Paint, event -> paint(event.gc));
        }

        @Override
        public Point computeSize(int wHint, int hHint, boolean changed) {
            checkWidget();
            return new Point(wHint == SWT.DEFAULT ? width : wHint, hHint == SWT.DEFAULT ? height : hHint);
        }

        private void paint(GC gc) {
            Rectangle area = getClientArea();
            gc.setForeground(SwtKit.color(darker(rgb)));
            gc.drawRectangle(0, 0, area.width - 1, area.height - 1);
            gc.setFont(SwtKit.font(SWT.NORMAL, 8));
            gc.setForeground(SwtKit.color(0x212121));
            Point extent = gc.textExtent(name);
            gc.drawText(name, (area.width - extent.x) / 2, (area.height - extent.y) / 2, true);
        }
    }
}
