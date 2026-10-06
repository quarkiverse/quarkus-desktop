package io.quarkiverse.desktop.showcase.swt.pages.widgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

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
 * Lists, tables and trees : {@code List} with single and multiple selection ; a {@code Table} with check boxes, a
 * header, grid lines, generated images in two columns, moved columns, a sort indicator, a multiple selection, a checked
 * and grayed item, item fonts and cell colors ; a virtual {@code Table} ({@code SWT.VIRTUAL}) of 100,000 rows, scrolled
 * to the middle once painted, whose {@code SetData} events are counted ; a {@code Tree} with columns, check boxes
 * (checked and grayed items), expanded and collapsed items and a sort indicator, and a {@code Tree} without columns
 * with a multiple selection, item colors and fonts.
 * <p>
 * Native paths exercised on purpose. Windows : the {@code LISTBOX} messages ({@code LB_GETSELITEMS}) ; the
 * {@code SysListView32} of comctl32 v6 with the structs {@code LVITEM}, {@code LVCOLUMN}, {@code LVHITTESTINFO} and
 * {@code RECT}, the {@code LVN_GETDISPINFO} notifications of every painted cell ({@code NMLVDISPINFO} : SWT gives the
 * texts and images on demand, and fires {@code SetData} from there for a virtual table), the custom draw of the item
 * colors and fonts ({@code NM_CUSTOMDRAW}, {@code NMLVCUSTOMDRAW}), the column header ({@code HDITEM}, the sort arrows)
 * and the image lists ({@code HIMAGELIST}, 32-bit images with an alpha channel) ; the {@code SysTreeView32} with
 * {@code TVITEM}, {@code TVINSERTSTRUCT}, {@code TVHITTESTINFO} and {@code NMTVCUSTOMDRAW} (SWT paints the columns of a
 * tree itself, under a separate {@code SysHeader32}), and the state image lists of the check boxes (SWT draws the
 * grayed box with the theme). GTK : {@code GtkTreeView} over a {@code GtkListStore} or {@code GtkTreeStore}, the cell
 * renderers and the cell data callbacks of SWT. macOS : {@code NSTableView} and {@code NSOutlineView}, their data
 * source and delegate methods implemented by SWT callbacks.
 * <p>
 * Why it matters for a native executable : every notification arrives through the window procedure of SWT, a JNI
 * callback into Java ({@code org.eclipse.swt.internal.Callback}), and every struct is read and written by the C side
 * of SWT through JNI field accesses ({@code GetFieldID} on {@code LVITEM}, {@code TVITEM}, {@code NMLVCUSTOMDRAW}...) :
 * a struct class or field missing from the JNI metadata of the executable fails at the first paint of the widget, not
 * at build time.
 */
@Singleton
public class SwtListsTablesTreesPage implements SwtPage {

    private static final String[] WORDS = { "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
            "India", "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa" };
    private static final int SINGLE_SELECTION = 3;
    private static final int[] MULTI_SELECTION = { 1, 2, 5, 7 };
    private static final int MULTI_TOP = 2;
    private static final int LIST_WIDTH = 140;
    private static final int TOP_HEIGHT = 170;

    private static final int VIRTUAL_COUNT = 100_000;
    private static final int VIRTUAL_TOP = 50_000;
    private static final int VIRTUAL_SELECTED = 50_002;
    private static final int LAZY_INDEX = 99_999;
    private static final int VIRTUAL_WIDTH = 600;

    private static final int TABLE_WIDTH = 960;
    private static final String[] TABLE_COLUMNS = { "Name", "Type", "Size", "Modified", "State" };
    private static final int[] TABLE_ALIGNMENTS = { SWT.LEFT, SWT.LEFT, SWT.RIGHT, SWT.CENTER, SWT.LEFT };
    /** Widths of the table columns, the packed one ({@code -1}) computed from its texts. */
    private static final int[] TABLE_WIDTHS = { 230, 150, 120, -1, 150 };
    private static final int SIZE_COLUMN = 2;
    private static final int MODIFIED_COLUMN = 3;
    private static final int[] COLUMN_ORDER = { 0, 1, 3, 2, 4 };
    private static final int[] TABLE_SELECTION = { 1, 3 };
    private static final int[] TABLE_CHECKED = { 0, 2, 3, 4 };
    private static final int TABLE_GRAYED = 4;
    private static final int BOLD_ROW = 2;
    private static final int TINTED_ROW = 5;
    private static final int RED_CELL_ROW = 6;
    private static final int BLUE_CELL_ROW = 7;
    private static final int TINT = 0xFFF8E1;
    private static final int RED = 0xC62828;
    private static final int PALE_BLUE = 0xE3F2FD;

    private static final int TREE_WIDTH = 560;
    private static final int PLAIN_TREE_WIDTH = 380;
    private static final String[] TREE_COLUMNS = { "Name", "Type", "Size" };
    private static final int[] TREE_WIDTHS = { 290, 140, 100 };
    private static final int LINK_BLUE = 0x1565C0;
    private static final int HIGHLIGHT = 0xFFF59D;
    /** Width of the name column of the checks. */
    private static final int CHECK_NAME_WIDTH = 400;

    private static final int FOLDER_COLOR = 0xFFB300;
    private static final int SOURCE_COLOR = 0xFB8C00;
    private static final int MODULE_COLOR = 0x5E35B1;
    private static final int LEAF_COLOR = 0x26A69A;

    /** A row of the table (sorted by size, descending). */
    private record FileRow(String name, String type, long size, String modified, String state, Shape shape, int color,
            int stateColor) {
    }

    private static final List<FileRow> FILES = List.of(
            new FileRow("quarkus-run.jar", "Archive", 4_812_331, "2025-11-03 14:20", "Built", Shape.SQUARE, 0x8E24AA,
                    0x43A047),
            new FileRow("swt-win32.dll", "Library", 3_604_480, "2025-10-28 09:12", "Extracted", Shape.DIAMOND,
                    0x1E88E5, 0x43A047),
            new FileRow("report.json", "JSON", 182_516, "2025-11-03 14:31", "Written", Shape.PAGE, 0x81D4FA,
                    0x43A047),
            new FileRow("main-window.png", "Image", 96_214, "2025-11-03 14:31", "Compared", Shape.TRIANGLE, 0x43A047,
                    0x43A047),
            new FileRow("application.properties", "Properties", 2_048, "2025-09-30 18:05", "Packaged", Shape.PAGE,
                    0xFFE082, 0x43A047),
            new FileRow("README.md", "Markdown", 1_512, "2025-08-14 11:47", "Unchanged", Shape.PAGE, 0xCFD8DC,
                    0x9E9E9E),
            new FileRow("Snapshot.java", "Java source", 1_180, "2025-11-02 21:09", "Modified", Shape.CIRCLE,
                    SOURCE_COLOR, SOURCE_COLOR),
            new FileRow(".gitignore", "Text", 64, "2025-01-05 08:00", "Ignored", Shape.PAGE, 0xEEEEEE, 0xE53935));

    /** A node of the tree with columns : its check state ({@code 0} unchecked, {@code 1} checked, {@code 2} grayed). */
    private record Node(String name, String type, String size, int check, boolean expanded, List<Node> children) {

        static Node leaf(String name, String type, String size, int check) {
            return new Node(name, type, size, check, false, List.of());
        }

        static Node folder(String name, String type, int check, boolean expanded, Node... children) {
            return new Node(name, type, "", check, expanded, List.of(children));
        }
    }

    /** The tree with columns, sorted by name (ascending) at every level. */
    private static final List<Node> PROJECT = List.of(
            Node.folder("docs", "Folder", 0, false,
                    Node.leaf("index.adoc", "Document", "5,120", 0)),
            Node.folder("showcase", "Module", 2, true,
                    Node.leaf("pom.xml", "Maven POM", "14,330", 1),
                    Node.folder("src/main/java", "Folder", 2, true,
                            Node.leaf("ChecksTable.java", "Java source", "9,216", 0),
                            Node.leaf("SwtKit.java", "Java source", "11,804", 1),
                            Node.leaf("SwtPage.java", "Java source", "3,412", 1)),
                    Node.folder("src/main/resources", "Folder", 0, false,
                            Node.leaf("application.properties", "Properties", "2,048", 0))),
            Node.folder("swt", "Module", 1, true,
                    Node.folder("deployment", "Module", 1, true,
                            Node.leaf("SwtProcessor.java", "Java source", "21,507", 1)),
                    Node.folder("runtime", "Module", 1, false,
                            Node.leaf("pom.xml", "Maven POM", "6,871", 1))));
    private static final String TREE_SELECTED = "SwtKit.java";

    // per build state (one content at a time)
    private State state;

    private static final class State {
        ChecksTable checks;
        org.eclipse.swt.widgets.List single;
        org.eclipse.swt.widgets.List multi;
        Table table;
        Table virtual;
        Label virtualLabel;
        /** The indices of the rows of the virtual table materialized by {@code SetData}. */
        final TreeSet<Integer> materialized = new TreeSet<>();
        /** {@code true} when selecting a row materialized it at once. */
        boolean selectMaterialized;
        /** The rows materialized before the scroll, and the top index right after {@code setTopIndex}. */
        List<Integer> beforeScroll = List.of();
        int scrolledTop = -1;
        boolean labelScheduled;
        Tree tree;
        Tree plain;
    }

    @Override
    public String id() {
        return "swt-lists-tables-trees";
    }

    @Override
    public String title() {
        return "Lists, tables and trees";
    }

    @Override
    public String category() {
        return SwtCategories.WIDGETS;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Control build(Composite parent) {
        State s = new State();
        state = s;
        Composite page = SwtKit.page(parent, 12);
        StructuredIcons.Cache icons = new StructuredIcons.Cache(parent.getDisplay());
        page.addListener(SWT.Dispose, event -> icons.dispose());
        SwtKit.text(page, "The native list, table and tree widgets of the platform. Top : List with single and multiple"
                + " selection, and a virtual Table of 100,000 rows, scrolled to row 50,000 once painted : only the"
                + " rows painted, selected or read are materialized by SetData. Middle : a Table with check boxes (one"
                + " grayed), generated images, moved columns (Modified before Size), a sort indicator on Size, two"
                + " selected rows, a bold row and cell colors. Bottom : a Tree with columns, check boxes and expanded"
                + " items, and a Tree without columns with a multiple selection. The page never takes the focus :"
                + " selections show their unfocused colors.", SwtKit.TEXT_WIDTH);

        Composite top = SwtKit.row(page, 12);
        s.single = list(labeled(top, "List : SWT.SINGLE"), SWT.SINGLE);
        // select() does not scroll : setSelection() would scroll the list while it has no size yet
        s.single.select(SINGLE_SELECTION);
        s.multi = list(labeled(top, "List : SWT.MULTI"), SWT.MULTI);
        s.multi.setSelection(MULTI_SELECTION);
        s.multi.setTopIndex(MULTI_TOP);
        Composite virtualColumn = labeled(top, "Table : SWT.VIRTUAL, 100,000 rows, SetData");
        s.virtual = virtualTable(virtualColumn, s, icons);
        // select() does not scroll (setSelection would show the row)
        s.virtual.select(VIRTUAL_SELECTED);
        s.selectMaterialized = s.materialized.contains(VIRTUAL_SELECTED);
        s.virtualLabel = SwtKit.text(virtualColumn, "SetData : pending", SwtKit.font(SWT.NORMAL, 8),
                SwtKit.MUTED_COLOR, 0);
        SwtKit.size(s.virtualLabel, VIRTUAL_WIDTH, SWT.DEFAULT);

        s.table = table(labeled(page, "Table : SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI, sorted by Size"
                + " (descending), Modified moved before Size"), icons);

        Composite trees = SwtKit.row(page, 12);
        s.tree = tree(labeled(trees, "Tree : SWT.CHECK | SWT.FULL_SELECTION, columns, sorted by Name"), icons);
        s.plain = plainTree(labeled(trees, "Tree : SWT.MULTI, no columns"), icons);

        s.checks = ChecksTable.table(page, "Checks", List.of(Check.info("state", "pending")), CHECK_NAME_WIDTH,
                ChecksTable.WIDTH);
        if (!SwtMode.snapshot()) {
            // the snapshot runs call ready() : run the checks for the user too
            ready(page);
        }
        return page;
    }

    /**
     * Once the virtual table is painted : scrolls it to {@link #VIRTUAL_TOP} and waits for the rows painted there, then
     * runs the checks.
     */
    @Override
    public CompletionStage<?> ready(Control content) {
        State s = state;
        return settled(s).thenCompose(v -> {
            if (s.virtual.isDisposed()) {
                return UiStages.rounds(1);
            }
            s.beforeScroll = List.copyOf(s.materialized);
            // after the first paint : on Windows, a list view scrolled (LVM_SCROLL) before its first paint is moved
            // back by a page when it paints (top index 49995)
            s.virtual.setTopIndex(VIRTUAL_TOP);
            s.scrolledTop = s.virtual.getTopIndex();
            return settled(s);
        }).thenAccept(v -> {
            if (!s.checks.isDisposed()) {
                s.checks.setChecks(checks(s));
            }
        });
    }

    /**
     * Completes once the page is painted and no {@code SetData} event came for 300 ms.
     */
    private static CompletionStage<Void> settled(State s) {
        return SwtSnapshots.settle(SwtSnapshots.SETTLE_MILLIS).thenCompose(v -> UiStages.untilStable(
                () -> s.materialized.size(), 300, 10_000, "the SetData events of the virtual table"));
    }

    @Override
    public void dispose(Control content) {
        state = null;
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

    private static org.eclipse.swt.widgets.List list(Composite parent, int selection) {
        org.eclipse.swt.widgets.List list = new org.eclipse.swt.widgets.List(parent,
                selection | SWT.BORDER | SWT.V_SCROLL);
        list.setItems(WORDS);
        SwtKit.size(list, LIST_WIDTH, TOP_HEIGHT);
        return list;
    }

    /**
     * The virtual table : the rows are materialized by the {@code SetData} listener when the table needs them (painted,
     * selected, or read through {@code TableItem}). On Windows, selecting a row of an owner data list view
     * ({@code LVM_SETITEMSTATE}) asks for that row at once ({@code LVN_GETDISPINFO}).
     */
    private static Table virtualTable(Composite parent, State s, StructuredIcons.Cache icons) {
        Table table = new Table(parent, SWT.VIRTUAL | SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        String[] titles = { "Index", "Item", "Hex", "Square" };
        int[] widths = { 90, 170, 110, 170 };
        int[] alignments = { SWT.RIGHT, SWT.LEFT, SWT.RIGHT, SWT.RIGHT };
        for (int i = 0; i < titles.length; i++) {
            TableColumn column = new TableColumn(table, alignments[i]);
            column.setText(titles[i]);
            column.setWidth(widths[i]);
        }
        table.addListener(SWT.SetData, event -> {
            TableItem item = (TableItem) event.item;
            int index = event.index;
            item.setText(new String[] { String.format(Locale.ROOT, "%,d", index), "Item " + index,
                    String.format(Locale.ROOT, "0x%05X", index),
                    String.format(Locale.ROOT, "%,d", (long) index * index) });
            if (index % 4 == 0) {
                item.setImage(0, icons.get(Shape.CIRCLE, LEAF_COLOR, 16));
            }
            s.materialized.add(index);
            scheduleLabel(s);
        });
        table.setItemCount(VIRTUAL_COUNT);
        SwtKit.size(table, VIRTUAL_WIDTH, TOP_HEIGHT);
        return table;
    }

    private static void scheduleLabel(State s) {
        if (!s.labelScheduled) {
            s.labelScheduled = true;
            // not while the table paints : later, on the user interface thread
            UiStages.display().asyncExec(() -> {
                s.labelScheduled = false;
                updateLabel(s);
            });
        }
    }

    private static void updateLabel(State s) {
        if (!s.virtualLabel.isDisposed()) {
            s.virtualLabel.setText(String.format(Locale.ROOT, "SetData : %d of %,d rows materialized (%s)",
                    s.materialized.size(), VIRTUAL_COUNT, ranges(s.materialized)));
        }
    }

    private static Table table(Composite parent, StructuredIcons.Cache icons) {
        Table table = new Table(parent, SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        for (int i = 0; i < TABLE_COLUMNS.length; i++) {
            TableColumn column = new TableColumn(table, TABLE_ALIGNMENTS[i]);
            column.setText(TABLE_COLUMNS[i]);
            column.setMoveable(true);
            column.setToolTipText(TABLE_COLUMNS[i] + " column");
            if (TABLE_WIDTHS[i] > 0) {
                column.setWidth(TABLE_WIDTHS[i]);
            }
        }
        for (FileRow row : FILES) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] { row.name(), row.type(), String.format(Locale.ROOT, "%,d", row.size()),
                    row.modified(), row.state() });
            item.setImage(0, icons.get(row.shape(), row.color(), 16));
            item.setImage(4, icons.get(Shape.CIRCLE, row.stateColor(), 16));
        }
        for (int index : TABLE_CHECKED) {
            table.getItem(index).setChecked(true);
        }
        table.getItem(TABLE_GRAYED).setGrayed(true);
        table.getItem(BOLD_ROW).setFont(SwtKit.font(SWT.BOLD, SwtKit.TEXT_POINTS));
        table.getItem(TINTED_ROW).setBackground(SwtKit.color(TINT));
        table.getItem(RED_CELL_ROW).setForeground(SIZE_COLUMN, SwtKit.color(RED));
        table.getItem(BLUE_CELL_ROW).setBackground(MODIFIED_COLUMN, SwtKit.color(PALE_BLUE));
        table.getColumn(MODIFIED_COLUMN).pack();
        table.setColumnOrder(COLUMN_ORDER);
        table.setSortColumn(table.getColumn(SIZE_COLUMN));
        table.setSortDirection(SWT.DOWN);
        table.setSelection(TABLE_SELECTION);
        // every row shown : the height of the rows and of the header
        SwtKit.size(table, TABLE_WIDTH, SWT.DEFAULT);
        return table;
    }

    private static Tree tree(Composite parent, StructuredIcons.Cache icons) {
        Tree tree = new Tree(parent, SWT.CHECK | SWT.FULL_SELECTION | SWT.BORDER | SWT.MULTI);
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        for (int i = 0; i < TREE_COLUMNS.length; i++) {
            TreeColumn column = new TreeColumn(tree, i == 2 ? SWT.RIGHT : SWT.LEFT);
            column.setText(TREE_COLUMNS[i]);
            column.setWidth(TREE_WIDTHS[i]);
        }
        List<TreeItem> expanded = new ArrayList<>();
        for (Node node : PROJECT) {
            add(tree, null, node, icons, expanded);
        }
        // expanded once every item exists, parents first
        expanded.forEach(item -> item.setExpanded(true));
        tree.setSortColumn(tree.getColumn(0));
        tree.setSortDirection(SWT.UP);
        tree.setSelection(find(tree.getItems(), TREE_SELECTED));
        SwtKit.size(tree, TREE_WIDTH, SWT.DEFAULT);
        return tree;
    }

    private static void add(Tree tree, TreeItem parentItem, Node node, StructuredIcons.Cache icons,
            List<TreeItem> expanded) {
        TreeItem item = parentItem == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parentItem, SWT.NONE);
        item.setText(new String[] { node.name(), node.type(), node.size() });
        boolean folder = !node.children().isEmpty();
        item.setImage(folder ? icons.get(node.type().equals("Module") ? Shape.SQUARE : Shape.FOLDER,
                node.type().equals("Module") ? MODULE_COLOR : FOLDER_COLOR, 16)
                : icons.get(Shape.PAGE, node.type().equals("Java source") ? 0xFFCC80 : 0xCFD8DC, 16));
        item.setChecked(node.check() > 0);
        item.setGrayed(node.check() == 2);
        if (node.expanded()) {
            expanded.add(item);
        }
        for (Node child : node.children()) {
            add(tree, item, child, icons, expanded);
        }
    }

    private static Tree plainTree(Composite parent, StructuredIcons.Cache icons) {
        Tree tree = new Tree(parent, SWT.MULTI | SWT.BORDER);
        TreeItem widgets = plainItem(tree, null, "Widgets", icons);
        widgets.setFont(SwtKit.font(SWT.BOLD, SwtKit.TEXT_POINTS));
        TreeItem basic = plainItem(tree, widgets, "Basic", icons);
        plainItem(tree, basic, "Button", icons);
        plainItem(tree, basic, "Label", icons);
        TreeItem structured = plainItem(tree, widgets, "Structured", icons);
        structured.setForeground(SwtKit.color(LINK_BLUE));
        plainItem(tree, structured, "List", icons);
        TreeItem table = plainItem(tree, structured, "Table", icons);
        TreeItem treeItem = plainItem(tree, structured, "Tree", icons);
        treeItem.setBackground(SwtKit.color(HIGHLIGHT));
        TreeItem containers = plainItem(tree, widgets, "Containers", icons);
        plainItem(tree, containers, "Shell", icons);
        plainItem(tree, containers, "TabFolder", icons);
        TreeItem graphics = plainItem(tree, null, "Graphics", icons);
        plainItem(tree, graphics, "GC", icons);
        plainItem(tree, null, "Layouts", icons);
        widgets.setExpanded(true);
        basic.setExpanded(true);
        structured.setExpanded(true);
        tree.setSelection(new TreeItem[] { table, treeItem });
        SwtKit.size(tree, PLAIN_TREE_WIDTH, SWT.DEFAULT);
        return tree;
    }

    private static TreeItem plainItem(Tree tree, TreeItem parentItem, String text, StructuredIcons.Cache icons) {
        TreeItem item = parentItem == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parentItem, SWT.NONE);
        item.setText(text);
        item.setImage(parentItem == null || text.equals("Basic") || text.equals("Structured")
                || text.equals("Containers") ? icons.get(Shape.FOLDER, FOLDER_COLOR, 16)
                        : icons.get(Shape.CIRCLE, LEAF_COLOR, 16));
        return item;
    }

    // ------------------------------------------------------------------------------------------------------ checks

    private static List<Check> checks(State s) {
        List<Check> checks = new ArrayList<>();
        listChecks(s, checks);
        virtualChecks(s, checks);
        tableChecks(s.table, checks);
        treeChecks(s.tree, s.plain, checks);
        return checks;
    }

    private static void listChecks(State s, List<Check> checks) {
        org.eclipse.swt.widgets.List single = s.single;
        org.eclipse.swt.widgets.List multi = s.multi;
        checks.add(SwtChecks.expect("List SWT.SINGLE : getItemCount()", WORDS.length, single::getItemCount));
        checks.add(SwtChecks.expect("List SWT.SINGLE : getSelectionIndex(), getSelection()", "3 [Delta]",
                () -> single.getSelectionIndex() + " " + Arrays.toString(single.getSelection())));
        checks.add(SwtChecks.expect("List SWT.SINGLE : getTopIndex() (select() does not scroll), indexOf(\"Kilo\")",
                "0 10",
                () -> single.getTopIndex() + " " + single.indexOf("Kilo")));
        checks.add(SwtChecks.expect("List SWT.MULTI : getSelectionIndices() (LB_GETSELITEMS)",
                Arrays.toString(MULTI_SELECTION), () -> Arrays.toString(multi.getSelectionIndices())));
        checks.add(SwtChecks.expect("List SWT.MULTI : getSelection()", "[Bravo, Charlie, Foxtrot, Hotel]",
                () -> Arrays.toString(multi.getSelection())));
        checks.add(SwtChecks.expect("List SWT.MULTI : getTopIndex() after setTopIndex(2), isSelected(5)", "2 true",
                () -> multi.getTopIndex() + " " + multi.isSelected(5)));
        checks.add(SwtChecks.info("List : getItemHeight()", single::getItemHeight));
    }

    private static void virtualChecks(State s, List<Check> checks) {
        Table virtual = s.virtual;
        int visible = (virtual.getClientArea().height - virtual.getHeaderHeight()) / virtual.getItemHeight();
        // the rows painted before the scroll (without the selected row), and after it (before the lazy access below)
        List<Integer> first = s.beforeScroll.stream().filter(i -> i != VIRTUAL_SELECTED).toList();
        List<Integer> scrolled = s.materialized.stream().filter(i -> !s.beforeScroll.contains(i)).toList();
        checks.add(SwtChecks.expect("virtual Table : getItemCount()", VIRTUAL_COUNT, virtual::getItemCount));
        checks.add(SwtChecks.expect("virtual Table : getSelectionIndices()", "[" + VIRTUAL_SELECTED + "]",
                () -> Arrays.toString(virtual.getSelectionIndices())));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, Check.of("virtual Table : select(50002) materializes the row"
                + " at once (LVM_SETITEMSTATE asks for it)", s.selectMaterialized, s.selectMaterialized)));
        checks.add(Check.info("virtual Table : fully visible rows", visible));
        checks.add(Check.info("virtual Table : rows materialized by the first paint", ranges(first)));
        // GTK : a virtual Table is a GtkTreeView in fixed height mode (Table.createHandle, Table.java:709 of SWT GTK
        // 3.132.0), and SetData comes for every row whose cells GTK asks for (Table.cellDataProc, Table.java:227-231) :
        // GtkTreeView also measures the row above the top one (validate_visible_area), a few rows below the partly
        // visible one (3 in the Docker image, 4 allowed), and once the root of its red-black tree of rows, for the
        // height of every row (initialize_fixed_height_mode) : a row far below (32767 of 100,000). The table stays lazy
        int above = SwtMode.isLinux() ? 1 : 0;
        int below = SwtMode.isLinux() ? 4 : 1;
        int far = SwtMode.isLinux() ? 1 : 0;
        checks.add(SwtChecks.expect("virtual Table : first paint : only the rows shown from row 0", true,
                () -> !first.isEmpty() && first.getFirst() == 0
                        && first.stream().filter(i -> i > visible + below).count() <= far));
        checks.add(SwtChecks.expect("virtual Table : getTopIndex() after setTopIndex(50000), once settled",
                VIRTUAL_TOP + " " + VIRTUAL_TOP, () -> s.scrolledTop + " " + virtual.getTopIndex()));
        checks.add(Check.info("virtual Table : rows materialized after the scroll", ranges(scrolled)));
        // macOS 10.11 and later : the visible rect of the NSTableView starts under its header (Table.getClientArea,
        // getTopIndex, setTopIndex), so AppKit also paints, and SetData materializes, the rows behind the header,
        // just above the top index (49998 and 49999 for a 28 point header)
        int underHeader = SwtMode.isMac() ? Math.ceilDiv(virtual.getHeaderHeight(), virtual.getItemHeight()) : 0;
        checks.add(SwtChecks.expect("virtual Table : after the scroll : only the rows shown from row 50000", true,
                () -> !scrolled.isEmpty() && scrolled.getFirst() >= VIRTUAL_TOP - underHeader - above
                        && scrolled.getLast() <= VIRTUAL_TOP + visible + below));
        checks.add(SwtChecks.expect("virtual Table : getItem(99999).getText(1) fires SetData", "Item 99999 true",
                () -> virtual.getItem(LAZY_INDEX).getText(1) + " " + s.materialized.contains(LAZY_INDEX)));
        updateLabel(s);
    }

    private static void tableChecks(Table table, List<Check> checks) {
        checks.add(SwtChecks.expect("Table : getItemCount(), getColumnCount()", FILES.size() + " "
                + TABLE_COLUMNS.length, () -> table.getItemCount() + " " + table.getColumnCount()));
        checks.add(SwtChecks.expect("Table : getHeaderVisible(), getLinesVisible()", "true true",
                () -> table.getHeaderVisible() + " " + table.getLinesVisible()));
        checks.add(SwtChecks.expect("Table : getColumnOrder() (LVM_GETCOLUMNORDERARRAY)",
                Arrays.toString(COLUMN_ORDER), () -> Arrays.toString(table.getColumnOrder())));
        checks.add(SwtChecks.expect("Table : columns in display order", "Name Type Modified Size State",
                () -> Arrays.stream(table.getColumnOrder()).mapToObj(i -> table.getColumn(i).getText())
                        .collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("Table : column alignments", "LEFT LEFT RIGHT CENTER LEFT",
                () -> Arrays.stream(table.getColumns()).map(c -> alignment(c.getAlignment()))
                        .collect(Collectors.joining(" "))));
        // GTK : getWidth() is the width set by setWidth until the column is allocated, then its allocated width
        // (TableColumn.getWidth and gtk_size_allocate, TableColumn.java:320-327 and 401-402 of SWT GTK 3.132.0), and
        // GtkTreeView gives the width left in the table to the last column : State, last in the display order
        checks.add(SwtChecks.expect("Table : widths of the columns set with setWidth",
                SwtMode.pick("230 150 120 150", "230 150 120 150", "230 150 120 150 or more"),
                () -> IntStream.of(0, 1, 2, 4).mapToObj(i -> {
                    int width = table.getColumn(i).getWidth();
                    return SwtMode.isLinux() && i == 4 && width >= TABLE_WIDTHS[i] ? TABLE_WIDTHS[i] + " or more"
                            : String.valueOf(width);
                }).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.info("Table : width of the Modified column after pack()",
                () -> table.getColumn(MODIFIED_COLUMN).getWidth()));
        checks.add(SwtChecks.expect("Table : getSortColumn(), getSortDirection()", "Size DOWN",
                () -> table.getSortColumn().getText() + " " + (table.getSortDirection() == SWT.DOWN ? "DOWN"
                        : String.valueOf(table.getSortDirection()))));
        checks.add(SwtChecks.expect("Table : Size column from top to bottom (sorted, descending)", true,
                () -> {
                    long previous = Long.MAX_VALUE;
                    for (TableItem item : table.getItems()) {
                        long size = Long.parseLong(item.getText(SIZE_COLUMN).replace(",", ""));
                        if (size > previous) {
                            return false;
                        }
                        previous = size;
                    }
                    return true;
                }));
        checks.add(SwtChecks.expect("Table : getSelectionIndices()", Arrays.toString(TABLE_SELECTION),
                () -> Arrays.toString(table.getSelectionIndices())));
        checks.add(SwtChecks.expect("Table : checked rows", "quarkus-run.jar, report.json, main-window.png,"
                + " application.properties", () -> names(table, TableItem::getChecked)));
        checks.add(SwtChecks.expect("Table : grayed rows", "application.properties",
                () -> names(table, TableItem::getGrayed)));
        checks.add(SwtChecks.expect("Table : font of row 2, background of row 5", "bold #FFF8E1",
                () -> ((table.getItem(BOLD_ROW).getFont().getFontData()[0].getStyle() & SWT.BOLD) != 0 ? "bold"
                        : "normal") + " " + SwtChecks.rgb(table.getItem(TINTED_ROW).getBackground().getRGB())));
        checks.add(SwtChecks.expect("Table : cell colors (row 6 Size foreground, row 7 Modified background)",
                "#C62828 #E3F2FD", () -> SwtChecks.rgb(table.getItem(RED_CELL_ROW).getForeground(SIZE_COLUMN)
                        .getRGB()) + " " + SwtChecks.rgb(table.getItem(BLUE_CELL_ROW).getBackground(MODIFIED_COLUMN)
                                .getRGB())));
        checks.add(SwtChecks.expect("Table : images of row 0 (Name, State)", "16x16 16x16",
                () -> size(table.getItem(0).getImage(0).getBounds()) + " "
                        + size(table.getItem(0).getImage(4).getBounds())));
        checks.add(SwtChecks.expect("Table : getItem(Point) at the center of row 3 (LVM_SUBITEMHITTEST)",
                FILES.get(3).name(), () -> {
                    Rectangle bounds = table.getItem(3).getBounds();
                    TableItem hit = table.getItem(new Point(bounds.x + bounds.width / 2,
                            bounds.y + bounds.height / 2));
                    return hit == null ? "none" : hit.getText(0);
                }));
        checks.add(SwtChecks.info("Table : getItemHeight(), getHeaderHeight()",
                () -> table.getItemHeight() + " " + table.getHeaderHeight()));
        checks.add(SwtChecks.info("Table : bounds of row 3, of its Size cell, of its Name image",
                () -> SwtChecks.rect(table.getItem(3).getBounds()) + " ; "
                        + SwtChecks.rect(table.getItem(3).getBounds(SIZE_COLUMN)) + " ; "
                        + SwtChecks.rect(table.getItem(3).getImageBounds(0))));
    }

    private static void treeChecks(Tree tree, Tree plain, List<Check> checks) {
        checks.add(SwtChecks.expect("Tree : root items, all items", "3 15",
                () -> tree.getItemCount() + " " + all(tree).size()));
        checks.add(SwtChecks.expect("Tree : getColumnCount(), widths of the columns", "3 290 140 100",
                () -> tree.getColumnCount() + " " + Arrays.stream(tree.getColumns())
                        .map(c -> String.valueOf(c.getWidth())).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("Tree : getSortColumn(), getSortDirection()", "Name UP",
                () -> tree.getSortColumn().getText() + " " + (tree.getSortDirection() == SWT.UP ? "UP"
                        : String.valueOf(tree.getSortDirection()))));
        checks.add(SwtChecks.expect("Tree : expanded items", "showcase, src/main/java, swt, deployment",
                () -> treeNames(all(tree), TreeItem::getExpanded)));
        checks.add(SwtChecks.expect("Tree : collapsed items with children", "docs (1), src/main/resources (1),"
                + " runtime (1)", () -> all(tree).stream().filter(i -> !i.getExpanded() && i.getItemCount() > 0)
                        .map(i -> i.getText() + " (" + i.getItemCount() + ")").collect(Collectors.joining(", "))));
        checks.add(SwtChecks.expect("Tree : checked items", "showcase, pom.xml, src/main/java, SwtKit.java,"
                + " SwtPage.java, swt, deployment, SwtProcessor.java, runtime, pom.xml",
                () -> treeNames(all(tree), TreeItem::getChecked)));
        checks.add(SwtChecks.expect("Tree : grayed items", "showcase, src/main/java",
                () -> treeNames(all(tree), TreeItem::getGrayed)));
        checks.add(SwtChecks.expect("Tree : getSelection()", TREE_SELECTED,
                () -> Arrays.stream(tree.getSelection()).map(TreeItem::getText).collect(Collectors.joining(", "))));
        checks.add(SwtChecks.expect("Tree : getParentItem() chain of the selection", "src/main/java < showcase",
                () -> {
                    TreeItem parent = tree.getSelection()[0].getParentItem();
                    return parent.getText() + " < " + parent.getParentItem().getText();
                }));
        checks.add(SwtChecks.expect("Tree : names sorted (ascending) under every parent", true,
                () -> sorted(tree.getItems())));
        checks.add(SwtChecks.expect("Tree : getItem(Point) at the center of SwtKit.java (TVM_HITTEST)",
                TREE_SELECTED, () -> {
                    TreeItem item = find(tree.getItems(), TREE_SELECTED);
                    Rectangle bounds = item.getBounds(0);
                    TreeItem hit = tree.getItem(new Point(bounds.x + bounds.width / 2,
                            bounds.y + bounds.height / 2));
                    return hit == null ? "none" : hit.getText();
                }));
        // GTK : while the tree is not scrolled (its vertical adjustment equals the cached one, 0 when setTopItem was
        // never called), getTopItem() returns the first selected item, not the item at the top (Tree.getTopItem and
        // _getCachedTopItem, Tree.java:2114-2134 and 2151-2172 of SWT GTK 3.132.0), a GTK SWT bug
        checks.add(SwtChecks.expect("Tree : getTopItem(), texts of the selected row",
                SwtMode.pick("docs", "docs", TREE_SELECTED) + " SwtKit.java/Java source/11,804",
                () -> tree.getTopItem().getText() + " " + IntStream.range(0, 3)
                        .mapToObj(i -> tree.getSelection()[0].getText(i)).collect(Collectors.joining("/"))));
        checks.add(SwtChecks.info("Tree : getItemHeight(), getHeaderHeight()",
                () -> tree.getItemHeight() + " " + tree.getHeaderHeight()));
        checks.add(SwtChecks.info("Tree : bounds of SwtKit.java, of its Size cell",
                () -> SwtChecks.rect(find(tree.getItems(), TREE_SELECTED).getBounds()) + " ; "
                        + SwtChecks.rect(find(tree.getItems(), TREE_SELECTED).getBounds(2))));

        checks.add(SwtChecks.expect("Tree without columns : getSelection() (SWT.MULTI)", "Table, Tree",
                () -> Arrays.stream(plain.getSelection()).map(TreeItem::getText).collect(Collectors.joining(", "))));
        checks.add(SwtChecks.expect("Tree without columns : expanded items, all items", "Widgets, Basic,"
                + " Structured 14", () -> treeNames(all(plain), TreeItem::getExpanded) + " " + all(plain).size()));
        checks.add(SwtChecks.expect("Tree without columns : item font, foreground, background",
                "bold #1565C0 #FFF59D", () -> {
                    TreeItem widgets = plain.getItem(0);
                    String font = (widgets.getFont().getFontData()[0].getStyle() & SWT.BOLD) != 0 ? "bold"
                            : "normal";
                    return font + " " + SwtChecks.rgb(find(plain.getItems(), "Structured").getForeground().getRGB())
                            + " " + SwtChecks.rgb(find(plain.getItems(), "Tree").getBackground().getRGB());
                }));
        checks.add(SwtChecks.expect("Tree without columns : getColumnCount(), getHeaderVisible()", "0 false",
                () -> plain.getColumnCount() + " " + plain.getHeaderVisible()));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private static String names(Table table, Predicate<TableItem> filter) {
        return Arrays.stream(table.getItems()).filter(filter).map(item -> item.getText(0))
                .collect(Collectors.joining(", "));
    }

    private static String treeNames(List<TreeItem> items, Predicate<TreeItem> filter) {
        return items.stream().filter(filter).map(TreeItem::getText).collect(Collectors.joining(", "));
    }

    /**
     * Every item of {@code tree}, depth first.
     */
    private static List<TreeItem> all(Tree tree) {
        List<TreeItem> all = new ArrayList<>();
        collect(tree.getItems(), all);
        return all;
    }

    private static void collect(TreeItem[] items, List<TreeItem> all) {
        for (TreeItem item : items) {
            all.add(item);
            collect(item.getItems(), all);
        }
    }

    /**
     * The first item named {@code text}, depth first.
     */
    private static TreeItem find(TreeItem[] items, String text) {
        for (TreeItem item : items) {
            if (item.getText().equals(text)) {
                return item;
            }
            TreeItem found = find(item.getItems(), text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean sorted(TreeItem[] items) {
        for (int i = 1; i < items.length; i++) {
            if (items[i - 1].getText().compareToIgnoreCase(items[i].getText()) > 0) {
                return false;
            }
        }
        for (TreeItem item : items) {
            if (!sorted(item.getItems())) {
                return false;
            }
        }
        return true;
    }

    private static String alignment(int alignment) {
        return switch (alignment) {
            case SWT.LEFT -> "LEFT";
            case SWT.RIGHT -> "RIGHT";
            case SWT.CENTER -> "CENTER";
            default -> String.valueOf(alignment);
        };
    }

    private static String size(Rectangle r) {
        return r.width + "x" + r.height;
    }

    /**
     * The sorted indices as ranges : {@code 50000-50008, 99999}.
     */
    private static String ranges(Iterable<Integer> indices) {
        List<String> ranges = new ArrayList<>();
        Integer start = null;
        Integer end = null;
        for (int index : indices) {
            if (start != null && index == end + 1) {
                end = index;
                continue;
            }
            if (start != null) {
                ranges.add(start.equals(end) ? String.valueOf(start) : start + "-" + end);
            }
            start = index;
            end = index;
        }
        if (start != null) {
            ranges.add(start.equals(end) ? String.valueOf(start) : start + "-" + end);
        }
        return ranges.isEmpty() ? "none" : String.join(", ", ranges);
    }
}
