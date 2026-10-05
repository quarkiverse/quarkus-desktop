package io.quarkiverse.desktop.showcase.swt.pages.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jboss.logging.Logger;

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
 * Data transfer with SWT : the system {@link Clipboard} (round trips of text, RTF, HTML, a URL, a file list, an image
 * and a type of the page, a {@link ByteArrayTransfer} subclass ; the available types and their native names ;
 * {@code getContentsAsync} ; the validation of the data ; {@code clearContents}), and a {@link DragSource} and a
 * {@link DropTarget} set up on a label, a table and a canvas with every transfer type and their supported native types
 * (no real drag : nothing moves the mouse ; in interactive mode, the canvas shows what is dropped on it).
 * <p>
 * The clipboard is the user's : its text, RTF, HTML, URL, file list and image contents are saved first and restored
 * when the page is left (unless another application changed the clipboard meanwhile) ; on Windows, the data of the
 * page carries the {@code CanIncludeInClipboardHistory} and {@code CanUploadToCloudClipboard} formats (zero), which
 * keep it out of the clipboard history and of the cloud clipboard. A round trip is retried when another application
 * changed the clipboard between the write and the reads.
 * <p>
 * Native code paths. Windows : the OLE clipboard. {@code OleSetClipboard} receives an {@code IDataObject} that SWT
 * implements as a {@code COMObject}, a table of native function pointers whose methods ({@code QueryGetData},
 * {@code GetData}, {@code EnumFormatEtc}...) OLE calls back, through JNI callbacks, to list the formats and to render
 * them ; {@code OleGetClipboard} returns the OLE data object that the reads query, with the {@code FORMATETC} and
 * {@code STGMEDIUM} structs and {@code HGLOBAL} memory : {@code CF_UNICODETEXT}, {@code CF_DIB} and its
 * {@code BITMAPINFOHEADER} (a DIB section read back), {@code CF_HDROP} and its {@code DROPFILES}, the registered
 * formats {@code Rich Text Format}, {@code HTML Format} (UTF-8 with its fragment offsets) and
 * {@code UniformResourceLocatorW} ; {@code OleFlushClipboard} renders every format when the clipboard is disposed.
 * {@code DropTarget} registers an {@code IDropTarget} COMObject ({@code RegisterDragDrop}) that OLE calls during a
 * drag ; {@code DragSource} creates its {@code IDropSource} and {@code IDataObject} COMObjects when a drag starts.
 * GTK : the {@code GtkClipboard} of the {@code CLIPBOARD} selection (its get and clear callbacks, the reads waiting in
 * a nested main loop), the targets as atoms, the drag and drop signals of the widgets. Cocoa : {@code NSPasteboard}.
 * <p>
 * Why it matters for a native executable : the COM methods, the GTK callbacks and the clipboard owner functions are
 * Java methods that native code calls through JNI : each one must be registered for JNI access (a missing one is an
 * {@code SWTError} "No more callbacks", or a crash at the first call from native code, never a build error), and the
 * structs SWT copies from native memory are read through field ids looked up by name. The page renders the same pixels
 * in every run : the image read back is the generated one, and the values that depend on other applications (the
 * native type names of the clipboard) are informational.
 */
@Singleton
public class SwtDataTransferPage implements SwtPage {

    private static final Logger LOG = Logger.getLogger(SwtDataTransferPage.class);

    /** Plain text : ASCII, Latin-1, a symbol and CJK characters (UTF-16 on Windows, UTF-8 on GTK). */
    static final String TEXT = "Quarkus Desktop SWT clipboard ünïcødé ✓ 日本語";
    /** The text that goes with the other formats of the rich contents. */
    static final String RICH_TEXT = "Quarkus Desktop rich contents";
    static final String RTF = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0 Quarkus {\\b Desktop} {\\i SWT}\\par}";
    static final String HTML = "<p>Quarkus <b>Desktop</b> <i>SWT</i> café</p>";
    static final String URL = "https://github.com/quarkiverse/quarkus-desktop";
    /** The payload of {@link ShowcaseTransfer}, as UTF-8 bytes. */
    static final String CUSTOM = "quarkus-desktop-showcase:42";
    /** The texts the page writes : the clipboard still holds the data of the page when its text is one of them. */
    static final List<String> OWN_TEXTS = List.of(TEXT, RICH_TEXT);

    /** The transfer types, without their {@code Transfer} suffix. */
    static final List<String> TRANSFER_NAMES = List.of("Text", "RTF", "HTML", "URL", "File", "Image", "Showcase");

    private static final int IMAGE_WIDTH = 64;
    private static final int IMAGE_HEIGHT = 48;
    private static final int ZONE_BORDER = 0x90A4AE;
    private static final int PLACEHOLDER = 0xECEFF1;
    private static final int ATTEMPTS = 3;
    private static final int NAME_WIDTH = 330;

    // per build state (one content at a time)
    private State state;

    /** What one build of the page holds. */
    private static final class State {
        Transfers transfers;
        ImageData generated;
        Canvas readBack;
        Image readBackImage;
        Canvas zone;
        String dropped;
        ChecksTable clipboardTable;
        ClipboardSession session;
    }

    @Override
    public String id() {
        return "swt-data-transfer";
    }

    @Override
    public String title() {
        return "Clipboard and drag and drop";
    }

    @Override
    public String category() {
        return SwtCategories.DESKTOP;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        State s = new State();
        state = s;
        // the page's own types : their native types are registered at run time (Transfer.registerType)
        s.transfers = new Transfers(new ShowcaseTransfer(),
                SwtMode.isWindows() ? new HistoryExclusionTransfer() : null);
        s.generated = generatedImage();
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "Round trips through the system clipboard (text, RTF, HTML, a URL, a file list, an image and"
                + " a custom ByteArrayTransfer type), then drag sources and drop targets on native and custom controls"
                + " with every transfer type. The user's clipboard is saved first and restored when the page is left;"
                + " nothing is dragged in snapshot mode (no robot).", SwtKit.TEXT_WIDTH);
        Composite row = SwtKit.row(page, 32);
        imageRoundTrip(row, s);
        List<Check> dragAndDrop = dragAndDrop(row, s);
        ChecksTable.table(page, "Drag sources, drop targets and transfer types", dragAndDrop, NAME_WIDTH,
                ChecksTable.WIDTH);
        s.clipboardTable = ChecksTable.table(page, "Clipboard round trips", List.of(Check.info("state", "pending")),
                NAME_WIDTH, ChecksTable.WIDTH);
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        State s = state;
        s.session = new ClipboardSession(content.getDisplay(), s.transfers, s.generated);
        return s.session.run().thenAccept(result -> {
            if (s.clipboardTable.isDisposed()) {
                return;
            }
            s.clipboardTable.setChecks(result.checks());
            if (result.image() != null && !s.readBack.isDisposed()) {
                s.readBackImage = new Image(s.readBack.getDisplay(), result.image().scaledTo(2 * IMAGE_WIDTH,
                        2 * IMAGE_HEIGHT));
                s.readBack.redraw();
            }
        });
    }

    @Override
    public void dispose(Control content) {
        State s = state;
        state = null;
        if (s != null && s.session != null) {
            s.session.dispose();
        }
    }

    // --------------------------------------------------------------------------------------------- image and zone

    private static void imageRoundTrip(Composite row, State s) {
        Composite column = SwtKit.column(row, 6);
        SwtKit.title(column, "ImageTransfer round trip");
        Composite images = SwtKit.row(column, 16);
        Composite left = SwtKit.column(images, 4);
        SwtKit.image(left, s.generated.scaledTo(2 * IMAGE_WIDTH, 2 * IMAGE_HEIGHT));
        SwtKit.caption(left, "generated (x2)");
        Composite right = SwtKit.column(images, 4);
        s.readBack = SwtKit.canvas(right, 2 * IMAGE_WIDTH, 2 * IMAGE_HEIGHT, gc -> paintReadBack(gc, s));
        s.readBack.addListener(SWT.Dispose, event -> {
            if (s.readBackImage != null) {
                s.readBackImage.dispose();
                s.readBackImage = null;
            }
        });
        SwtKit.caption(right, "read back (x2)");
    }

    private static void paintReadBack(GC gc, State s) {
        if (s.readBackImage != null && !s.readBackImage.isDisposed()) {
            gc.drawImage(s.readBackImage, 0, 0);
            return;
        }
        gc.setBackground(SwtKit.color(PLACEHOLDER));
        gc.fillRectangle(0, 0, 2 * IMAGE_WIDTH, 2 * IMAGE_HEIGHT);
        gc.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        centered(gc, "pending", 2 * IMAGE_WIDTH, IMAGE_HEIGHT - gc.getFontMetrics().getHeight() / 2);
    }

    private static void paintZone(GC gc, State s, int width, int height) {
        gc.setForeground(SwtKit.color(ZONE_BORDER));
        gc.setLineStyle(SWT.LINE_DASH);
        gc.drawRectangle(1, 1, width - 3, height - 3);
        gc.setLineStyle(SWT.LINE_SOLID);
        gc.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
        gc.setFont(SwtKit.font(SWT.BOLD, 9));
        int lineHeight = gc.getFontMetrics().getHeight();
        centered(gc, "Drop zone (a Canvas)", width, height / 2 - lineHeight);
        gc.setForeground(SwtKit.color(SwtKit.MUTED_COLOR));
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        centered(gc, s.dropped == null ? "accepts every transfer type" : "dropped " + s.dropped, width, height / 2 + 2);
    }

    private static void centered(GC gc, String text, int width, int y) {
        Point extent = gc.stringExtent(text);
        gc.drawString(text, (width - extent.x) / 2, y, true);
    }

    /**
     * A 64 x 48 test image drawn offscreen (opaque : the clipboard bitmap formats have no alpha channel).
     */
    static ImageData generatedImage() {
        return SwtSnapshots.offscreen(IMAGE_WIDTH, IMAGE_HEIGHT, gc -> {
            gc.setBackground(SwtKit.color(0x1E88E5));
            gc.fillRectangle(0, 0, 32, 24);
            gc.setBackground(SwtKit.color(0xE53935));
            gc.fillRectangle(32, 0, 32, 24);
            gc.setBackground(SwtKit.color(0x43A047));
            gc.fillRectangle(0, 24, 32, 24);
            gc.setBackground(SwtKit.color(0xFDD835));
            gc.fillRectangle(32, 24, 32, 24);
            gc.setBackground(SwtKit.color(0xFFFFFF));
            gc.fillOval(20, 12, 24, 24);
            gc.setForeground(SwtKit.color(0x263238));
            gc.drawLine(0, 47, 63, 0);
            gc.drawRectangle(0, 0, 63, 47);
        });
    }

    // ---------------------------------------------------------------------------------------------- drag and drop

    private static List<Check> dragAndDrop(Composite row, State s) {
        Composite column = SwtKit.column(row, 6);
        SwtKit.title(column, "Drag sources and drop targets");
        Composite controls = SwtKit.row(column, 12);
        Label label = new Label(controls, SWT.CENTER | SWT.WRAP);
        label.setText("\nDrag source\n(a Label)");
        label.setBackground(SwtKit.color(PLACEHOLDER));
        SwtKit.size(label, 130, 2 * IMAGE_HEIGHT);
        Table table = new Table(controls, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        for (String item : List.of("Table : drag source", "and drop target", "(default effects)")) {
            new TableItem(table, SWT.NONE).setText(item);
        }
        SwtKit.size(table, 170, 2 * IMAGE_HEIGHT);
        int zoneWidth = 220;
        s.zone = SwtKit.canvas(controls, zoneWidth, 2 * IMAGE_HEIGHT, gc -> paintZone(gc, s, zoneWidth,
                2 * IMAGE_HEIGHT));

        Transfer[] all = s.transfers.all().toArray(Transfer[]::new);
        int operations = DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK;
        DragSource labelSource = new DragSource(label, operations);
        labelSource.setTransfer(all);
        labelSource.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragSetData(DragSourceEvent event) {
                event.data = dataFor(s, event.dataType);
            }
        });
        DragSource tableSource = new DragSource(table, DND.DROP_COPY | DND.DROP_MOVE);
        tableSource.setTransfer(all);
        tableSource.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragSetData(DragSourceEvent event) {
                event.data = dataFor(s, event.dataType);
            }
        });
        DropTarget tableTarget = new DropTarget(table, operations | DND.DROP_DEFAULT);
        tableTarget.setTransfer(all);
        tableTarget.addDropListener(new DropTargetAdapter() {
            @Override
            public void drop(DropTargetEvent event) {
                new TableItem(table, SWT.NONE).setText("dropped " + describe(event.data));
            }
        });
        DropTarget zoneTarget = new DropTarget(s.zone, operations | DND.DROP_DEFAULT);
        zoneTarget.setTransfer(all);
        zoneTarget.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent event) {
                if (event.detail == DND.DROP_DEFAULT) {
                    event.detail = (event.operations & DND.DROP_COPY) != 0 ? DND.DROP_COPY : DND.DROP_NONE;
                }
            }

            @Override
            public void drop(DropTargetEvent event) {
                s.dropped = describe(event.data);
                s.zone.redraw();
            }
        });

        List<Check> checks = new ArrayList<>();
        String names = String.join(" ", TRANSFER_NAMES);
        checks.add(SwtChecks.expect("Label : DragSource operations : transfers", "COPY MOVE LINK : " + names,
                () -> operations(labelSource.getStyle()) + " : " + names(s, labelSource.getTransfer())));
        checks.add(SwtChecks.expect("Table : DragSource operations : transfers", "COPY MOVE : " + names,
                () -> operations(tableSource.getStyle()) + " : " + names(s, tableSource.getTransfer())));
        checks.add(SwtChecks.expect("Table : DropTarget operations : transfers",
                "COPY MOVE LINK DEFAULT : " + names,
                () -> operations(tableTarget.getStyle()) + " : " + names(s, tableTarget.getTransfer())));
        checks.add(SwtChecks.expect("Canvas : DropTarget operations : transfers",
                "COPY MOVE LINK DEFAULT : " + names,
                () -> operations(zoneTarget.getStyle()) + " : " + names(s, zoneTarget.getTransfer())));
        checks.add(SwtChecks.expect("getData(DRAG_SOURCE_KEY, DROP_TARGET_KEY)",
                "label : source ; table : source, target ; canvas : target",
                () -> "label : " + keys(label, labelSource, null) + " ; table : "
                        + keys(table, tableSource, tableTarget) + " ; canvas : " + keys(s.zone, null, zoneTarget)));
        checks.add(SwtChecks.expect("getDragListeners(), getDropListeners()", "label 1 ; table 1, 1 ; canvas 1",
                () -> "label " + labelSource.getDragListeners().length + " ; table "
                        + tableSource.getDragListeners().length + ", " + tableTarget.getDropListeners().length
                        + " ; canvas " + zoneTarget.getDropListeners().length));
        // DragSource and DropTarget give a Table or a Tree a default effect (the insertion feedback, the drag image)
        checks.add(SwtChecks.expect("default effects of drag and drop",
                "label none ; table TableDragSourceEffect, TableDropTargetEffect ; canvas none",
                () -> "label " + effect(labelSource.getDragSourceEffect()) + " ; table "
                        + effect(tableSource.getDragSourceEffect()) + ", " + effect(tableTarget.getDropTargetEffect())
                        + " ; canvas " + effect(zoneTarget.getDropTargetEffect())));
        checks.add(SwtChecks.expect("a second DragSource on the label", "SWTError " + DND.ERROR_CANNOT_INIT_DRAG
                + " Cannot initialize Drag", () -> thrown(() -> new DragSource(label, DND.DROP_COPY))));
        checks.add(SwtChecks.expect("a second DropTarget on the canvas", "SWTError " + DND.ERROR_CANNOT_INIT_DROP
                + " Cannot initialize Drop", () -> thrown(() -> new DropTarget(s.zone, DND.DROP_COPY))));
        List<Transfer> transfers = s.transfers.all();
        for (int i = 0; i < transfers.size(); i++) {
            Transfer transfer = transfers.get(i);
            checks.add(SwtChecks.info(TRANSFER_NAMES.get(i) + "Transfer.getSupportedTypes()",
                    () -> typeNames(transfer)));
        }
        Check own = SwtChecks.expect("isSupportedType : its own types only", "true",
                () -> supportsOwnTypesOnly(transfers));
        checks.add(SwtMode.isMac() ? Check.info(own.name(), own.value()) : own);
        checks.add(SwtChecks.expect("registerType(ShowcaseTransfer.NAME) twice", "the same type",
                () -> Transfer.registerType(ShowcaseTransfer.NAME) == Transfer.registerType(ShowcaseTransfer.NAME)
                        ? "the same type"
                        : "two types"));
        return checks;
    }

    /**
     * The data of the page for a native type, in a drag from the label or the table (interactive mode).
     */
    private static Object dataFor(State s, TransferData type) {
        if (TextTransfer.getInstance().isSupportedType(type)) {
            return TEXT;
        } else if (RTFTransfer.getInstance().isSupportedType(type)) {
            return RTF;
        } else if (HTMLTransfer.getInstance().isSupportedType(type)) {
            return HTML;
        } else if (URLTransfer.getInstance().isSupportedType(type)) {
            return URL;
        } else if (FileTransfer.getInstance().isSupportedType(type)) {
            return files();
        } else if (ImageTransfer.getInstance().isSupportedType(type)) {
            return s.generated;
        } else if (s.transfers.showcase().isSupportedType(type)) {
            return CUSTOM.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * A file list of one existing entry : the working directory.
     */
    static String[] files() {
        return new String[] { Path.of("").toAbsolutePath().toString() };
    }

    static String describe(Object data) {
        return switch (data) {
            case null -> "nothing";
            case String text -> "text : " + (text.length() > 24 ? text.substring(0, 24) + "..." : text);
            case String[] files -> files.length + " file(s)";
            case ImageData image -> "an image " + image.width + "x" + image.height;
            case byte[] bytes -> bytes.length + " bytes";
            default -> data.getClass().getSimpleName();
        };
    }

    private static String operations(int style) {
        List<String> names = new ArrayList<>();
        if ((style & DND.DROP_COPY) != 0) {
            names.add("COPY");
        }
        if ((style & DND.DROP_MOVE) != 0) {
            names.add("MOVE");
        }
        if ((style & DND.DROP_LINK) != 0) {
            names.add("LINK");
        }
        if ((style & DND.DROP_TARGET_MOVE) != 0) {
            names.add("TARGET_MOVE");
        }
        if ((style & DND.DROP_DEFAULT) != 0) {
            names.add("DEFAULT");
        }
        return String.join(" ", names);
    }

    /**
     * The names of {@code transfers} (the instances of the page, compared by identity).
     */
    private static String names(State s, Transfer[] transfers) {
        List<Transfer> all = s.transfers.all();
        List<String> names = new ArrayList<>();
        for (Transfer transfer : transfers) {
            int index = all.indexOf(transfer);
            names.add(index < 0 ? transfer.getClass().getSimpleName() : TRANSFER_NAMES.get(index));
        }
        return String.join(" ", names);
    }

    private static String keys(Control control, DragSource source, DropTarget target) {
        List<String> keys = new ArrayList<>();
        Object dragSource = control.getData(DND.DRAG_SOURCE_KEY);
        Object dropTarget = control.getData(DND.DROP_TARGET_KEY);
        if (dragSource != null) {
            keys.add(dragSource == source ? "source" : "another source");
        }
        if (dropTarget != null) {
            keys.add(dropTarget == target ? "target" : "another target");
        }
        return keys.isEmpty() ? "none" : String.join(", ", keys);
    }

    private static String effect(Object effect) {
        return effect == null ? "none" : effect.getClass().getSimpleName();
    }

    /**
     * {@code SWTError <code> <message>}, the simple name of another exception, or {@code no exception}.
     */
    static String thrown(Runnable action) {
        try {
            action.run();
            return "no exception";
        } catch (SWTError e) {
            return "SWTError " + e.code + " " + e.getMessage();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * {@code true}, or the pairs {@code transfer/type of transfer} where {@code isSupportedType} is wrong.
     */
    private static String supportsOwnTypesOnly(List<Transfer> transfers) {
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < transfers.size(); i++) {
            for (int j = 0; j < transfers.size(); j++) {
                for (TransferData data : transfers.get(j).getSupportedTypes()) {
                    if (transfers.get(i).isSupportedType(data) != (i == j)) {
                        wrong.add(TRANSFER_NAMES.get(i) + "/" + TRANSFER_NAMES.get(j));
                    }
                }
            }
        }
        return wrong.isEmpty() ? "true" : String.join(" ", wrong);
    }

    /**
     * The native names of the supported types of {@code transfer} : the predefined Windows formats, the names SWT
     * registers (registered again, they give the same type), or {@code #<type>}.
     */
    private static String typeNames(Transfer transfer) {
        List<String> candidates = new ArrayList<>(SwtMode.pick(List.<String> of(),
                List.of("Rich Text Format", "HTML Format", "UniformResourceLocatorW", "UniformResourceLocator",
                        "Shell IDList Array"),
                List.of("UTF8_STRING", "COMPOUND_TEXT", "STRING", "text/plain", "text/plain;charset=utf-8",
                        "text/rtf", "TEXT/RTF", "application/rtf", "text/html", "TEXT/HTML", "text/unicode",
                        "text/x-moz-url", "text/uri-list", "x-special/gnome-copied-files", "image/png", "image/bmp",
                        "image/jpeg", "image/eps", "image/pcx", "image/ppm", "image/tga", "image/xbm", "image/xpm",
                        "image/xv")));
        candidates.add(ShowcaseTransfer.NAME);
        List<String> names = new ArrayList<>();
        for (TransferData data : transfer.getSupportedTypes()) {
            long type = data.type;
            String name = SwtMode.isWindows() ? predefinedWindowsFormat(type) : null;
            for (int i = 0; name == null && i < candidates.size(); i++) {
                if (Transfer.registerType(candidates.get(i)) == type) {
                    name = candidates.get(i);
                }
            }
            names.add(name == null ? "#" + type : name);
        }
        return String.join(" ", names);
    }

    private static String predefinedWindowsFormat(long type) {
        return switch ((int) type) {
            case 1 -> "CF_TEXT";
            case 8 -> "CF_DIB";
            case 13 -> "CF_UNICODETEXT";
            case 15 -> "CF_HDROP";
            default -> null;
        };
    }

    // -------------------------------------------------------------------------------------------- transfer types

    /**
     * The transfer types of one build : the SWT singletons, the type of the page, and on Windows the formats that keep
     * the data of the page out of the clipboard history (not a transfer of the drag and drop).
     */
    record Transfers(ShowcaseTransfer showcase, HistoryExclusionTransfer exclusion) {

        List<Transfer> all() {
            return List.of(TextTransfer.getInstance(), RTFTransfer.getInstance(), HTMLTransfer.getInstance(),
                    URLTransfer.getInstance(), FileTransfer.getInstance(), ImageTransfer.getInstance(), showcase);
        }
    }

    /**
     * A transfer type of the page : bytes under a native type of its own ({@code Transfer.registerType} :
     * {@code RegisterClipboardFormat} on Windows, an atom on GTK), registered when it is created (never in a static
     * initializer : Quarkus initializes the application classes at build time).
     */
    static final class ShowcaseTransfer extends ByteArrayTransfer {

        static final String NAME = "application/x-quarkus-desktop-showcase";

        private final int typeId = registerType(NAME);

        @Override
        protected String[] getTypeNames() {
            return new String[] { NAME };
        }

        @Override
        protected int[] getTypeIds() {
            return new int[] { typeId };
        }

        @Override
        protected boolean validate(Object object) {
            return object instanceof byte[] bytes && bytes.length > 0;
        }
    }

    /**
     * Windows only : the {@code CanIncludeInClipboardHistory} and {@code CanUploadToCloudClipboard} formats, a
     * {@code DWORD} of zero, which keep the clipboard data out of the clipboard history and of the cloud clipboard.
     */
    static final class HistoryExclusionTransfer extends ByteArrayTransfer {

        static final String[] NAMES = { "CanIncludeInClipboardHistory", "CanUploadToCloudClipboard" };

        private final int[] typeIds = { registerType(NAMES[0]), registerType(NAMES[1]) };

        static byte[] zero() {
            return new byte[4];
        }

        @Override
        protected String[] getTypeNames() {
            return NAMES.clone();
        }

        @Override
        protected int[] getTypeIds() {
            return typeIds.clone();
        }
    }

    // ------------------------------------------------------------------------------------------ clipboard session

    record Result(List<Check> checks, ImageData image) {
    }

    /**
     * The clipboard work of one display of the page (on the user interface thread, the event loop running between the
     * steps), and the restoration of the user's clipboard when the page is left : by {@link #dispose()} if the work is
     * over, else at the end of the work.
     */
    static final class ClipboardSession {

        private final Clipboard clipboard;
        private final Transfers transfers;
        private final ImageData generated;
        private final TextTransfer text = TextTransfer.getInstance();
        private final RTFTransfer rtf = RTFTransfer.getInstance();
        private final HTMLTransfer html = HTMLTransfer.getInstance();
        private final URLTransfer url = URLTransfer.getInstance();
        private final FileTransfer file = FileTransfer.getInstance();
        private final ImageTransfer image = ImageTransfer.getInstance();
        private final List<Object> savedData = new ArrayList<>();
        private final List<Transfer> savedTypes = new ArrayList<>();
        private boolean running;
        private boolean disposed;

        ClipboardSession(Display display, Transfers transfers, ImageData generated) {
            this.clipboard = new Clipboard(display);
            this.transfers = transfers;
            this.generated = generated;
        }

        CompletionStage<Result> run() {
            running = true;
            List<Check> checks = new ArrayList<>();
            try {
                save();
                checks.add(Check.info("the user's clipboard", "saved, restored when the page is left"));
            } catch (Throwable t) {
                checks.add(Check.fail("the user's clipboard", "not saved: " + SwtChecks.describe(t)));
            }
            ImageData[] readBack = new ImageData[1];
            List<Supplier<CompletionStage<List<Check>>>> steps = List.of(this::textStep, this::richStep,
                    () -> imageStep(readBack), this::filesStep, this::asyncStep, this::clearStep);
            CompletionStage<List<Check>> chain = CompletableFuture.completedFuture(List.of());
            for (Supplier<CompletionStage<List<Check>>> step : steps) {
                chain = chain.thenCompose(previous -> {
                    checks.addAll(previous);
                    return step.get();
                });
            }
            return chain.thenApply(last -> {
                checks.addAll(last);
                checks.addAll(validation());
                return new Result(checks, readBack[0]);
            }).whenComplete((result, error) -> finish());
        }

        private CompletionStage<List<Check>> textStep() {
            return step("text", new Object[] { TEXT }, new Transfer[] { text }, () -> List.of(
                    SwtChecks.expect("text : getContents(Text)", TEXT, () -> get(text)),
                    SwtChecks.expect("text : getContents(RTF, HTML, Image)", "null null null",
                            () -> get(rtf) + " " + get(html) + " " + get(image)),
                    SwtChecks.expect("text : getContents(Text, DND.CLIPBOARD)", TEXT,
                            () -> clipboard.getContents(text, DND.CLIPBOARD))));
        }

        /**
         * Several formats at once, as an application copies rich text.
         */
        private CompletionStage<List<Check>> richStep() {
            ShowcaseTransfer showcase = transfers.showcase();
            Object[] data = { RICH_TEXT, RTF, HTML, URL, CUSTOM.getBytes(StandardCharsets.UTF_8) };
            return step("rich contents", data, new Transfer[] { text, rtf, html, url, showcase }, () -> List.of(
                    SwtChecks.expect("rich : getContents(Text)", RICH_TEXT, () -> get(text)),
                    SwtChecks.expect("rich : getContents(RTF)", RTF, () -> get(rtf)),
                    SwtChecks.expect("rich : getContents(HTML) (the fragment)", HTML, () -> get(html)),
                    SwtChecks.expect("rich : getContents(URL)", URL, () -> get(url)),
                    SwtChecks.expect("rich : getContents(Showcase)", CUSTOM, () -> get(showcase) instanceof byte[] bytes
                            ? new String(bytes, StandardCharsets.UTF_8)
                            : "not bytes: " + get(showcase)),
                    SwtChecks.expect("rich : getAvailableTypes() supported by", "Text RTF HTML URL Showcase",
                            this::availableTransfers),
                    // the formats the system synthesizes, and those another application may add
                    SwtChecks.info("rich : getAvailableTypeNames() (sorted)", () -> String.join(" ",
                            new TreeSet<>(Arrays.asList(clipboard.getAvailableTypeNames()))))));
        }

        private CompletionStage<List<Check>> imageStep(ImageData[] readBack) {
            return step("image", new Object[] { generated }, new Transfer[] { image }, () -> {
                Object back = get(image);
                readBack[0] = back instanceof ImageData data ? data : null;
                return List.of(
                        SwtChecks.expect("image : getContents(Image) size", "64x48",
                                () -> back instanceof ImageData data ? data.width + "x" + data.height
                                        : String.valueOf(back)),
                        SwtChecks.expect("image : RGB pixels (SHA-256)", rgbDigest(generated),
                                () -> back instanceof ImageData data ? rgbDigest(data) : String.valueOf(back)),
                        SwtChecks.expect("image : getContents(Text)", "null", () -> String.valueOf(get(text))));
            });
        }

        private CompletionStage<List<Check>> filesStep() {
            String[] files = files();
            return step("files", new Object[] { files }, new Transfer[] { file }, () -> List.of(
                    SwtChecks.expect("files : getContents(File) (working dir.)", "1 entry, the same path", () -> {
                        Object back = get(file);
                        if (!(back instanceof String[] paths)) {
                            return String.valueOf(back);
                        }
                        return paths.length + " entry, " + (Arrays.equals(paths, files) ? "the same path"
                                : "another path");
                    })));
        }

        /**
         * {@code getContentsAsync} : a future, completed later by the event loop on GTK 4, at once elsewhere.
         */
        private CompletionStage<List<Check>> asyncStep() {
            return attempt("getContentsAsync", new Object[] { TEXT }, new Transfer[] { text },
                    () -> UiStages.timeout(clipboard.getContentsAsync(text), 5_000, "getContentsAsync")
                            .handle((value, error) -> List.of(error != null
                                    ? Check.fail("getContentsAsync(Text)", SwtChecks.describe(error))
                                    : SwtChecks.expect("getContentsAsync(Text)", TEXT, () -> value))),
                    1);
        }

        private CompletionStage<List<Check>> clearStep() {
            return step("clear", new Object[] { TEXT }, new Transfer[] { text }, () -> {
                clipboard.clearContents();
                return List.of(SwtChecks.expect("clearContents() : getContents(Text)", "null",
                        () -> String.valueOf(get(text))));
            });
        }

        /**
         * The data is validated before the clipboard is touched : {@code IllegalArgumentException}.
         */
        private List<Check> validation() {
            ShowcaseTransfer showcase = transfers.showcase();
            return List.of(
                    SwtChecks.expect("setContents : an empty text", "IllegalArgumentException",
                            () -> thrown(() -> clipboard.setContents(new Object[] { "" }, new Transfer[] { text }))),
                    SwtChecks.expect("setContents : a String for Showcase",
                            "IllegalArgumentException", () -> thrown(() -> clipboard.setContents(
                                    new Object[] { CUSTOM }, new Transfer[] { showcase }))),
                    SwtChecks.expect("setContents : 1 data for 2 transfers", "IllegalArgumentException",
                            () -> thrown(() -> clipboard.setContents(new Object[] { TEXT },
                                    new Transfer[] { text, rtf }))),
                    SwtChecks.expect("setContents : no data", "IllegalArgumentException",
                            () -> thrown(() -> clipboard.setContents(new Object[0], new Transfer[0]))));
        }

        private Object get(Transfer transfer) {
            return clipboard.getContents(transfer);
        }

        private String availableTransfers() {
            TransferData[] available = clipboard.getAvailableTypes();
            List<Transfer> all = transfers.all();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Transfer transfer = all.get(i);
                if (Arrays.stream(available).anyMatch(transfer::isSupportedType)) {
                    names.add(TRANSFER_NAMES.get(i));
                }
            }
            return String.join(" ", names);
        }

        private CompletionStage<List<Check>> step(String name, Object[] data, Transfer[] types,
                Supplier<List<Check>> verify) {
            return attempt(name, data, types, () -> CompletableFuture.completedFuture(verify.get()), 1);
        }

        /**
         * Writes {@code data}, then verifies it : up to {@link #ATTEMPTS} times while a check fails (another
         * application may have changed the clipboard meanwhile). Completes on the user interface thread, after the
         * event loop ran.
         */
        private CompletionStage<List<Check>> attempt(String name, Object[] data, Transfer[] types,
                Supplier<CompletionStage<List<Check>>> verify, int attempt) {
            if (disposed) {
                return CompletableFuture.completedFuture(List.of(Check.info(name, "cancelled")));
            }
            CompletionStage<List<Check>> verified;
            try {
                set(data, types);
                verified = verify.get();
            } catch (Throwable t) {
                verified = CompletableFuture.completedFuture(List.of(Check.fail(name + " : setContents",
                        SwtChecks.describe(t))));
            }
            return verified.thenCompose(checks -> {
                if (attempt >= ATTEMPTS || checks.stream().noneMatch(c -> Boolean.FALSE.equals(c.ok()))) {
                    return UiStages.rounds(2).thenApply(v -> checks);
                }
                LOG.infof("Clipboard step %s failed (attempt %d) : retried", name, attempt);
                return UiStages.delay(250).thenCompose(v -> attempt(name, data, types, verify, attempt + 1));
            });
        }

        /**
         * {@code setContents}, with the history exclusion formats on Windows.
         */
        private void set(Object[] data, Transfer[] types) {
            HistoryExclusionTransfer exclusion = transfers.exclusion();
            if (exclusion == null) {
                clipboard.setContents(data, types);
                return;
            }
            Object[] allData = Arrays.copyOf(data, data.length + 1);
            allData[data.length] = HistoryExclusionTransfer.zero();
            Transfer[] allTypes = Arrays.copyOf(types, types.length + 1);
            allTypes[types.length] = exclusion;
            clipboard.setContents(allData, allTypes);
        }

        /**
         * Saves the contents of the user's clipboard that SWT can read back and write again.
         */
        private void save() {
            for (Transfer transfer : List.of(text, rtf, html, url, file, image)) {
                Object value = clipboard.getContents(transfer);
                boolean valid = switch (value) {
                    case null -> false;
                    case String string -> !string.isEmpty();
                    case String[] strings -> strings.length > 0;
                    default -> true;
                };
                if (valid) {
                    savedData.add(value);
                    savedTypes.add(transfer);
                }
            }
        }

        private void finish() {
            running = false;
            if (disposed) {
                restore();
            }
        }

        void dispose() {
            disposed = true;
            if (!running) {
                restore();
            }
        }

        /**
         * Writes the saved contents back, unless another application changed the clipboard meanwhile (its data is
         * newer), and disposes the clipboard : SWT flushes the contents it owns ({@code OleFlushClipboard} on
         * Windows), which outlive the {@code Clipboard} and the application.
         */
        private void restore() {
            if (clipboard.isDisposed()) {
                return;
            }
            try {
                Object current = clipboard.getContents(text);
                if (current != null && !OWN_TEXTS.contains(current)) {
                    LOG.warn("The clipboard was changed by another application during the data transfer page :"
                            + " not restored");
                } else if (!savedData.isEmpty()) {
                    clipboard.setContents(savedData.toArray(), savedTypes.toArray(Transfer[]::new));
                } else if (current != null) {
                    clipboard.clearContents();
                }
            } catch (Throwable t) {
                LOG.warn("The user's clipboard could not be restored", t);
            } finally {
                clipboard.dispose();
            }
        }
    }

    /**
     * SHA-256 of the RGB pixels of {@code data} (first 16 hex digits) : the clipboard bitmap formats drop the alpha
     * channel, and the depth of the image read back is the one of the platform.
     */
    static String rgbDigest(ImageData data) {
        int[] argb = SwtSnapshots.argb(data);
        byte[] bytes = new byte[argb.length * 3 + 8];
        int i = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            bytes[i++] = (byte) (data.width >> shift);
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            bytes[i++] = (byte) (data.height >> shift);
        }
        for (int pixel : argb) {
            bytes[i++] = (byte) (pixel >> 16);
            bytes[i++] = (byte) (pixel >> 8);
            bytes[i++] = (byte) pixel;
        }
        return SwtChecks.sha256(bytes);
    }
}
