package io.quarkiverse.desktop.showcase.swt.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Quarkus;

/**
 * The main window of the SWT variant : a {@link Shell} with a {@link Tree} of the pages (by category) on the left, the
 * title and the selected page in a {@link ScrolledComposite} on the right, and a status bar. The SWT counterpart of
 * {@code core/MainWindow} and its implementations.
 * <p>
 * A bean owning its shell (SWT widgets are not beans) : {@link #open} creates it. The page frame is white, at least
 * {@link #PAGE_WIDTH} x {@link #PAGE_HEIGHT} with a padding of {@link #PAGE_PADDING} around the page content, and
 * always at its preferred size (its size never depends on the size of the window). In snapshot mode, the shell is at
 * ({@link #SNAPSHOT_X}, {@link #SNAPSHOT_Y}), {@link #SNAPSHOT_WIDTH} x {@link #SNAPSHOT_HEIGHT}, and shown without
 * taking the focus.
 * <p>
 * Every method is invoked on the user interface thread.
 */
@Singleton
public class SwtMainWindow {

    private static final Logger LOG = Logger.getLogger(SwtMainWindow.class);

    /** Width of the page frame (at least). */
    public static final int PAGE_WIDTH = 1060;
    /** Height of the page frame (at least). */
    public static final int PAGE_HEIGHT = 760;
    /** Padding between the page frame and the page content. */
    public static final int PAGE_PADDING = 16;
    /** Size and position of the window in snapshot mode. */
    public static final int SNAPSHOT_X = 40;
    public static final int SNAPSHOT_Y = 40;
    public static final int SNAPSHOT_WIDTH = 1400;
    public static final int SNAPSHOT_HEIGHT = 900;
    /** Width of the navigation tree. */
    static final int NAV_WIDTH = 260;

    private static final String TITLE = "Quarkus Desktop Showcase";

    private List<SwtPage> pages = List.of();
    private final Map<SwtPage, TreeItem> items = new LinkedHashMap<>();
    private Shell shell;
    private Composite root;
    private Tree nav;
    private Label pageTitle;
    private ScrolledComposite scroll;
    private Composite pageFrame;
    private SwtPage currentPage;
    private Control currentContent;
    private Throwable currentError;

    /**
     * Creates and shows the window.
     */
    public void open(Display display, List<SwtPage> pages) {
        this.pages = List.copyOf(pages);
        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText(TITLE + " (" + SwtMode.runtime() + ")");
        Image[] icons = { icon(display, 16), icon(display, 32), icon(display, 48) };
        shell.setImages(icons);
        shell.addListener(SWT.Dispose, event -> {
            clear();
            for (Image icon : icons) {
                icon.dispose();
            }
        });
        shell.setMenuBar(menuBar());
        shell.setLayout(new FillLayout());

        // Control.print of a Shell is not supported : this composite is the content rendered as _main-window.png
        root = new Composite(shell, SWT.NONE);
        GridLayout rootLayout = new GridLayout(2, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        rootLayout.horizontalSpacing = 0;
        rootLayout.verticalSpacing = 0;
        root.setLayout(rootLayout);

        nav = new Tree(root, SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData navData = new GridData(SWT.FILL, SWT.FILL, false, true);
        navData.widthHint = NAV_WIDTH;
        nav.setLayoutData(navData);
        Map<String, TreeItem> categories = new LinkedHashMap<>();
        for (SwtPage page : this.pages) {
            TreeItem category = categories.computeIfAbsent(page.category(), name -> {
                TreeItem item = new TreeItem(nav, SWT.NONE);
                item.setText(name);
                return item;
            });
            TreeItem item = new TreeItem(category, SWT.NONE);
            item.setText(page.title());
            item.setData(page);
            items.put(page, item);
        }
        categories.values().forEach(item -> item.setExpanded(true));
        nav.addListener(SWT.Selection, event -> {
            if (event.item instanceof TreeItem item) {
                SwtPage page = item.getData() instanceof SwtPage p ? p
                        : item.getItemCount() > 0 && item.getItem(0).getData() instanceof SwtPage first ? first : null;
                if (page != null) {
                    select(page);
                }
            }
        });

        Composite right = new Composite(root, SWT.NONE);
        right.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout rightLayout = new GridLayout(1, false);
        rightLayout.marginWidth = 0;
        rightLayout.marginHeight = 0;
        rightLayout.verticalSpacing = 0;
        right.setLayout(rightLayout);

        pageTitle = new Label(right, SWT.NONE);
        pageTitle.setFont(SwtKit.font(SWT.BOLD, 11));
        GridData titleData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        titleData.horizontalIndent = 12;
        titleData.verticalIndent = 6;
        pageTitle.setLayoutData(titleData);
        pageTitle.setText(TITLE);
        Label titleGap = new Label(right, SWT.NONE);
        GridData gapData = new GridData(SWT.FILL, SWT.TOP, true, false);
        gapData.heightHint = 6;
        titleGap.setLayoutData(gapData);

        scroll = new ScrolledComposite(right, SWT.H_SCROLL | SWT.V_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(false);
        scroll.setExpandVertical(false);
        pageFrame = new Composite(scroll, SWT.NONE);
        pageFrame.setBackground(SwtKit.color(SwtKit.WHITE));
        pageFrame.setBackgroundMode(SWT.INHERIT_DEFAULT);
        pageFrame.setLayout(new PageFrameLayout());
        scroll.setContent(pageFrame);
        pageFrame.setSize(pageFrame.computeSize(SWT.DEFAULT, SWT.DEFAULT));

        Label status = new Label(root, SWT.NONE);
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        statusData.horizontalIndent = 8;
        statusData.verticalIndent = 3;
        status.setLayoutData(statusData);
        status.setText(statusText());

        if (SwtMode.snapshot()) {
            // the snapshot window never steals the focus of the user's application : shown without being activated
            shell.setBounds(SNAPSHOT_X, SNAPSHOT_Y, SNAPSHOT_WIDTH, SNAPSHOT_HEIGHT);
            shell.layout(true, true);
            shell.setVisible(true);
        } else {
            shell.setSize(SNAPSHOT_WIDTH, SNAPSHOT_HEIGHT);
            shell.layout(true, true);
            shell.open();
        }
    }

    public Shell shell() {
        return shell;
    }

    public List<SwtPage> pages() {
        return pages;
    }

    /**
     * The whole content of the window, without the native decorations and the menu bar : rendered as
     * {@code _main-window.png}.
     */
    public Composite rootContent() {
        return root;
    }

    /**
     * The composite holding the page content (white background, {@link #PAGE_PADDING} around the content, at least
     * {@link #PAGE_WIDTH} x {@link #PAGE_HEIGHT}) : the parent given to {@link SwtPage#build(Composite)}, rendered as
     * {@code <page-id>.png}.
     */
    public Composite pageFrame() {
        return pageFrame;
    }

    public SwtPage currentPage() {
        return currentPage;
    }

    public Control currentContent() {
        return currentContent;
    }

    /**
     * The exception thrown by {@link SwtPage#build(Composite)} of the current page, if any.
     */
    public Throwable currentError() {
        return currentError;
    }

    /**
     * Selects {@code page} in the navigation and shows it (builds it) if it is not the current page.
     */
    public void select(SwtPage page) {
        TreeItem item = items.get(page);
        if (item != null && !item.isDisposed()) {
            nav.setSelection(item);
        }
        if (currentPage != page) {
            show(page);
        }
    }

    /**
     * Shows {@code page} : disposes the current page, then builds the new one (the error content if the build fails).
     */
    private void show(SwtPage page) {
        clear();
        currentPage = page;
        pageTitle.setText(page.category() + " › " + page.title());
        Control content;
        try {
            content = page.build(pageFrame);
            Control[] children = pageFrame.getChildren();
            if (content == null || content.isDisposed() || content.getParent() != pageFrame || children.length != 1) {
                throw new IllegalStateException("build(parent) must return the single control it created in the page"
                        + " frame (" + children.length + " controls in the page frame)");
            }
        } catch (Throwable t) {
            LOG.errorf(t, "Page %s failed to build", page.id());
            currentError = t;
            for (Control child : pageFrame.getChildren()) {
                child.dispose();
            }
            content = SwtKit.error(pageFrame, t);
        }
        currentContent = content;
        contentChanged();
    }

    /**
     * Disposes the current page, if any : {@link SwtPage#dispose(Control)}, then its content.
     */
    public void clear() {
        if (currentPage != null && currentContent != null && currentError == null) {
            try {
                currentPage.dispose(currentContent);
            } catch (Throwable t) {
                LOG.errorf(t, "Page %s failed to dispose", currentPage.id());
            }
        }
        currentPage = null;
        currentContent = null;
        currentError = null;
        if (pageFrame != null && !pageFrame.isDisposed()) {
            for (Control child : pageFrame.getChildren()) {
                child.dispose();
            }
            pageTitle.setText(TITLE);
            contentChanged();
        }
    }

    /**
     * Renders the page frame ({@code <page-id>.png}) once {@link SwtSnapshots#STABLE_RENDERS} renders in a row give
     * the same pixels ({@link SwtSnapshots#renderStable}) : the caller settled it.
     * <p>
     * On Windows, the pixels are copied from the window ({@link SwtSnapshots#copy}), which holds the parts on the
     * screen only : a page frame that the viewport does not show whole on the screen (its bottom, or a page larger than
     * the window) is moved for the capture into a shell (no trim, not activated) at the position of the main window, as
     * large as the page frame or the client area of the monitor allow, and settled there ({@code settleMillis}). A page
     * frame larger than that shell is captured by tiles : moved in the shell to show each tile in turn, every tile
     * rendered until its pixels are stable, then assembled. The page frame is moved back to the main window after.
     */
    public CompletionStage<ImageData> renderPageFrame(long settleMillis) {
        Rectangle viewport = scroll.getClientArea();
        Point size = pageFrame.getSize();
        if (!SwtMode.isWindows() || (size.x <= viewport.width && size.y <= viewport.height
                && SwtSnapshots.onScreen(pageFrame, new Rectangle(0, 0, size.x, size.y)))) {
            return SwtSnapshots.renderStable(pageFrame, SwtSnapshots.STABLE_RENDERS,
                    SwtSnapshots.STABLE_TIMEOUT_MILLIS);
        }
        Rectangle screen = shell.getMonitor().getClientArea();
        Point location = shell.getLocation();
        Point origin = screen.contains(location) ? location : new Point(screen.x, screen.y);
        int tileWidth = tile(size.x, screen.x + screen.width - origin.x);
        int tileHeight = tile(size.y, screen.y + screen.height - origin.y);
        Shell capture = new Shell(shell, SWT.NO_TRIM | SWT.TOOL);
        capture.setBackground(SwtKit.color(SwtKit.WHITE));
        capture.setBounds(origin.x, origin.y, tileWidth, tileHeight);
        pageFrame.setParent(capture);
        pageFrame.setLocation(0, 0);
        capture.setVisible(true);
        // by the position of their top left corner in pixels : exact, the tiles start at multiples of 100 points
        Map<Point, ImageData> tiles = new LinkedHashMap<>();
        int zoom = SwtSnapshots.deviceZoom();
        CompletionStage<Void> chain = SwtSnapshots.settle(settleMillis);
        for (int y = 0; y < size.y; y += tileHeight) {
            for (int x = 0; x < size.x; x += tileWidth) {
                Rectangle area = new Rectangle(x, y, Math.min(tileWidth, size.x - x), Math.min(tileHeight, size.y - y));
                chain = chain.thenCompose(v -> {
                    pageFrame.setLocation(-area.x, -area.y);
                    return SwtSnapshots.renderStable(() -> SwtSnapshots.copy(pageFrame, area),
                            "the page frame at " + area, SwtSnapshots.STABLE_RENDERS,
                            SwtSnapshots.STABLE_TIMEOUT_MILLIS);
                }).thenAccept(image -> tiles.put(new Point(area.x * zoom / 100, area.y * zoom / 100), image));
            }
        }
        return chain.thenApply(v -> SwtSnapshots.assemble(tiles))
                .whenComplete((image, error) -> {
                    if (!pageFrame.isDisposed()) {
                        // still the content of the scrolled composite (setContent would resize it to nothing first)
                        pageFrame.setParent(scroll);
                        pageFrame.setLocation(0, 0);
                        scroll.layout(true);
                        scroll.setOrigin(0, 0);
                    }
                    capture.dispose();
                });
    }

    /**
     * The length of the tiles of a capture : the whole {@code length} when {@code available}, else what is available
     * rounded down to a multiple of 100 points (a tile then starts at a whole pixel at every zoom).
     */
    private static int tile(int length, int available) {
        if (length <= available) {
            return length;
        }
        return available >= 100 ? available / 100 * 100 : Math.max(1, available);
    }

    /**
     * Disposes the window (and the current page).
     */
    public void dispose() {
        if (shell != null && !shell.isDisposed()) {
            shell.dispose();
        }
    }

    /**
     * Lays out the page frame (at its preferred size) after its content changed, and scrolls to its top left corner.
     */
    private void contentChanged() {
        pageFrame.layout(true, true);
        scroll.setOrigin(0, 0);
    }

    /**
     * Shows the previous ({@code -1}) or next ({@code 1}) page.
     */
    private void step(int delta) {
        int index = currentPage == null ? 0 : pages.indexOf(currentPage) + delta;
        if (index >= 0 && index < pages.size()) {
            select(pages.get(index));
        }
    }

    private String statusText() {
        String text = "SWT UI · " + pages.size() + " pages";
        // the runtime differs between the compared runs : keep it out of snapshots
        return SwtMode.snapshot() ? text : SwtMode.runtime() + " · " + text;
    }

    private Menu menuBar() {
        Menu bar = new Menu(shell, SWT.BAR);
        MenuItem showcase = new MenuItem(bar, SWT.CASCADE);
        showcase.setText("&Showcase");
        Menu menu = new Menu(shell, SWT.DROP_DOWN);
        showcase.setMenu(menu);
        item(menu, "&Previous page\tCtrl+[", SWT.MOD1 | '[', () -> step(-1));
        item(menu, "&Next page\tCtrl+]", SWT.MOD1 | ']', () -> step(1));
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, "&Quit\tCtrl+Q", SWT.MOD1 | 'Q', () -> {
            shell.dispose();
            Quarkus.asyncExit();
        });
        return bar;
    }

    private static void item(Menu menu, String text, int accelerator, Runnable action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.setAccelerator(accelerator);
        item.addListener(SWT.Selection, event -> action.run());
    }

    /**
     * The window icon, painted with a {@code GC} (disposed with the shell).
     */
    private static Image icon(Display display, int size) {
        Image image = new Image(display, size, size);
        GC gc = new GC(image);
        try {
            gc.setAntialias(SWT.ON);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, size, size);
            gc.setForeground(SwtKit.color(0x4695EB));
            gc.setBackground(SwtKit.color(0x1B3A6B));
            gc.fillGradientRectangle(0, 0, size, size, true);
            gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
            gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
            gc.setLineWidth(Math.max(1, size / 12));
            gc.drawRectangle(size * 7 / 32, size * 9 / 32, size * 18 / 32, size * 13 / 32);
            gc.fillRectangle(size * 12 / 32, size * 24 / 32, size / 4, Math.max(1, size / 16));
        } finally {
            gc.dispose();
        }
        return image;
    }

    /**
     * Layout of the page frame : the single content control at {@link #PAGE_PADDING}, at least {@link #PAGE_WIDTH} x
     * {@link #PAGE_HEIGHT} with the padding, larger if the content prefers. The page frame is kept at its preferred
     * size (the content of a {@link ScrolledComposite} that does not expand it) : it grows when its content does
     * (checks set once the page is ready), whatever the size of the window.
     */
    static final class PageFrameLayout extends Layout {

        @Override
        protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
            int width = PAGE_WIDTH;
            int height = PAGE_HEIGHT;
            for (Control child : composite.getChildren()) {
                Point size = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                width = Math.max(width, size.x + 2 * PAGE_PADDING);
                height = Math.max(height, size.y + 2 * PAGE_PADDING);
            }
            return new Point(width, height);
        }

        @Override
        protected void layout(Composite composite, boolean flushCache) {
            Point preferred = computeSize(composite, SWT.DEFAULT, SWT.DEFAULT, flushCache);
            if (!composite.getSize().equals(preferred)) {
                // lays it out again, at its new size
                composite.setSize(preferred);
            }
            Rectangle area = composite.getClientArea();
            for (Control child : composite.getChildren()) {
                child.setBounds(area.x + PAGE_PADDING, area.y + PAGE_PADDING, area.width - 2 * PAGE_PADDING,
                        area.height - 2 * PAGE_PADDING);
            }
        }
    }
}
