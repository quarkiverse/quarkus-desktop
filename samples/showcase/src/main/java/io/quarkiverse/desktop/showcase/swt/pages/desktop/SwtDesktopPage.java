package io.quarkiverse.desktop.showcase.swt.pages.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;
import org.eclipse.swt.widgets.Tray;
import org.eclipse.swt.widgets.TrayItem;

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
 * Desktop integration with SWT : {@link Program} (the registered extensions, the programs of a few file types and
 * their icons ; no program is ever launched), the {@link Display} services (monitors, system tray, task bar, system
 * menu, application name and version, dark theme), the system colors, images and cursors, and three secondary shells
 * shown beside the main window and rendered as extra snapshots : a translucent shell ({@code Shell.setAlpha}), a
 * shaped shell ({@code Shell.setRegion}) and a shell resized below its minimum size ({@code Shell.setMinimumSize}). A
 * {@link TrayItem} is added to the system tray and removed at once : no tray icon is left behind.
 * <p>
 * {@code Control.print} renders the content of a shell without its alpha and its region : the extra snapshots compose
 * them with the content. On Windows, the renders of a shell with a window region are unreliable (from one render to
 * the next, parts of what its canvas painted are missing, its paint listener not being called again) : the shaped
 * shell is rendered with its region removed for a moment.
 * <p>
 * Native code paths. Windows : the registry ({@code RegEnumKeyEx} of {@code HKEY_CLASSES_ROOT}),
 * {@code AssocQueryString} for the program of an extension, {@code SHDefExtractIcon} and {@code SHGetFileInfo} for
 * its icon (an {@code HICON} converted to an {@code ImageData} with its alpha), {@code EnumDisplayMonitors} and
 * {@code GetDpiForMonitor}, {@code Shell_NotifyIcon} (the tray item and its {@code NOTIFYICONDATA}, whose messages
 * come back through the window procedure callback), the {@code ITaskbarList3} COM object of the task bar,
 * {@code GetSysColor} and the dark theme from the registry, {@code LoadCursor} and {@code CreateIconIndirect},
 * {@code SetLayeredWindowAttributes} for the alpha, {@code SetWindowRgn} for the region, and
 * {@code WM_GETMINMAXINFO} and {@code WM_WINDOWPOSCHANGING}, whose {@code MINMAXINFO} and {@code WINDOWPOS} structs SWT
 * reads and writes in its window procedure, for the minimum size. GTK : GIO content types and application infos,
 * {@code GdkMonitor}, {@code GtkStatusIcon}, the colors of the GTK theme, {@code GdkCursor},
 * {@code gtk_widget_set_opacity}, {@code gdk_window_shape_combine_region} and the geometry hints. Cocoa :
 * {@code NSWorkspace}, {@code NSScreen}, {@code NSStatusBar}, the dock tile, {@code NSCursor}, the alpha value and the
 * shape of the {@code NSWindow}.
 * <p>
 * Why it matters for a native executable : the window procedure and the messages it decodes are reached through a JNI
 * callback, the structs through field ids looked up by name : a missing registration breaks the minimum size or the
 * tray at run time only. The pixels of the page depend on the machine only where the desktop does (the icons of the
 * programs, the colors and images of the theme), and the names of the programs and the monitors are in the checks :
 * the JVM and the native executable of one machine render the same pixels.
 */
@Singleton
public class SwtDesktopPage implements SwtPage {

    /** The extensions whose program is looked up. */
    static final List<String> EXTENSIONS = List.of(".txt", ".html", ".png", ".pdf");

    /** A constant of {@code SWT} and its name. */
    record Named(String name, int id) {
    }

    static final List<Named> COLORS = List.of(new Named("WHITE", SWT.COLOR_WHITE), new Named("BLACK", SWT.COLOR_BLACK),
            new Named("RED", SWT.COLOR_RED), new Named("DARK_RED", SWT.COLOR_DARK_RED),
            new Named("GREEN", SWT.COLOR_GREEN), new Named("DARK_GREEN", SWT.COLOR_DARK_GREEN),
            new Named("YELLOW", SWT.COLOR_YELLOW), new Named("DARK_YELLOW", SWT.COLOR_DARK_YELLOW),
            new Named("BLUE", SWT.COLOR_BLUE), new Named("DARK_BLUE", SWT.COLOR_DARK_BLUE),
            new Named("MAGENTA", SWT.COLOR_MAGENTA), new Named("DARK_MAGENTA", SWT.COLOR_DARK_MAGENTA),
            new Named("CYAN", SWT.COLOR_CYAN), new Named("DARK_CYAN", SWT.COLOR_DARK_CYAN),
            new Named("GRAY", SWT.COLOR_GRAY), new Named("DARK_GRAY", SWT.COLOR_DARK_GRAY),
            new Named("WIDGET_DARK_SHADOW", SWT.COLOR_WIDGET_DARK_SHADOW),
            new Named("WIDGET_NORMAL_SHADOW", SWT.COLOR_WIDGET_NORMAL_SHADOW),
            new Named("WIDGET_LIGHT_SHADOW", SWT.COLOR_WIDGET_LIGHT_SHADOW),
            new Named("WIDGET_HIGHLIGHT_SHADOW", SWT.COLOR_WIDGET_HIGHLIGHT_SHADOW),
            new Named("WIDGET_FOREGROUND", SWT.COLOR_WIDGET_FOREGROUND),
            new Named("WIDGET_BACKGROUND", SWT.COLOR_WIDGET_BACKGROUND),
            new Named("WIDGET_BORDER", SWT.COLOR_WIDGET_BORDER),
            new Named("LIST_FOREGROUND", SWT.COLOR_LIST_FOREGROUND),
            new Named("LIST_BACKGROUND", SWT.COLOR_LIST_BACKGROUND),
            new Named("LIST_SELECTION", SWT.COLOR_LIST_SELECTION),
            new Named("LIST_SELECTION_TEXT", SWT.COLOR_LIST_SELECTION_TEXT),
            new Named("INFO_FOREGROUND", SWT.COLOR_INFO_FOREGROUND),
            new Named("INFO_BACKGROUND", SWT.COLOR_INFO_BACKGROUND),
            new Named("TITLE_FOREGROUND", SWT.COLOR_TITLE_FOREGROUND),
            new Named("TITLE_BACKGROUND", SWT.COLOR_TITLE_BACKGROUND),
            new Named("TITLE_BACKGROUND_GRADIENT", SWT.COLOR_TITLE_BACKGROUND_GRADIENT),
            new Named("TITLE_INACTIVE_FOREGROUND", SWT.COLOR_TITLE_INACTIVE_FOREGROUND),
            new Named("TITLE_INACTIVE_BACKGROUND", SWT.COLOR_TITLE_INACTIVE_BACKGROUND),
            new Named("TITLE_INACTIVE_BACKGROUND_GRADIENT", SWT.COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT),
            new Named("LINK_FOREGROUND", SWT.COLOR_LINK_FOREGROUND),
            new Named("TRANSPARENT", SWT.COLOR_TRANSPARENT),
            new Named("TEXT_DISABLED_BACKGROUND", SWT.COLOR_TEXT_DISABLED_BACKGROUND),
            new Named("WIDGET_DISABLED_FOREGROUND", SWT.COLOR_WIDGET_DISABLED_FOREGROUND));

    /** The 16 basic colors, the same on every platform. */
    static final String BASIC_COLORS = "#FFFFFF #000000 #FF0000 #800000 #00FF00 #008000 #FFFF00 #808000 #0000FF"
            + " #000080 #FF00FF #800080 #00FFFF #008080 #C0C0C0 #808080";

    static final List<Named> CURSORS = List.of(new Named("ARROW", SWT.CURSOR_ARROW), new Named("WAIT", SWT.CURSOR_WAIT),
            new Named("CROSS", SWT.CURSOR_CROSS), new Named("APPSTARTING", SWT.CURSOR_APPSTARTING),
            new Named("HELP", SWT.CURSOR_HELP), new Named("SIZEALL", SWT.CURSOR_SIZEALL),
            new Named("SIZENESW", SWT.CURSOR_SIZENESW), new Named("SIZENS", SWT.CURSOR_SIZENS),
            new Named("SIZENWSE", SWT.CURSOR_SIZENWSE), new Named("SIZEWE", SWT.CURSOR_SIZEWE),
            new Named("SIZEN", SWT.CURSOR_SIZEN), new Named("SIZES", SWT.CURSOR_SIZES),
            new Named("SIZEE", SWT.CURSOR_SIZEE), new Named("SIZEW", SWT.CURSOR_SIZEW),
            new Named("SIZENE", SWT.CURSOR_SIZENE), new Named("SIZESE", SWT.CURSOR_SIZESE),
            new Named("SIZESW", SWT.CURSOR_SIZESW), new Named("SIZENW", SWT.CURSOR_SIZENW),
            new Named("UPARROW", SWT.CURSOR_UPARROW), new Named("IBEAM", SWT.CURSOR_IBEAM),
            new Named("NO", SWT.CURSOR_NO), new Named("HAND", SWT.CURSOR_HAND));

    static final List<Named> SYSTEM_IMAGES = List.of(new Named("ERROR", SWT.ICON_ERROR),
            new Named("INFORMATION", SWT.ICON_INFORMATION), new Named("QUESTION", SWT.ICON_QUESTION),
            new Named("WARNING", SWT.ICON_WARNING), new Named("WORKING", SWT.ICON_WORKING));

    static final String TRAY_TOOL_TIP = "Quarkus Desktop Showcase";

    private static final int HALF = 494;
    private static final int NAME_WIDTH = 330;
    private static final int TILE_WIDTH = 100;
    private static final int ICON = 32;
    private static final int COLOR_COLUMNS = 3;
    private static final int SWATCH_WIDTH = 36;
    private static final int CURSOR_COLUMNS = 8;
    private static final int SWATCH_BORDER = 0x9E9E9E;
    private static final int PANEL = 0xF3F4F6;
    /** The secondary shells : their size, the gap between them and beside the main window. */
    private static final int SHELL_WIDTH = 260;
    private static final int SHELL_HEIGHT = 120;
    private static final int SHELL_GAP = 24;
    private static final int ALPHA = 160;
    private static final int RENDER_ATTEMPTS = 10;
    private static final int MINIMUM_HEIGHT = 100;
    private static final int TOO_SMALL_WIDTH = 120;
    private static final int TOO_SMALL_HEIGHT = 50;
    /** The outline of the shaped shell : a tag, with a hole. */
    private static final int[] TAG = { 0, 16, 16, 0, 200, 0, 260, 60, 200, 120, 16, 120, 0, 104 };
    private static final int[] HOLE = { 214, 56, 218, 52, 226, 52, 230, 56, 230, 64, 226, 68, 218, 68, 214, 64 };

    // per build state (one content at a time)
    private State state;

    /** What one build of the page holds : the secondary shells and the region of the shaped one. */
    private static final class State {
        Shell alphaShell;
        Shell shapedShell;
        Shell minimumShell;
        Region region;
        ChecksTable shellsTable;
    }

    @Override
    public String id() {
        return "swt-desktop";
    }

    @Override
    public String title() {
        return "Programs, display services and shells";
    }

    @Override
    public String category() {
        return SwtCategories.DESKTOP;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Control build(Composite parent) {
        Display display = parent.getDisplay();
        State s = new State();
        state = s;
        shells(display, parent.getShell(), s);

        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "Programs registered for a few file types (never launched), the display services, the system"
                + " colors, images and cursors (hover the cursor names), and three secondary shells beside the main"
                + " window: translucent (setAlpha), shaped (setRegion) and resized below its minimum size"
                + " (setMinimumSize). A tray item is added to the system tray and removed at once.", SwtKit.TEXT_WIDTH);
        List<Check> programChecks = new ArrayList<>();
        Composite strips = SwtKit.row(page, 32);
        Composite programs = SwtKit.column(strips, 6);
        SwtKit.title(programs, "Programs (Program.findProgram)");
        SwtKit.painted(programs, EXTENSIONS.size() * TILE_WIDTH, tileHeight(display),
                programTiles(display, programChecks));
        Composite images = SwtKit.column(strips, 6);
        SwtKit.title(images, "System images (Display.getSystemImage)");
        SwtKit.painted(images, SYSTEM_IMAGES.size() * TILE_WIDTH, tileHeight(display), systemImageTiles(display));

        SwtKit.title(page, "System colors (Display.getSystemColor)");
        int rows = (COLORS.size() + COLOR_COLUMNS - 1) / COLOR_COLUMNS;
        int rowHeight = textHeight(display, SwtKit.font(SWT.NORMAL, 8)) + 6;
        SwtKit.painted(page, SwtKit.TEXT_WIDTH, rows * rowHeight, gc -> paintColors(gc, display, rowHeight));

        SwtKit.title(page, "System cursors (Display.getSystemCursor : hover the names)");
        cursorGrid(page, display);

        ChecksTable.table(page, "Program", programChecks, NAME_WIDTH, ChecksTable.WIDTH);
        Composite row = SwtKit.row(page, 12);
        ChecksTable.table(row, "Display and monitors", displayChecks(display), 210, HALF);
        s.shellsTable = ChecksTable.table(row, "Secondary shells (extra snapshots)",
                List.of(Check.info("state", "pending")), 210, HALF);
        ChecksTable.table(page, "System tray, task bar and cursors", trayAndCursors(display, parent.getShell()),
                NAME_WIDTH, ChecksTable.WIDTH);
        ChecksTable.table(page, "System colors", colorChecks(display), NAME_WIDTH, ChecksTable.WIDTH);
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        State s = state;
        return SwtSnapshots.settle(SwtSnapshots.SETTLE_MILLIS).thenAccept(v -> {
            if (!s.shellsTable.isDisposed()) {
                s.shellsTable.setChecks(shellChecks(content.getShell(), s));
            }
        });
    }

    @Override
    public CompletionStage<Map<String, ImageData>> extraSnapshots(Control content) {
        State s = state;
        Map<String, ImageData> images = new LinkedHashMap<>();
        // Control.print renders the content of a shell, without its alpha and its region : composed here
        return SwtSnapshots.settle(SwtSnapshots.SETTLE_MILLIS)
                .thenCompose(v -> renderStable(s.alphaShell.getChildren()[0]))
                .thenCompose(alpha -> {
                    images.put("alpha-shell", compose(alpha, (x, y) -> s.alphaShell.getAlpha()));
                    // Windows : the renders of a shell with a window region are unreliable (from one render to the
                    // next, parts of what the canvas painted are missing) : rendered without its region, then masked
                    s.shapedShell.setRegion(null);
                    return SwtSnapshots.settle(SwtSnapshots.SETTLE_MILLIS)
                            .thenCompose(v -> renderStable(s.shapedShell.getChildren()[0]))
                            .whenComplete((image, error) -> {
                                if (!s.shapedShell.isDisposed() && !s.region.isDisposed()) {
                                    s.shapedShell.setRegion(s.region);
                                }
                            });
                })
                .thenCompose(shaped -> {
                    images.put("shaped-shell", compose(shaped, (x, y) -> s.region.contains(x, y) ? 0xFF : 0));
                    return renderStable(s.minimumShell.getChildren()[0]);
                })
                .thenApply(minimum -> {
                    images.put("minimum-size-shell", minimum);
                    return images;
                });
    }

    /**
     * Renders {@code control} (repainted first) until two renders in a row give the same pixels, at most
     * {@link #RENDER_ATTEMPTS} times, 100 ms apart : a render of a shell just changed is not always the last one.
     */
    private static CompletionStage<ImageData> renderStable(Control control) {
        control.redraw();
        control.update();
        CompletableFuture<ImageData> done = new CompletableFuture<>();
        renderStable(control, null, 1, done);
        return done;
    }

    private static void renderStable(Control control, int[] previous, int attempt, CompletableFuture<ImageData> done) {
        UiStages.delay(100).thenAccept(v -> {
            try {
                ImageData image = SwtSnapshots.render(control);
                int[] pixels = SwtSnapshots.argb(image);
                if (Arrays.equals(previous, pixels) || attempt >= RENDER_ATTEMPTS) {
                    done.complete(image);
                } else {
                    renderStable(control, pixels, attempt + 1, done);
                }
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
    }

    @Override
    public void dispose(Control content) {
        State s = state;
        state = null;
        if (s == null) {
            return;
        }
        for (Shell shell : new Shell[] { s.alphaShell, s.shapedShell, s.minimumShell }) {
            if (shell != null && !shell.isDisposed()) {
                shell.dispose();
            }
        }
        if (s.region != null && !s.region.isDisposed()) {
            s.region.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------- secondary shells

    /**
     * Creates and shows the three secondary shells beside the main window (owned by it, no task bar button, shown
     * without being activated).
     */
    private static void shells(Display display, Shell main, State s) {
        Rectangle bounds = main.getBounds();
        int x = bounds.x + bounds.width + SHELL_GAP;
        int y = bounds.y;

        s.alphaShell = shell(main, (gc, size) -> paintShell(gc, size, 0x0D47A1, 0x42A5F5,
                "Shell.setAlpha(" + ALPHA + ")", "translucent : " + ALPHA + " / 255"));
        s.alphaShell.setBounds(x, y, SHELL_WIDTH, SHELL_HEIGHT);
        s.alphaShell.setAlpha(ALPHA);

        s.region = new Region(display);
        s.region.add(TAG);
        s.region.subtract(HOLE);
        s.shapedShell = shell(main, (gc, size) -> paintShell(gc, size, 0xE65100, 0xFFB74D, "Shell.setRegion",
                "a tag shaped shell"));
        // the region sizes a NO_TRIM shell (its bounds)
        s.shapedShell.setRegion(s.region);
        s.shapedShell.setLocation(x, y + SHELL_HEIGHT + SHELL_GAP);

        Shell[] minimum = new Shell[1];
        minimum[0] = shell(main, (gc, size) -> paintShell(gc, size, 0x1B5E20, 0x66BB6A,
                "setMinimumSize(" + SHELL_WIDTH + ", " + MINIMUM_HEIGHT + ")",
                "set to " + TOO_SMALL_WIDTH + "x" + TOO_SMALL_HEIGHT + ", gives "
                        + SwtChecks.size(minimum[0].getSize())));
        s.minimumShell = minimum[0];
        s.minimumShell.setMinimumSize(SHELL_WIDTH, MINIMUM_HEIGHT);
        s.minimumShell.setBounds(x, y + 2 * (SHELL_HEIGHT + SHELL_GAP), TOO_SMALL_WIDTH, TOO_SMALL_HEIGHT);

        for (Shell shell : new Shell[] { s.alphaShell, s.shapedShell, s.minimumShell }) {
            shell.layout(true, true);
            // shown without taking the focus
            shell.setVisible(true);
        }
    }

    /**
     * A shell without trim and without a task bar button, its content a canvas filling it.
     */
    private static Shell shell(Shell main, BiConsumer<GC, Point> painter) {
        Shell shell = new Shell(main, SWT.NO_TRIM | SWT.TOOL);
        shell.setLayout(new FillLayout());
        Canvas content = new Canvas(shell, SWT.NO_FOCUS);
        content.addListener(SWT.Paint, event -> painter.accept(event.gc, content.getSize()));
        return shell;
    }

    /**
     * Paints the content of a secondary shell : a vertical gradient, a title and a line of text.
     */
    private static void paintShell(GC gc, Point size, int top, int bottom, String title, String text) {
        gc.setForeground(SwtKit.color(top));
        gc.setBackground(SwtKit.color(bottom));
        gc.fillGradientRectangle(0, 0, size.x, size.y, true);
        gc.setForeground(SwtKit.color(0xFFFFFF));
        gc.setFont(SwtKit.font(SWT.BOLD, 9));
        gc.drawString(title, 16, 20, true);
        int lineHeight = gc.getFontMetrics().getHeight();
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        gc.drawString(text, 16, 26 + lineHeight, true);
    }

    @FunctionalInterface
    interface AlphaFunction {
        int alpha(int x, int y);
    }

    /**
     * {@code data} with the alpha of {@code alpha} at every pixel (a 24 bits image with an alpha channel).
     */
    static ImageData compose(ImageData data, AlphaFunction alpha) {
        int[] argb = SwtSnapshots.argb(data);
        ImageData composed = new ImageData(data.width, data.height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        byte[] alphas = new byte[data.width * data.height];
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                int i = y * data.width + x;
                composed.setPixel(x, y, argb[i] & 0xFFFFFF);
                alphas[i] = (byte) alpha.alpha(x, y);
            }
        }
        composed.alphaData = alphas;
        return composed;
    }

    private static List<Check> shellChecks(Shell main, State s) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("alpha shell : getAlpha()", ALPHA,
                () -> s.alphaShell.getAlpha())));
        checks.add(SwtChecks.expect("shaped shell : region bounds", "0,0 260x120",
                () -> SwtChecks.rect(s.shapedShell.getRegion().getBounds())));
        // the hole, a corner cut by the tag, the inside
        checks.add(SwtChecks.expect("shaped shell : region contains (222,60), (2,2), (120,60), (255,110)",
                "false false true false", () -> s.region.contains(222, 60) + " " + s.region.contains(2, 2) + " "
                        + s.region.contains(120, 60) + " " + s.region.contains(255, 110)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("shaped shell : getSize() (from the region)",
                "260x120", () -> SwtChecks.size(s.shapedShell.getSize()))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("minimum size shell : getMinimumSize()",
                "260x100", () -> SwtChecks.size(s.minimumShell.getMinimumSize()))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("minimum size shell : size after"
                + " setBounds(..., 120, 50)", "260x100", () -> SwtChecks.size(s.minimumShell.getSize()))));
        checks.add(SwtChecks.expect("isVisible() of the three shells", "true true true",
                () -> s.alphaShell.isVisible() + " " + s.shapedShell.isVisible() + " " + s.minimumShell.isVisible()));
        checks.add(SwtChecks.expect("shells owned by the main window (getShells())", 3,
                () -> main.getShells().length));
        return checks;
    }

    // ----------------------------------------------------------------------------------------- programs and images

    private static int tileHeight(Display display) {
        return ICON + 10 + textHeight(display, SwtKit.font(SWT.NORMAL, 8));
    }

    private static int textHeight(Display display, Font font) {
        Image image = new Image(display, 1, 1);
        GC gc = new GC(image);
        try {
            gc.setFont(font);
            return gc.getFontMetrics().getHeight();
        } finally {
            gc.dispose();
            image.dispose();
        }
    }

    /**
     * Looks the programs up (their names in the checks only) and returns the painter of their icons.
     */
    private static Consumer<GC> programTiles(Display display, List<Check> checks) {
        checks.add(SwtChecks.info("Program.getExtensions()", () -> Program.getExtensions().length + " extensions"));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("getExtensions() all start with a dot", true,
                () -> Arrays.stream(Program.getExtensions()).allMatch(e -> e.startsWith(".")))));
        List<ImageData> icons = new ArrayList<>();
        for (String extension : EXTENSIONS) {
            Program program = Program.findProgram(extension);
            ImageData icon = program == null ? null : program.getImageData(200);
            icons.add(icon);
            // the name of the program, and the sizes of getImageData() and getImageData(200)
            checks.add(SwtChecks.info("findProgram(\"" + extension + "\") : name ; icons", () -> program == null
                    ? "none"
                    : (program.getName().isEmpty() ? "no name" : program.getName()) + " ; "
                            + size(program.getImageData()) + ", " + size(icon)));
        }
        checks.add(SwtChecks.expect("findProgram(\"txt\") equals findProgram(\".txt\")", true,
                () -> Objects.equals(Program.findProgram("txt"), Program.findProgram(".txt"))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("findProgram(\".quarkus-showcase-none\")",
                "none", () -> String.valueOf(Objects.requireNonNullElse(
                        Program.findProgram(".quarkus-showcase-none"), "none")))));
        checks.add(SwtChecks.expect("findProgram(\"\")", "none",
                () -> String.valueOf(Objects.requireNonNullElse(Program.findProgram(""), "none"))));
        checks.add(SwtChecks.expect("findProgram(null)", "IllegalArgumentException",
                () -> SwtDataTransferPage.thrown(() -> Program.findProgram(null))));
        checks.add(Check.info("Program.launch / Program.execute", "never called : the page starts no program"));
        return gc -> {
            for (int i = 0; i < EXTENSIONS.size(); i++) {
                paintTile(gc, display, i, icons.get(i), EXTENSIONS.get(i));
            }
        };
    }

    private static Consumer<GC> systemImageTiles(Display display) {
        return gc -> {
            for (int i = 0; i < SYSTEM_IMAGES.size(); i++) {
                // shared images of the display : never disposed
                Image image = display.getSystemImage(SYSTEM_IMAGES.get(i).id());
                int x = i * TILE_WIDTH;
                if (image != null) {
                    Rectangle bounds = image.getBounds();
                    gc.drawImage(image, 0, 0, bounds.width, bounds.height, x + (TILE_WIDTH - ICON) / 2, 0, ICON,
                            ICON);
                }
                caption(gc, SYSTEM_IMAGES.get(i).name(), x);
            }
        };
    }

    private static void paintTile(GC gc, Display display, int index, ImageData icon, String caption) {
        int x = index * TILE_WIDTH;
        int iconX = x + (TILE_WIDTH - ICON) / 2;
        if (icon == null) {
            gc.setForeground(SwtKit.color(SWATCH_BORDER));
            gc.setLineStyle(SWT.LINE_DOT);
            gc.drawRectangle(iconX, 0, ICON - 1, ICON - 1);
            gc.setLineStyle(SWT.LINE_SOLID);
        } else {
            Image image = new Image(display, icon);
            try {
                gc.drawImage(image, 0, 0, icon.width, icon.height, iconX, 0, ICON, ICON);
            } finally {
                image.dispose();
            }
        }
        caption(gc, icon == null ? caption + " : none" : caption, x);
    }

    private static void caption(GC gc, String text, int x) {
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        gc.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
        Point extent = gc.stringExtent(text);
        gc.drawString(text, x + (TILE_WIDTH - extent.x) / 2, ICON + 6, true);
    }

    private static String size(ImageData data) {
        return data == null ? "none" : data.width + "x" + data.height;
    }

    // ------------------------------------------------------------------------------------------- colors, cursors

    private static void paintColors(GC gc, Display display, int rowHeight) {
        int rows = (COLORS.size() + COLOR_COLUMNS - 1) / COLOR_COLUMNS;
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        // by column : the basic colors first, then the colors of the theme ; each column as wide as its longest name
        int[] columnX = new int[COLOR_COLUMNS];
        for (int column = 1; column < COLOR_COLUMNS; column++) {
            int widest = 0;
            for (int i = (column - 1) * rows; i < Math.min(COLORS.size(), column * rows); i++) {
                widest = Math.max(widest, gc.stringExtent(COLORS.get(i).name()).x);
            }
            columnX[column] = columnX[column - 1] + SWATCH_WIDTH + 8 + widest + 40;
        }
        for (int i = 0; i < COLORS.size(); i++) {
            Named named = COLORS.get(i);
            int x = columnX[i / rows];
            int y = (i % rows) * rowHeight;
            int swatchHeight = rowHeight - 6;
            Color color = display.getSystemColor(named.id());
            if (color.getAlpha() < 0xFF) {
                // a checkerboard under a transparent color
                for (int cx = 0; cx < SWATCH_WIDTH; cx += 6) {
                    for (int cy = 0; cy < swatchHeight; cy += 6) {
                        gc.setBackground(SwtKit.color(((cx + cy) / 6) % 2 == 0 ? 0xCFD8DC : 0xFFFFFF));
                        gc.fillRectangle(x + cx, y + 3 + cy, Math.min(6, SWATCH_WIDTH - cx),
                                Math.min(6, swatchHeight - cy));
                    }
                }
            }
            gc.setBackground(color);
            gc.setAlpha(color.getAlpha());
            gc.fillRectangle(x, y + 3, SWATCH_WIDTH, swatchHeight);
            gc.setAlpha(0xFF);
            gc.setForeground(SwtKit.color(SWATCH_BORDER));
            gc.drawRectangle(x, y + 3, SWATCH_WIDTH - 1, swatchHeight - 1);
            gc.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
            gc.drawString(named.name(), x + SWATCH_WIDTH + 8, y + 3, true);
        }
    }

    /**
     * The names of the system cursors, each label showing its cursor (shared by the display : never disposed).
     */
    private static void cursorGrid(Composite page, Display display) {
        Composite grid = new Composite(page, SWT.NONE);
        GridLayout layout = new GridLayout(CURSOR_COLUMNS, true);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 4;
        layout.verticalSpacing = 4;
        grid.setLayout(layout);
        for (Named cursor : CURSORS) {
            Label label = new Label(grid, SWT.CENTER);
            label.setText(cursor.name());
            label.setFont(SwtKit.font(SWT.NORMAL, 8));
            label.setForeground(SwtKit.color(SwtKit.TEXT_COLOR));
            label.setBackground(SwtKit.color(PANEL));
            label.setCursor(display.getSystemCursor(cursor.id()));
            GridData data = new GridData(SWT.FILL, SWT.CENTER, false, false);
            data.widthHint = (SwtKit.TEXT_WIDTH - (CURSOR_COLUMNS - 1) * 4) / CURSOR_COLUMNS;
            label.setLayoutData(data);
        }
    }

    private static List<Check> colorChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("the 16 basic colors", BASIC_COLORS, () -> String.join(" ",
                COLORS.subList(0, 16).stream().map(c -> SwtChecks.rgb(display.getSystemColor(c.id()).getRGB()))
                        .toList())));
        checks.add(SwtChecks.expect("COLOR_TRANSPARENT : alpha", 0,
                () -> display.getSystemColor(SWT.COLOR_TRANSPARENT).getAlpha()));
        // the colors of the theme : informational, the same in both runs
        for (String prefix : List.of("WIDGET_", "LIST_", "INFO_", "TITLE_")) {
            checks.add(SwtChecks.info("COLOR_" + prefix + "*", () -> String.join(" ", COLORS.stream()
                    .filter(c -> c.name().startsWith(prefix) && !c.name().equals("WIDGET_DISABLED_FOREGROUND"))
                    .map(c -> SwtChecks.rgb(display.getSystemColor(c.id()).getRGB())).toList())));
        }
        checks.add(SwtChecks.info("LINK_FOREGROUND, TEXT_DISABLED_BACKGROUND, WIDGET_DISABLED_FOREGROUND",
                () -> SwtChecks.rgb(display.getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB()) + " "
                        + SwtChecks.rgb(display.getSystemColor(SWT.COLOR_TEXT_DISABLED_BACKGROUND).getRGB()) + " "
                        + SwtChecks.rgb(display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND).getRGB())));
        return checks;
    }

    // ---------------------------------------------------------------------------------------------- display checks

    private static List<Check> displayChecks(Display display) {
        List<Check> checks = new ArrayList<>();
        // quarkus-desktop-swt : Display.setAppName and setAppVersion before the Display is created
        checks.add(SwtChecks.expect("getAppName() (application name)", "quarkus-desktop-showcase",
                Display::getAppName));
        checks.add(SwtChecks.expect("getAppVersion() (application version)", "1.0.0-SNAPSHOT",
                Display::getAppVersion));
        checks.add(SwtChecks.info("isSystemDarkTheme()", Display::isSystemDarkTheme));
        Monitor[] monitors = display.getMonitors();
        Monitor primary = display.getPrimaryMonitor();
        checks.add(Check.info("getMonitors()", monitors.length + " monitor(s)"));
        for (int i = 0; i < monitors.length; i++) {
            Monitor monitor = monitors[i];
            checks.add(SwtChecks.info("monitor " + i + (monitor.equals(primary) ? " (primary)" : ""),
                    () -> SwtChecks.rect(monitor.getBounds()) + ", client area " + SwtChecks.rect(
                            monitor.getClientArea()) + ", zoom " + monitor.getZoom() + " %"));
        }
        checks.add(SwtChecks.expect("getPrimaryMonitor() in getMonitors()", true,
                () -> Arrays.asList(monitors).contains(primary)));
        checks.add(SwtChecks.expect("getSystemMenu() (macOS only)",
                SwtMode.pick("present", "none", "none"),
                () -> display.getSystemMenu() == null ? "none" : "present"));
        return checks;
    }

    // ----------------------------------------------------------------------------------- tray, task bar, cursors

    private static List<Check> trayAndCursors(Display display, Shell main) {
        List<Check> checks = new ArrayList<>();
        Tray tray = display.getSystemTray();
        checks.add(Check.info("Display.getSystemTray()", tray == null ? "none" : "available"));
        if (tray != null) {
            checks.add(trayItem(display, tray, checks));
        }
        TaskBar taskBar = display.getSystemTaskBar();
        checks.add(Check.info("Display.getSystemTaskBar()", taskBar == null ? "none" : "available"));
        if (taskBar != null) {
            checks.add(SwtChecks.info("TaskBar.getItemCount() (the shells, the application)", taskBar::getItemCount));
            checks.add(SwtChecks.expect("TaskBar.getItem(main shell) twice", "the same item",
                    () -> taskBar.getItem(main) == taskBar.getItem(main) ? "the same item" : "two items"));
            checks.add(SwtChecks.info("TaskItem of the main shell : progress, state, overlay text", () -> {
                TaskItem item = taskBar.getItem(main);
                return item.getProgress() + ", " + item.getProgressState() + ", '" + item.getOverlayText() + "'";
            }));
        }

        checks.add(SwtChecks.expect("new Cursor(display, SWT.CURSOR_*) then dispose()",
                CURSORS.size() + " created, " + CURSORS.size() + " disposed", () -> {
                    int created = 0;
                    int disposed = 0;
                    for (Named named : CURSORS) {
                        Cursor cursor = new Cursor(display, named.id());
                        created++;
                        cursor.dispose();
                        disposed += cursor.isDisposed() ? 1 : 0;
                    }
                    return created + " created, " + disposed + " disposed";
                }));
        checks.add(SwtChecks.expect("new Cursor(display, ImageData, 4, 4) then dispose()", "created, disposed", () -> {
            Cursor cursor = new Cursor(display, cursorImage(), 4, 4);
            cursor.dispose();
            return "created, " + (cursor.isDisposed() ? "disposed" : "not disposed");
        }));
        checks.add(SwtChecks.expect("Display.getSystemCursor(id) : shared instances", CURSORS.size() + " shared",
                () -> CURSORS.stream().filter(c -> display.getSystemCursor(c.id()) == display.getSystemCursor(c.id())
                        && !display.getSystemCursor(c.id()).isDisposed()).count() + " shared"));
        checks.add(SwtChecks.info("Display.getSystemImage(SWT.ICON_*) sizes", () -> String.join(" ",
                SYSTEM_IMAGES.stream().map(i -> {
                    Image image = display.getSystemImage(i.id());
                    return image == null ? "none" : SwtChecks.size(new Point(image.getBounds().width,
                            image.getBounds().height));
                }).toList())));
        return checks;
    }

    /**
     * Adds a tray item, reads it back and removes it at once : no tray icon is left behind.
     */
    private static Check trayItem(Display display, Tray tray, List<Check> checks) {
        int before = tray.getItemCount();
        Image icon = new Image(display, trayImage());
        try {
            TrayItem item = new TrayItem(tray, SWT.NONE);
            int with;
            try {
                item.setToolTipText(TRAY_TOOL_TIP);
                item.setImage(icon);
                with = tray.getItemCount();
                checks.add(SwtChecks.expect("TrayItem : tool tip, image, visible",
                        TRAY_TOOL_TIP + ", 16x16, true", () -> item.getToolTipText() + ", "
                                + SwtChecks.size(new Point(item.getImage().getBounds().width,
                                        item.getImage().getBounds().height))
                                + ", " + item.getVisible()));
            } finally {
                item.dispose();
            }
            int after = tray.getItemCount();
            int added = with - before;
            return SwtChecks.expect("TrayItem added, then disposed", "1 added, none left behind",
                    () -> added + " added, " + (after == before ? "none" : after - before) + " left behind");
        } finally {
            icon.dispose();
        }
    }

    private static ImageData trayImage() {
        return SwtSnapshots.offscreen(16, 16, gc -> {
            gc.setBackground(SwtKit.color(0x1B3A6B));
            gc.fillRectangle(0, 0, 16, 16);
            gc.setBackground(SwtKit.color(0x4695EB));
            gc.fillRectangle(3, 4, 10, 7);
            gc.setBackground(SwtKit.color(0xFFFFFF));
            gc.fillRectangle(6, 12, 4, 1);
        });
    }

    private static ImageData cursorImage() {
        return SwtSnapshots.offscreen(32, 32, gc -> {
            gc.setBackground(SwtKit.color(0x263238));
            gc.fillPolygon(new int[] { 4, 4, 24, 14, 14, 16, 12, 26 });
        });
    }
}
