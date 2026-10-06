package io.quarkiverse.desktop.showcase.swt.pages.widgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.CoolBar;
import org.eclipse.swt.widgets.CoolItem;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ExpandBar;
import org.eclipse.swt.widgets.ExpandItem;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtMode;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;
import io.quarkiverse.desktop.showcase.swt.pages.widgets.StructuredIcons.Shape;

/**
 * Containers : {@code TabFolder} with the tabs on top and at the bottom ; {@code ToolBar} (flat with the text at the
 * right of the images, wrapped on two rows, vertical) with push, check, radio, drop-down and separator items, a control
 * in a separator, a disabled item and generated images ; {@code CoolBar} with three items wrapped on two rows ;
 * {@code ExpandBar} with expanded and collapsed items ; {@code Sash} in a {@code FormLayout} ; {@code Composite} and
 * {@code Group} borders ; five secondary {@code Shell}s of different styles (rendered as the extra snapshots
 * {@code --shell-trim}, {@code --dialog-trim}, {@code --tool}, {@code --no-trim} and {@code --on-top}, at the right of
 * the main window, without taking the focus), one of them with a menu bar : a menu bar is not rendered by
 * {@code Control.print}, its items, accelerators and styles are checked instead.
 * <p>
 * Native paths exercised on purpose. Windows : the {@code SysTabControl32} of comctl32 v6 ({@code TCITEM},
 * {@code TCM_GETITEMRECT} and its {@code RECT}, {@code TCS_BOTTOM}) ; the {@code ToolbarWindow32} ({@code TBBUTTON},
 * {@code TBBUTTONINFO}, {@code TB_GETITEMRECT}, {@code TB_GETROWS}, the image lists of the enabled and of the disabled
 * images that SWT computes, the custom draw of the background, {@code NMCUSTOMDRAW}) ; the {@code ReBarWindow32} of
 * the cool bar ({@code REBARBANDINFO}, {@code RB_GETBANDINFO}) ; the expand bar, which SWT paints itself with the
 * explorer bar theme of the platform ({@code DrawThemeBackground}) ; the shells : the window styles of
 * {@code CreateWindowEx} ({@code WS_EX_TOOLWINDOW}, {@code WS_EX_TOPMOST}, {@code WS_POPUP}),
 * {@code AdjustWindowRectExForDpi} and {@code WM_NCCALCSIZE} for {@code computeTrim} (with a menu bar), the menu bar
 * ({@code CreateMenu}, {@code MENUITEMINFO}, 32-bit menu item bitmaps). GTK : {@code GtkNotebook},
 * {@code GtkToolbar}, the boxes and {@code GtkExpander}s of SWT, the window types and decorations of
 * {@code GtkWindow}, {@code GtkMenuBar} and its accelerator group. macOS : {@code NSTabView}, the views of SWT for the
 * tool bars, {@code NSWindow} style masks and levels, {@code NSMenu}.
 * <p>
 * Why it matters for a native executable : every notification of these controls ({@code WM_NOTIFY} with
 * {@code NMHDR}, {@code NMTOOLBAR}, {@code NMREBAR}...) comes through the window procedure of SWT, a JNI callback into
 * Java, and every struct is copied by the C side of SWT through JNI field accesses : a struct class missing from the
 * JNI metadata of the executable fails when the control is created, laid out or painted, not at build time.
 */
@Singleton
public class SwtContainersPage implements SwtPage {

    private static final int HALF_WIDTH = 494;
    private static final int TAB_HEIGHT = 150;
    private static final String[] TOP_TABS = { "General", "Images", "Advanced" };
    private static final String[] BOTTOM_TABS = { "One", "Two", "Three", "Four" };
    private static final Shape[] SHAPES = { Shape.CIRCLE, Shape.SQUARE, Shape.DIAMOND, Shape.TRIANGLE, Shape.FOLDER,
            Shape.PAGE };
    private static final int[] COLORS = { 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0xFFB300, 0x90A4AE, 0xE53935,
            0x00897B };

    private static final int TOOLBAR_BACKGROUND = 0xF3F4F6;
    private static final int WRAP_WIDTH = 300;
    private static final String[] WRAP_ITEMS = { "Cut", "Copy", "Paste", "Undo", "Redo", "Find", "Replace", "Help" };
    private static final String[] VERTICAL_ITEMS = { "Select", "Draw", "Fill", "Text" };
    private static final int COOL_WIDTH = 420;

    private static final int EXPAND_WIDTH = 280;
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 170;
    private static final int SASH_X = 120;
    private static final int SASH_Y = 70;
    private static final int SASH_SIZE = 6;
    private static final int SASH_COLOR = 0x90A4AE;
    private static final int BOX_WIDTH = 170;
    /** Width of the name column of the checks. */
    private static final int CHECK_NAME_WIDTH = 400;

    /** Width of the client area of the secondary shells (their height : the preferred height of their content). */
    private static final int SHELL_WIDTH = 360;
    /** Screen location of the first secondary shell (right of the main window), and the step to the next one. */
    private static final int SHELL_X = 1460;
    private static final int SHELL_Y = 40;
    private static final int SHELL_STEP = 280;
    /** How long a shell content may take to render the same pixels 3 times in a row. */
    private static final int STABLE_MILLIS = 3000;

    /** A secondary shell : the key of its extra snapshot, its style and the color of its content. */
    private record ShellSpec(String key, String label, int style, int background, String description) {
    }

    private static final List<ShellSpec> SHELLS = List.of(
            new ShellSpec("shell-trim", "SWT.SHELL_TRIM", SWT.SHELL_TRIM, 0xE3F2FD,
                    "Title bar, minimize, maximize and close buttons, resizable, and a menu bar (listed in the"
                            + " checks)."),
            new ShellSpec("dialog-trim", "SWT.DIALOG_TRIM", SWT.DIALOG_TRIM, 0xE8F5E9,
                    "Title bar, close button, fixed border : a dialog, with a default button."),
            new ShellSpec("tool", "SWT.TOOL | SWT.TITLE | SWT.CLOSE", SWT.TOOL | SWT.TITLE | SWT.CLOSE, 0xFFF3E0,
                    "A tool window : a smaller title bar, no task bar button."),
            new ShellSpec("no-trim", "SWT.NO_TRIM", SWT.NO_TRIM, 0xF3E5F5,
                    "No title bar, no border : a splash screen or a custom popup."),
            new ShellSpec("on-top", "SWT.ON_TOP | SWT.BORDER", SWT.ON_TOP | SWT.BORDER, 0xFFFDE7,
                    "Above the other windows, a thin border, no title bar."));

    // per build state (one content at a time)
    private State state;

    private static final class State {
        ChecksTable checks;
        final List<Integer> expandHeights = new ArrayList<>();
        final List<Point> shellSizes = new ArrayList<>();
        TabFolder topTabs;
        TabFolder bottomTabs;
        ToolBar rightBar;
        Combo zoom;
        ToolBar wrapBar;
        ToolBar verticalBar;
        CoolBar coolBar;
        ExpandBar expandBar;
        Composite sashPanel;
        Sash verticalSash;
        Sash horizontalSash;
        final List<Composite> boxes = new ArrayList<>();
        final List<Shell> shells = new ArrayList<>();
        final List<Composite> shellContents = new ArrayList<>();
        Shell owner;
        Menu menuBar;
    }

    @Override
    public String id() {
        return "swt-containers";
    }

    @Override
    public String title() {
        return "Containers";
    }

    @Override
    public String category() {
        return SwtCategories.WIDGETS;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Control build(Composite parent) {
        State s = new State();
        state = s;
        Composite page = SwtKit.page(parent, 12);
        StructuredIcons.Cache icons = new StructuredIcons.Cache(parent.getDisplay());
        page.addListener(SWT.Dispose, event -> icons.dispose());
        SwtKit.text(page, "The native containers of the platform : tab folders with the tabs on top and at the bottom,"
                + " tool bars (flat with the text at the right, wrapped on two rows, vertical), a cool bar wrapped"
                + " before its third item, an expand bar, sashes in a FormLayout, and the borders of composites and"
                + " groups. Five secondary shells of different styles are shown at the right of the main window"
                + " (without taking the focus) : their contents are the extra snapshots of the page, and the menu bar"
                + " of the SHELL_TRIM shell, which Control.print does not render, is listed in the checks.",
                SwtKit.TEXT_WIDTH);

        Composite tabs = SwtKit.row(page, 12);
        s.topTabs = tabFolder(labeled(tabs, "TabFolder : SWT.TOP, the second tab selected"), SWT.TOP, TOP_TABS, 1,
                icons);
        s.bottomTabs = tabFolder(labeled(tabs, "TabFolder : SWT.BOTTOM, the third tab selected"), SWT.BOTTOM,
                BOTTOM_TABS, 2, icons);

        s.rightBar = rightToolBar(labeled(page, "ToolBar : SWT.FLAT | SWT.RIGHT, push, check, radio, drop-down,"
                + " disabled and separator items, a Combo in a separator"), s, icons);

        Composite bars = SwtKit.row(page, 12);
        s.wrapBar = wrapToolBar(labeled(bars, "ToolBar : SWT.FLAT | SWT.WRAP, 300 wide"), icons);
        s.verticalBar = verticalToolBar(labeled(bars, "SWT.VERTICAL"), icons);
        s.coolBar = coolBar(labeled(bars, "CoolBar : three items, wrapped before the third"), icons);

        Composite boxes = SwtKit.row(page, 12);
        s.expandBar = expandBar(labeled(boxes, "ExpandBar : two items expanded"), s, icons);
        sashes(labeled(boxes, "Sash : vertical and horizontal"), s);
        borders(labeled(boxes, "Composite and Group borders"), s);

        s.checks = ChecksTable.table(page, "Checks", List.of(Check.info("state", "pending")), CHECK_NAME_WIDTH,
                ChecksTable.WIDTH);

        shells(parent.getShell(), s, icons);
        if (!SwtMode.snapshot()) {
            // the snapshot runs call ready() : run the checks for the user too
            ready(page);
        }
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        State s = state;
        // the secondary shells shown and painted, the layouts done
        return SwtSnapshots.settle(SwtSnapshots.SETTLE_MILLIS).thenAccept(v -> {
            if (!s.checks.isDisposed()) {
                List<Check> checks = new ArrayList<>(widgetChecks(s));
                checks.addAll(otherChecks(s));
                s.checks.setChecks(checks);
            }
        });
    }

    /**
     * The contents of the secondary shells (not their trim and menu bar, which depend on the window manager), then the
     * shells are disposed. Each content is rendered once it renders the same pixels 3 times in a row
     * ({@link UiStages#stable}) : the pixels of a shell may lag behind its paint (a wrapped label then shows its first
     * lines only), whether they are copied from the window ({@link SwtSnapshots#render} on Windows) or printed.
     */
    @Override
    public CompletionStage<Map<String, ImageData>> extraSnapshots(Control content) {
        State s = state;
        Map<String, ImageData> images = new LinkedHashMap<>();
        CompletionStage<Void> chain = SwtSnapshots.settle(SwtSnapshots.SETTLE_MILLIS);
        for (int i = 0; i < SHELLS.size(); i++) {
            Composite shellContent = s.shellContents.get(i);
            String key = SHELLS.get(i).key();
            chain = chain.thenCompose(v -> UiStages.stable(shellContent, STABLE_MILLIS))
                    .thenRun(() -> images.put(key, SwtSnapshots.render(shellContent)));
        }
        return chain.handle((v, error) -> {
            disposeShells(s);
            if (error != null) {
                throw new CompletionException(error);
            }
            return images;
        });
    }

    @Override
    public void dispose(Control content) {
        if (state != null) {
            disposeShells(state);
        }
        state = null;
    }

    private static void disposeShells(State s) {
        for (Shell shell : s.shells) {
            if (!shell.isDisposed()) {
                shell.dispose();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ widgets

    /**
     * A column with a bold caption, for a widget below it.
     */
    private static Composite labeled(Composite parent, String caption) {
        Composite column = SwtKit.column(parent, 4);
        SwtKit.text(column, caption, SwtKit.font(SWT.BOLD, SwtKit.TEXT_POINTS), SwtKit.TEXT_COLOR, 0);
        return column;
    }

    private static TabFolder tabFolder(Composite parent, int style, String[] titles, int selected,
            StructuredIcons.Cache icons) {
        TabFolder folder = new TabFolder(parent, style);
        for (int i = 0; i < titles.length; i++) {
            TabItem item = new TabItem(folder, SWT.NONE);
            item.setText(titles[i]);
            item.setImage(icons.get(SHAPES[i], COLORS[i], 16));
            item.setToolTipText("Tab " + titles[i]);
            Composite content = new Composite(folder, SWT.NONE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 10;
            layout.marginHeight = 10;
            content.setLayout(layout);
            SwtKit.text(content, "The content of the tab " + titles[i] + " : a Composite, the control of its TabItem,"
                    + " shown when the tab is selected.", 440);
            Button option = new Button(content, SWT.CHECK);
            option.setText("Option of " + titles[i]);
            option.setSelection(i % 2 == 0);
            Button choice = new Button(content, SWT.RADIO);
            choice.setText("Choice of " + titles[i]);
            choice.setSelection(true);
            item.setControl(content);
        }
        folder.setSelection(selected);
        SwtKit.size(folder, HALF_WIDTH, TAB_HEIGHT);
        return folder;
    }

    private static ToolBar rightToolBar(Composite parent, State s, StructuredIcons.Cache icons) {
        ToolBar bar = new ToolBar(parent, SWT.FLAT | SWT.RIGHT);
        bar.setBackground(SwtKit.color(TOOLBAR_BACKGROUND));
        toolItem(bar, SWT.PUSH, "New", icons.get(Shape.PAGE, 0xFFFFFF, 16));
        toolItem(bar, SWT.PUSH, "Open", icons.get(Shape.FOLDER, COLORS[4], 16));
        new ToolItem(bar, SWT.SEPARATOR);
        toolItem(bar, SWT.CHECK, "Bold", icons.get(Shape.SQUARE, COLORS[0], 16)).setSelection(true);
        toolItem(bar, SWT.CHECK, "Italic", icons.get(Shape.SQUARE, COLORS[3], 16));
        new ToolItem(bar, SWT.SEPARATOR);
        toolItem(bar, SWT.RADIO, "Left", icons.get(Shape.TRIANGLE, COLORS[1], 16)).setSelection(true);
        toolItem(bar, SWT.RADIO, "Center", icons.get(Shape.TRIANGLE, COLORS[2], 16));
        toolItem(bar, SWT.RADIO, "Right", icons.get(Shape.TRIANGLE, COLORS[7], 16));
        new ToolItem(bar, SWT.SEPARATOR);
        ToolItem zoom = toolItem(bar, SWT.DROP_DOWN, "Zoom", icons.get(Shape.CIRCLE, COLORS[0], 16));
        zoom.addListener(SWT.Selection, event -> {
            if (event.detail == SWT.ARROW) {
                dropDown(bar, zoom);
            }
        });
        // disabled : SWT computes the gray image (the disabled image list of the tool bar)
        toolItem(bar, SWT.PUSH, "Print", icons.get(Shape.DIAMOND, COLORS[6], 16)).setEnabled(false);
        ToolItem holder = new ToolItem(bar, SWT.SEPARATOR);
        s.zoom = new Combo(bar, SWT.READ_ONLY);
        s.zoom.setItems("50 %", "100 %", "200 %");
        s.zoom.select(1);
        s.zoom.pack();
        holder.setWidth(s.zoom.getSize().x);
        holder.setControl(s.zoom);
        return bar;
    }

    private static ToolItem toolItem(ToolBar bar, int style, String text, Image image) {
        ToolItem item = new ToolItem(bar, style);
        item.setText(text);
        item.setImage(image);
        item.setToolTipText(text);
        return item;
    }

    /**
     * The menu of the drop-down item (interactive only : the arrow of the item clicked).
     */
    private static void dropDown(ToolBar bar, ToolItem item) {
        Menu menu = new Menu(bar.getShell(), SWT.POP_UP);
        for (String text : new String[] { "50 %", "100 %", "200 %" }) {
            new MenuItem(menu, SWT.PUSH).setText(text);
        }
        Rectangle bounds = item.getBounds();
        menu.setLocation(bar.toDisplay(bounds.x, bounds.y + bounds.height));
        menu.addListener(SWT.Hide, event -> bar.getDisplay().asyncExec(menu::dispose));
        menu.setVisible(true);
    }

    private static ToolBar wrapToolBar(Composite parent, StructuredIcons.Cache icons) {
        ToolBar bar = new ToolBar(parent, SWT.FLAT | SWT.WRAP | SWT.BORDER);
        for (int i = 0; i < WRAP_ITEMS.length; i++) {
            toolItem(bar, SWT.PUSH, WRAP_ITEMS[i], icons.get(SHAPES[i % SHAPES.length], COLORS[i], 24));
        }
        SwtKit.size(bar, WRAP_WIDTH, SWT.DEFAULT);
        return bar;
    }

    private static ToolBar verticalToolBar(Composite parent, StructuredIcons.Cache icons) {
        ToolBar bar = new ToolBar(parent, SWT.FLAT | SWT.VERTICAL | SWT.BORDER);
        for (int i = 0; i < VERTICAL_ITEMS.length; i++) {
            ToolItem item = new ToolItem(bar, SWT.RADIO);
            item.setImage(icons.get(SHAPES[i], COLORS[i + 2], 16));
            item.setToolTipText(VERTICAL_ITEMS[i]);
            item.setSelection(i == 1);
        }
        new ToolItem(bar, SWT.SEPARATOR);
        ToolItem clear = new ToolItem(bar, SWT.PUSH);
        clear.setImage(icons.get(Shape.CIRCLE, COLORS[6], 16));
        clear.setToolTipText("Clear");
        return bar;
    }

    private static CoolBar coolBar(Composite parent, StructuredIcons.Cache icons) {
        CoolBar cool = new CoolBar(parent, SWT.FLAT | SWT.BORDER);
        for (int i = 0; i < 3; i++) {
            ToolBar bar = new ToolBar(cool, SWT.FLAT | (i == 2 ? SWT.RIGHT : SWT.NONE));
            for (int j = 0; j < 3; j++) {
                ToolItem item = new ToolItem(bar, SWT.PUSH);
                item.setImage(icons.get(SHAPES[(i + j) % SHAPES.length], COLORS[(i * 3 + j) % COLORS.length], 16));
                item.setToolTipText("Item " + (i + 1) + "." + (j + 1));
                if (i == 2) {
                    item.setText("Band 3." + (j + 1));
                }
            }
            bar.pack();
            Point size = bar.getSize();
            CoolItem item = new CoolItem(cool, SWT.NONE);
            item.setControl(bar);
            Point preferred = item.computeSize(size.x, size.y);
            item.setPreferredSize(preferred);
            item.setMinimumSize(size);
            item.setSize(preferred);
        }
        cool.setWrapIndices(new int[] { 2 });
        SwtKit.size(cool, COOL_WIDTH, SWT.DEFAULT);
        return cool;
    }

    private static ExpandBar expandBar(Composite parent, State s, StructuredIcons.Cache icons) {
        ExpandBar bar = new ExpandBar(parent, SWT.V_SCROLL);
        bar.setSpacing(6);
        expandItem(bar, "Display", icons.get(Shape.SQUARE, COLORS[0], 16), true, s, content -> {
            SwtKit.text(content, "Zoom : 100 %");
            check(content, "Show the grid", true);
            check(content, "Snap to the grid", false);
        });
        expandItem(bar, "Filters", icons.get(Shape.TRIANGLE, COLORS[1], 16), false, s, content -> {
            check(content, "Hidden files", false);
            check(content, "Generated sources", true);
        });
        expandItem(bar, "Details", icons.get(Shape.CIRCLE, COLORS[2], 16), true, s, content -> {
            SwtKit.text(content, "An ExpandItem shows its control below its header when it is expanded.",
                    EXPAND_WIDTH - 60);
        });
        SwtKit.size(bar, EXPAND_WIDTH, SWT.DEFAULT);
        return bar;
    }

    /**
     * An item of the expand bar : its control at its preferred height.
     */
    private static void expandItem(ExpandBar bar, String text, Image image, boolean expanded, State s,
            Consumer<Composite> filler) {
        ExpandItem item = new ExpandItem(bar, SWT.NONE);
        item.setText(text);
        item.setImage(image);
        Composite content = new Composite(bar, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 6;
        layout.verticalSpacing = 4;
        content.setLayout(layout);
        filler.accept(content);
        item.setControl(content);
        int height = content.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        item.setHeight(height);
        item.setExpanded(expanded);
        s.expandHeights.add(height);
    }

    private static Button check(Composite parent, String text, boolean selected) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(text);
        button.setSelection(selected);
        return button;
    }

    /**
     * A panel split by a vertical sash, its right part split by a horizontal sash : the sashes move when they are
     * dragged (interactive only).
     */
    private static void sashes(Composite parent, State s) {
        Composite panel = new Composite(parent, SWT.BORDER);
        panel.setLayout(new FormLayout());
        Label left = pane(panel, "Left : attached to the vertical sash", 0xE3F2FD);
        Sash vertical = new Sash(panel, SWT.VERTICAL);
        vertical.setBackground(SwtKit.color(SASH_COLOR));
        Label topRight = pane(panel, "Top right", 0xE8F5E9);
        Sash horizontal = new Sash(panel, SWT.HORIZONTAL | SWT.SMOOTH);
        horizontal.setBackground(SwtKit.color(SASH_COLOR));
        Label bottomRight = pane(panel, "Bottom right : attached to both sashes", 0xFFF3E0);

        FormData verticalData = new FormData();
        verticalData.left = new FormAttachment(0, SASH_X);
        verticalData.top = new FormAttachment(0);
        verticalData.bottom = new FormAttachment(100);
        verticalData.width = SASH_SIZE;
        vertical.setLayoutData(verticalData);
        left.setLayoutData(attach(new FormAttachment(0), new FormAttachment(vertical), new FormAttachment(0),
                new FormAttachment(100)));
        FormData horizontalData = new FormData();
        horizontalData.left = new FormAttachment(vertical);
        horizontalData.right = new FormAttachment(100);
        horizontalData.top = new FormAttachment(0, SASH_Y);
        horizontalData.height = SASH_SIZE;
        horizontal.setLayoutData(horizontalData);
        topRight.setLayoutData(attach(new FormAttachment(vertical), new FormAttachment(100), new FormAttachment(0),
                new FormAttachment(horizontal)));
        bottomRight.setLayoutData(attach(new FormAttachment(vertical), new FormAttachment(100),
                new FormAttachment(horizontal), new FormAttachment(100)));

        vertical.addListener(SWT.Selection, event -> {
            if (event.detail != SWT.DRAG) {
                verticalData.left = new FormAttachment(0, event.x);
                panel.layout();
            }
        });
        horizontal.addListener(SWT.Selection, event -> {
            if (event.detail != SWT.DRAG) {
                horizontalData.top = new FormAttachment(0, event.y);
                panel.layout();
            }
        });
        SwtKit.size(panel, PANEL_WIDTH, PANEL_HEIGHT);
        s.sashPanel = panel;
        s.verticalSash = vertical;
        s.horizontalSash = horizontal;
    }

    private static Label pane(Composite parent, String text, int rgb) {
        Label label = new Label(parent, SWT.WRAP | SWT.CENTER);
        label.setText(text);
        label.setFont(SwtKit.font(SWT.NORMAL, 8));
        label.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
        label.setBackground(SwtKit.color(rgb));
        return label;
    }

    private static FormData attach(FormAttachment left, FormAttachment right, FormAttachment top,
            FormAttachment bottom) {
        FormData data = new FormData();
        data.left = left;
        data.right = right;
        data.top = top;
        data.bottom = bottom;
        return data;
    }

    private static void borders(Composite parent, State s) {
        Composite grid = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, true);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 8;
        grid.setLayout(layout);

        Composite plain = box(grid, SWT.NONE, "SWT.NONE");
        plain.setBackground(SwtKit.color(0xECEFF1));
        Composite bordered = box(grid, SWT.BORDER, "SWT.BORDER");
        Composite outer = new Composite(grid, SWT.BORDER);
        FillLayout fill = new FillLayout();
        fill.marginWidth = 8;
        fill.marginHeight = 8;
        outer.setLayout(fill);
        SwtKit.size(outer, BOX_WIDTH, SWT.DEFAULT);
        Composite inner = new Composite(outer, SWT.BORDER);
        inner.setBackground(SwtKit.color(0xFFF8E1));
        inner.setLayout(new FillLayout());
        boxLabel(inner, "BORDER in BORDER", inner.getBorderWidth());
        Group group = new Group(grid, SWT.SHADOW_ETCHED_IN);
        group.setText("Group");
        group.setLayout(new FillLayout());
        boxLabel(group, "SHADOW_ETCHED_IN", group.getBorderWidth());
        SwtKit.size(group, BOX_WIDTH, SWT.DEFAULT);
        s.boxes.addAll(List.of(plain, bordered, outer, inner, group));
    }

    private static Composite box(Composite parent, int style, String text) {
        Composite box = new Composite(parent, style);
        box.setLayout(new FillLayout());
        boxLabel(box, text, box.getBorderWidth());
        SwtKit.size(box, BOX_WIDTH, SWT.DEFAULT);
        return box;
    }

    private static void boxLabel(Composite parent, String text, int borderWidth) {
        Label label = new Label(parent, SWT.WRAP | SWT.CENTER);
        label.setFont(SwtKit.font(SWT.NORMAL, 8));
        label.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
        label.setText(text + "\ngetBorderWidth() = " + borderWidth);
    }

    // ------------------------------------------------------------------------------------------------------ shells

    /**
     * The secondary shells, owned by the main window, at the right of it (in the client area of the primary monitor),
     * their client areas {@link #SHELL_WIDTH} wide and as high as their contents prefer ({@code computeTrim}) : shown
     * without taking the focus ({@code setVisible(true)}, not {@code open()}).
     */
    private static void shells(Shell owner, State s, StructuredIcons.Cache icons) {
        s.owner = owner;
        Rectangle area = owner.getDisplay().getPrimaryMonitor().getClientArea();
        for (int i = 0; i < SHELLS.size(); i++) {
            ShellSpec spec = SHELLS.get(i);
            Shell shell = new Shell(owner, spec.style());
            shell.setText(spec.label());
            shell.setLayout(new FillLayout());
            if (i == 0) {
                s.menuBar = menuBar(shell, icons);
                shell.setMenuBar(s.menuBar);
            }
            Composite content = new Composite(shell, SWT.NONE);
            content.setBackground(SwtKit.color(spec.background()));
            content.setBackgroundMode(SWT.INHERIT_DEFAULT);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 12;
            layout.marginHeight = 10;
            layout.verticalSpacing = 6;
            content.setLayout(layout);
            SwtKit.text(content, spec.label(), SwtKit.font(SWT.BOLD, 10), SwtKit.TEXT_COLOR, SHELL_WIDTH - 24);
            SwtKit.text(content, spec.description(), SwtKit.font(SWT.NORMAL, SwtKit.TEXT_POINTS), SwtKit.TEXT_COLOR,
                    SHELL_WIDTH - 24);
            Composite buttons = SwtKit.row(content, 8);
            Button ok = new Button(buttons, SWT.PUSH);
            ok.setText("OK");
            SwtKit.size(ok, 80, SWT.DEFAULT);
            Button cancel = new Button(buttons, SWT.PUSH);
            cancel.setText("Cancel");
            SwtKit.size(cancel, 80, SWT.DEFAULT);
            if (spec.key().equals("dialog-trim")) {
                shell.setDefaultButton(ok);
            }
            Point client = new Point(SHELL_WIDTH, content.computeSize(SHELL_WIDTH, SWT.DEFAULT).y);
            s.shellSizes.add(client);
            Rectangle trim = shell.computeTrim(0, 0, client.x, client.y);
            int x = Math.max(area.x, Math.min(SHELL_X, area.x + area.width - trim.width - 20));
            int y = Math.max(area.y, Math.min(SHELL_Y + i * SHELL_STEP, area.y + area.height - trim.height - 20));
            shell.setBounds(x, y, trim.width, trim.height);
            shell.layout(true, true);
            s.shells.add(shell);
            s.shellContents.add(content);
        }
        for (Shell shell : s.shells) {
            shell.setVisible(true);
        }
    }

    private static Menu menuBar(Shell shell, StructuredIcons.Cache icons) {
        Menu bar = new Menu(shell, SWT.BAR);
        Menu file = cascade(bar, "&File");
        menuItem(file, SWT.PUSH, "&New\tCtrl+N", SWT.MOD1 | 'N').setImage(icons.get(Shape.PAGE, 0xFFFFFF, 16));
        menuItem(file, SWT.PUSH, "&Open...\tCtrl+O", SWT.MOD1 | 'O')
                .setImage(icons.get(Shape.FOLDER, COLORS[4], 16));
        Menu recent = cascade(file, "Open &Recent");
        menuItem(recent, SWT.PUSH, "quarkus-run.jar", 0);
        menuItem(recent, SWT.PUSH, "report.json", 0);
        new MenuItem(file, SWT.SEPARATOR);
        menuItem(file, SWT.PUSH, "&Close\tCtrl+W", SWT.MOD1 | 'W');
        Menu edit = cascade(bar, "&Edit");
        menuItem(edit, SWT.PUSH, "&Undo\tCtrl+Z", SWT.MOD1 | 'Z');
        menuItem(edit, SWT.PUSH, "&Redo\tCtrl+Shift+Z", SWT.MOD1 | SWT.MOD2 | 'Z');
        new MenuItem(edit, SWT.SEPARATOR);
        menuItem(edit, SWT.PUSH, "Cu&t\tCtrl+X", SWT.MOD1 | 'X');
        menuItem(edit, SWT.PUSH, "&Copy\tCtrl+C", SWT.MOD1 | 'C');
        menuItem(edit, SWT.PUSH, "&Paste\tCtrl+V", SWT.MOD1 | 'V').setEnabled(false);
        Menu view = cascade(bar, "&View");
        menuItem(view, SWT.CHECK, "&Status bar", 0).setSelection(true);
        menuItem(view, SWT.CHECK, "&Tool bar", 0);
        new MenuItem(view, SWT.SEPARATOR);
        menuItem(view, SWT.RADIO, "S&mall icons", 0);
        menuItem(view, SWT.RADIO, "&Large icons", 0).setSelection(true);
        menuItem(view, SWT.RADIO, "&Details", 0);
        Menu help = cascade(bar, "&Help");
        menuItem(help, SWT.PUSH, "&Contents\tF1", SWT.F1);
        menuItem(help, SWT.PUSH, "&About", 0);
        return bar;
    }

    private static Menu cascade(Menu parent, String text) {
        MenuItem item = new MenuItem(parent, SWT.CASCADE);
        item.setText(text);
        Menu menu = new Menu(parent.getShell(), SWT.DROP_DOWN);
        item.setMenu(menu);
        return menu;
    }

    private static MenuItem menuItem(Menu menu, int style, String text, int accelerator) {
        MenuItem item = new MenuItem(menu, style);
        item.setText(text);
        if (accelerator != 0) {
            item.setAccelerator(accelerator);
        }
        return item;
    }

    // ------------------------------------------------------------------------------------------------------ checks

    private static List<Check> widgetChecks(State s) {
        List<Check> checks = new ArrayList<>();
        TabFolder top = s.topTabs;
        TabFolder bottom = s.bottomTabs;
        checks.add(SwtChecks.expect("TabFolder SWT.TOP : items, getSelectionIndex()", "General Images Advanced 1",
                () -> tabTexts(top) + " " + top.getSelectionIndex()));
        checks.add(SwtChecks.expect("TabFolder SWT.BOTTOM : items, getSelectionIndex()", "One Two Three Four 2",
                () -> tabTexts(bottom) + " " + bottom.getSelectionIndex()));
        checks.add(SwtChecks.expect("TabFolder : visible tab controls (TOP ; BOTTOM)",
                "false true false ; false false true false", () -> visibleControls(top) + " ; "
                        + visibleControls(bottom)));
        checks.add(SwtChecks.expect("TabFolder : bounds of the selected control = getClientArea() (TOP, BOTTOM)",
                "true true", () -> top.getSelection()[0].getControl().getBounds().equals(top.getClientArea()) + " "
                        + bottom.getSelection()[0].getControl().getBounds().equals(bottom.getClientArea())));
        checks.add(SwtChecks.expect("TabFolder : tabs above the client area (TOP), below it (BOTTOM)", "true true",
                () -> Arrays.stream(top.getItems()).allMatch(i -> bottomOf(i.getBounds()) <= clientArea(top).y)
                        + " " + Arrays.stream(bottom.getItems()).allMatch(i -> i.getBounds().y >= bottomOf(
                                clientArea(bottom)))));
        checks.add(SwtChecks.info("TabFolder : getClientArea() (TOP ; BOTTOM)",
                () -> SwtChecks.rect(top.getClientArea()) + " ; " + SwtChecks.rect(bottom.getClientArea())));
        checks.add(SwtChecks.info("TabItem.getBounds() of the selected tabs (TCM_GETITEMRECT)",
                () -> SwtChecks.rect(top.getItem(1).getBounds()) + " ; "
                        + SwtChecks.rect(bottom.getItem(2).getBounds())));

        ToolBar right = s.rightBar;
        checks.add(SwtChecks.expect("ToolBar SWT.RIGHT : item styles", "PUSH PUSH SEPARATOR CHECK CHECK SEPARATOR"
                + " RADIO RADIO RADIO SEPARATOR DROP_DOWN PUSH SEPARATOR", () -> Arrays.stream(right.getItems())
                        .map(i -> toolStyle(i.getStyle())).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("ToolBar SWT.RIGHT : selected items, disabled items", "Bold Left ; Print",
                () -> toolTexts(right, ToolItem::getSelection) + " ; " + toolTexts(right, i -> !i.getEnabled())));
        checks.add(SwtChecks.expect("ToolBar SWT.RIGHT : getItemCount(), getRowCount()", "13 1",
                () -> right.getItemCount() + " " + right.getRowCount()));
        checks.add(SwtChecks.expect("ToolBar SWT.RIGHT : the Combo over its separator, as wide", true, () -> {
            ToolItem holder = right.getItem(right.getItemCount() - 1);
            Rectangle item = holder.getBounds();
            Rectangle combo = s.zoom.getBounds();
            return holder.getControl() == s.zoom && holder.getWidth() == combo.width && combo.x == item.x;
        }));
        checks.add(SwtChecks.expect("ToolBar SWT.RIGHT : text at the right of the image (wider than high)", true,
                () -> {
                    Rectangle bounds = right.getItem(0).getBounds();
                    return bounds.width > bounds.height + 8;
                }));
        checks.add(SwtChecks.expect("ToolBar SWT.RIGHT : getItem(Point) at the center of Italic", "Italic", () -> {
            Rectangle bounds = right.getItem(4).getBounds();
            ToolItem hit = right.getItem(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
            return hit == null ? "none" : hit.getText();
        }));
        checks.add(SwtChecks.info("ToolBar SWT.RIGHT : bounds of the DROP_DOWN item (TB_GETITEMRECT)",
                () -> SwtChecks.rect(right.getItem(10).getBounds())));
        ToolBar wrap = s.wrapBar;
        // GTK : "On GTK, toolbars cannot wrap", getRowCount() is always 1 (ToolBar.getRowCount, ToolBar.java:409-413 of
        // SWT GTK 3.132.0) : SWT.WRAP shows the overflow arrow of the GtkToolbar instead (ToolBar.java:193)
        checks.add(SwtChecks.expect("ToolBar SWT.WRAP : more than one row (TB_GETROWS)",
                SwtMode.pick(true, true, false), () -> wrap.getRowCount() > 1));
        checks.add(SwtChecks.info("ToolBar SWT.WRAP : getRowCount(), getSize()",
                () -> wrap.getRowCount() + " " + SwtChecks.size(wrap.getSize())));
        checks.add(SwtChecks.expect("ToolBar SWT.WRAP : text below the image (higher than 24 + text)", true,
                () -> wrap.getItem(0).getBounds().height > 24 + 8));
        ToolBar vertical = s.verticalBar;
        checks.add(SwtChecks.expect("ToolBar SWT.VERTICAL : items stacked (same x, growing y)", true, () -> {
            ToolItem[] items = vertical.getItems();
            for (int i = 1; i < items.length; i++) {
                if (items[i].getBounds().x != items[0].getBounds().x
                        || items[i].getBounds().y <= items[i - 1].getBounds().y) {
                    return false;
                }
            }
            return true;
        }));
        checks.add(SwtChecks.expect("ToolBar SWT.VERTICAL : selected radio item", "Draw",
                () -> Arrays.stream(vertical.getItems()).filter(ToolItem::getSelection).map(ToolItem::getToolTipText)
                        .collect(Collectors.joining(", "))));

        CoolBar cool = s.coolBar;
        checks.add(SwtChecks.expect("CoolBar : getItemCount(), getItemOrder(), getWrapIndices()", "3 [0, 1, 2] [2]",
                () -> cool.getItemCount() + " " + Arrays.toString(cool.getItemOrder()) + " "
                        + Arrays.toString(cool.getWrapIndices())));
        checks.add(SwtChecks.expect("CoolBar : rows of the items (distinct y), getLocked()", "2 false",
                () -> Arrays.stream(cool.getItems()).mapToInt(i -> i.getBounds().y).distinct().count() + " "
                        + cool.getLocked()));
        checks.add(SwtChecks.expect("CoolBar : controls of the items (tool bars)", "3 3 3",
                () -> Arrays.stream(cool.getItems()).map(i -> String.valueOf(((ToolBar) i.getControl())
                        .getItemCount())).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.info("CoolBar : getItemSizes() (RB_GETBANDINFO)",
                () -> Arrays.stream(cool.getItemSizes()).map(SwtChecks::size).collect(Collectors.joining(" "))));
        return checks;
    }

    private static List<Check> otherChecks(State s) {
        List<Check> checks = new ArrayList<>();
        ExpandBar expand = s.expandBar;
        checks.add(SwtChecks.expect("ExpandBar : getItemCount(), getSpacing()", "3 6",
                () -> expand.getItemCount() + " " + expand.getSpacing()));
        checks.add(SwtChecks.expect("ExpandBar : getExpanded() of the items", "true false true",
                () -> Arrays.stream(expand.getItems()).map(i -> String.valueOf(i.getExpanded()))
                        .collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("ExpandBar : getHeight() of the items (the preferred heights of their controls)",
                s.expandHeights.stream().map(String::valueOf).collect(Collectors.joining(" ")),
                () -> Arrays.stream(expand.getItems()).map(i -> String.valueOf(i.getHeight()))
                        .collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("ExpandBar : visible controls (the expanded items)", "true false true",
                () -> Arrays.stream(expand.getItems()).map(i -> String.valueOf(i.getControl().isVisible()))
                        .collect(Collectors.joining(" "))));
        checks.add(SwtChecks.info("ExpandBar : getHeaderHeight(), getSize()",
                () -> expand.getItem(0).getHeaderHeight() + " " + SwtChecks.size(expand.getSize())));

        checks.add(SwtChecks.expect("Sash SWT.VERTICAL : bounds (left 120, width 6, full height)", true, () -> {
            Rectangle bounds = s.verticalSash.getBounds();
            Rectangle area = s.sashPanel.getClientArea();
            return bounds.x == SASH_X && bounds.y == 0 && bounds.width == SASH_SIZE && bounds.height == area.height;
        }));
        checks.add(SwtChecks.expect("Sash SWT.HORIZONTAL : bounds (top 70, height 6, right of the sash)", true,
                () -> {
                    Rectangle bounds = s.horizontalSash.getBounds();
                    Rectangle area = s.sashPanel.getClientArea();
                    return bounds.x == SASH_X + SASH_SIZE && bounds.y == SASH_Y && bounds.height == SASH_SIZE
                            && bounds.x + bounds.width == area.width;
                }));
        checks.add(SwtChecks.info("Sash : bounds of the sashes",
                () -> SwtChecks.rect(s.verticalSash.getBounds()) + " ; "
                        + SwtChecks.rect(s.horizontalSash.getBounds())));

        checks.add(SwtChecks.info("getBorderWidth() : NONE, BORDER, outer, inner, Group",
                () -> s.boxes.stream().map(b -> String.valueOf(b.getBorderWidth())).collect(Collectors.joining(" "))));
        // macOS : getBorderWidth() is 0 for every control (Control.getBorderWidth), and SWT.BORDER wraps the composite
        // in an NSScrollView with an NSBezelBorder : the bezel is trim (computeTrim), outside the client area, so only
        // the SWT.NONE box has size - client area = 2 x getBorderWidth()
        checks.add(SwtChecks.expect("Composite : getSize() - client area = 2 x getBorderWidth()",
                SwtMode.pick("true false false false", "true true true true", "true true true true"),
                () -> s.boxes.subList(0, 4).stream().map(b -> {
                    Point size = b.getSize();
                    Rectangle area = b.getClientArea();
                    int border = b.getBorderWidth();
                    return String.valueOf(size.x - area.width == 2 * border && size.y - area.height == 2 * border);
                }).collect(Collectors.joining(" "))));
        // where the border of macOS went : the trim of computeTrim (Scrollable.computeTrim, getClientArea), verified
        // on macOS only
        checks.add(SwtChecks.onlyOn(SwtMode.Os.MAC, SwtChecks.expect(
                "Composite : getSize() - client area = the trim of computeTrim(0, 0, 0, 0)", "true true true true",
                () -> s.boxes.subList(0, 4).stream().map(b -> {
                    Point size = b.getSize();
                    Rectangle area = b.getClientArea();
                    Rectangle trim = b.computeTrim(0, 0, 0, 0);
                    return String.valueOf(size.x - area.width == trim.width && size.y - area.height == trim.height);
                }).collect(Collectors.joining(" ")))));
        checks.add(SwtChecks.info("Group : getClientArea() in its getSize()",
                () -> SwtChecks.rect(s.boxes.get(4).getClientArea()) + " in "
                        + SwtChecks.size(s.boxes.get(4).getSize())));

        shellChecks(s, checks);
        menuChecks(s, checks);
        return checks;
    }

    private static void shellChecks(State s, List<Check> checks) {
        List<Shell> shells = s.shells;
        for (int i = 0; i < SHELLS.size(); i++) {
            Shell shell = shells.get(i);
            Point client = s.shellSizes.get(i);
            checks.add(SwtChecks.info("Shell " + SHELLS.get(i).label(), () -> "style " + shellStyle(shell.getStyle())
                    + " ; client " + SwtChecks.size(client) + " ; trim "
                    + SwtChecks.rect(shell.computeTrim(0, 0, client.x, client.y)) + " ; border "
                    + shell.getBorderWidth()));
        }
        // the client area asked to computeTrim is the one the shell gets
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "Shells : getClientArea() = the client area given to computeTrim", "true true true true true",
                () -> IntStream.range(0, shells.size()).mapToObj(i -> String.valueOf(shells.get(i).getClientArea()
                        .equals(new Rectangle(0, 0, s.shellSizes.get(i).x, s.shellSizes.get(i).y))))
                        .collect(Collectors.joining(" ")))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "Shell SWT.NO_TRIM : computeTrim() adds nothing", true, () -> {
                    Point client = s.shellSizes.get(3);
                    return shells.get(3).computeTrim(0, 0, client.x, client.y)
                            .equals(new Rectangle(0, 0, client.x, client.y));
                })));
        checks.add(SwtChecks.expect("Shells : isVisible(), owned by the main window", "true true",
                () -> shells.stream().allMatch(Shell::isVisible) + " "
                        + shells.stream().allMatch(sh -> sh.getParent() == s.owner)));
        checks.add(SwtChecks.expect("Shells : SWT.TOOL, SWT.ON_TOP and SWT.NO_TRIM in getStyle()", "true true true",
                () -> ((shells.get(2).getStyle() & SWT.TOOL) != 0) + " " + ((shells.get(4).getStyle() & SWT.ON_TOP)
                        != 0) + " " + ((shells.get(3).getStyle() & SWT.NO_TRIM) != 0)));
        // GTK : Shell.setVisible shows the shell without activating it (Shell.java:2901 of SWT GTK 3.132.0), and the
        // active shell is the one the window manager gives the focus to (Shell.gtk_focus_in_event,
        // Shell.java:1621-1625) : a window manager that focuses new windows activates them (the openbox of the Docker
        // image, focusNew), informational on Linux
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtMode.Os.MAC, SwtChecks.expect(
                "Shells : none took the focus (Display.getActiveShell())", false,
                () -> shells.contains(Display.getCurrent().getActiveShell()))));
        checks.add(SwtChecks.expect("Shell SWT.DIALOG_TRIM : getDefaultButton()", "OK",
                () -> shells.get(1).getDefaultButton().getText()));
    }

    private static void menuChecks(State s, List<Check> checks) {
        Menu bar = s.menuBar;
        Shell shell = s.shells.getFirst();
        checks.add(SwtChecks.expect("Menu bar : getMenuBar() of the SHELL_TRIM shell, SWT.BAR", "true true",
                () -> (shell.getMenuBar() == bar) + " " + ((bar.getStyle() & SWT.BAR) != 0)));
        checks.add(SwtChecks.expect("Menu bar : items", "File, Edit, View, Help",
                () -> Arrays.stream(bar.getItems()).map(i -> plain(i.getText())).collect(Collectors.joining(", "))));
        String[] menus = { "File", "Edit", "View", "Help" };
        String[] expected = {
                "New (PUSH, MOD1+N, image), Open... (PUSH, MOD1+O, image), Open Recent (CASCADE, 2 items), -,"
                        + " Close (PUSH, MOD1+W)",
                "Undo (PUSH, MOD1+Z), Redo (PUSH, MOD1+MOD2+Z), -, Cut (PUSH, MOD1+X), Copy (PUSH, MOD1+C),"
                        + " Paste (PUSH, MOD1+V, disabled)",
                "Status bar (CHECK, selected), Tool bar (CHECK), -, Small icons (RADIO), Large icons (RADIO,"
                        + " selected), Details (RADIO)",
                "Contents (PUSH, F1), About (PUSH)" };
        for (int i = 0; i < expected.length; i++) {
            int index = i;
            checks.add(SwtChecks.expect("Menu " + menus[i], expected[i],
                    () -> describe(bar.getItem(index).getMenu())));
        }
        checks.add(SwtChecks.expect("MenuItem.getText() of New (mnemonic, accelerator text)", "&New\\tCtrl+N",
                () -> bar.getItem(0).getMenu().getItem(0).getText().replace("\t", "\\t")));
        checks.add(SwtChecks.expect("Open Recent : getParentItem(), getParentMenu(), items", "Open Recent File"
                + " quarkus-run.jar, report.json", () -> {
                    Menu recent = bar.getItem(0).getMenu().getItem(2).getMenu();
                    return plain(recent.getParentItem().getText()) + " " + plain(recent.getParentMenu()
                            .getParentItem().getText()) + " " + Arrays.stream(recent.getItems())
                                    .map(MenuItem::getText).collect(Collectors.joining(", "));
                }));
        checks.add(SwtChecks.expect("Paste : getEnabled(), isEnabled() ; Edit : isVisible()", "false false ; false",
                () -> {
                    MenuItem paste = bar.getItem(1).getMenu().getItem(5);
                    return paste.getEnabled() + " " + paste.isEnabled() + " ; " + bar.getItem(1).getMenu()
                            .isVisible();
                }));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private static String tabTexts(TabFolder folder) {
        return Arrays.stream(folder.getItems()).map(TabItem::getText).collect(Collectors.joining(" "));
    }

    private static String visibleControls(TabFolder folder) {
        return Arrays.stream(folder.getItems()).map(i -> String.valueOf(i.getControl().isVisible()))
                .collect(Collectors.joining(" "));
    }

    /**
     * The client area of {@code folder} in its coordinates. GTK : getClientArea() is at 0,0,
     * TabFolder.getClientAreaInPixels forces x and y to 0 (SWT bug 454936, TabFolder.java:214-235 of SWT GTK 3.132.0) ;
     * the client widget, the page of the selected tab, is placed by the trim of computeTrim (its allocation,
     * TabFolder.computeTrimInPixels, TabFolder.java:191-211).
     */
    private static Rectangle clientArea(TabFolder folder) {
        Rectangle area = folder.getClientArea();
        if (SwtMode.isLinux()) {
            Rectangle trim = folder.computeTrim(0, 0, 0, 0);
            area = new Rectangle(area.x - trim.x, area.y - trim.y, area.width, area.height);
        }
        return area;
    }

    private static int bottomOf(Rectangle r) {
        return r.y + r.height;
    }

    private static String toolTexts(ToolBar bar, Predicate<ToolItem> filter) {
        return Arrays.stream(bar.getItems()).filter(i -> (i.getStyle() & SWT.SEPARATOR) == 0).filter(filter)
                .map(ToolItem::getText).collect(Collectors.joining(" "));
    }

    private static String toolStyle(int style) {
        return (style & SWT.PUSH) != 0 ? "PUSH" : (style & SWT.CHECK) != 0 ? "CHECK"
                : (style & SWT.RADIO) != 0 ? "RADIO" : (style & SWT.SEPARATOR) != 0 ? "SEPARATOR"
                        : (style & SWT.DROP_DOWN) != 0 ? "DROP_DOWN" : String.valueOf(style);
    }

    private static String shellStyle(int style) {
        int[] flags = { SWT.NO_TRIM, SWT.TITLE, SWT.CLOSE, SWT.MIN, SWT.MAX, SWT.RESIZE, SWT.BORDER, SWT.TOOL,
                SWT.ON_TOP };
        String[] names = { "NO_TRIM", "TITLE", "CLOSE", "MIN", "MAX", "RESIZE", "BORDER", "TOOL", "ON_TOP" };
        String text = IntStream.range(0, flags.length).filter(i -> (style & flags[i]) != 0).mapToObj(i -> names[i])
                .collect(Collectors.joining(" "));
        return text.isEmpty() ? "NONE" : text;
    }

    /**
     * The items of {@code menu} : text (no mnemonic, no accelerator text), style, accelerator, image, state.
     */
    private static String describe(Menu menu) {
        List<String> items = new ArrayList<>();
        for (MenuItem item : menu.getItems()) {
            int style = item.getStyle();
            if ((style & SWT.SEPARATOR) != 0) {
                items.add("-");
                continue;
            }
            List<String> details = new ArrayList<>();
            details.add((style & SWT.CASCADE) != 0 ? "CASCADE" : (style & SWT.CHECK) != 0 ? "CHECK"
                    : (style & SWT.RADIO) != 0 ? "RADIO" : "PUSH");
            if (item.getAccelerator() != 0) {
                details.add(accelerator(item.getAccelerator()));
            }
            if (item.getMenu() != null) {
                details.add(item.getMenu().getItemCount() + " items");
            }
            if (item.getImage() != null) {
                details.add("image");
            }
            if (item.getSelection()) {
                details.add("selected");
            }
            if (!item.getEnabled()) {
                details.add("disabled");
            }
            items.add(plain(item.getText()) + " (" + String.join(", ", details) + ")");
        }
        return String.join(", ", items);
    }

    /**
     * {@code MOD1+MOD2+Z}, {@code F1} : the modifiers of SWT (MOD1 is Ctrl, Command on macOS) and the key.
     */
    private static String accelerator(int accelerator) {
        List<String> parts = new ArrayList<>();
        if ((accelerator & SWT.MOD1) != 0) {
            parts.add("MOD1");
        }
        if ((accelerator & SWT.MOD2) != 0) {
            parts.add("MOD2");
        }
        if ((accelerator & SWT.MOD3) != 0) {
            parts.add("MOD3");
        }
        int key = accelerator & SWT.KEY_MASK;
        parts.add(key >= SWT.F1 && key <= SWT.F12 ? "F" + (key - SWT.F1 + 1) : String.valueOf((char) key));
        return String.join("+", parts);
    }

    /**
     * The text of a menu item without its mnemonic and its accelerator text.
     */
    private static String plain(String text) {
        int tab = text.indexOf('\t');
        return (tab >= 0 ? text.substring(0, tab) : text).replace("&&", "\u0000").replace("&", "")
                .replace("\u0000", "&");
    }
}
