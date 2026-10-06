package io.quarkiverse.desktop.swt.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Resource;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Checks of SWT features : the widgets of the platform rendered from a shell, graphics drawn into images, images and
 * their formats, text, layouts, and the native services of SWT.
 * <p>
 * Prints one {@code RESULT <check> OK|FAILED|SKIPPED} line per check and a {@code SUMMARY} line. It never prints on a
 * printer, never opens a native dialog, only gives the focus to canvases of its own shells, does not write to the
 * clipboard, and disposes its shells. It does not depend on Quarkus : {@link #main(String[])} runs it in a plain JVM (for
 * instance with the native
 * image tracing agent), with a {@code Display} of its own.
 * <p>
 * The checks write what they render to {@code <name>-<mode>.png}, and what they measure to {@code <name>-<mode>.txt} :
 * the integration tests compare the files of the native executable with those of the JVM mode tests. They only hold
 * values that are the same in every run on a machine (no handles, hash codes, timings or temporary paths).
 * <p>
 * The checks are grouped by area : {@link SwtWidgetChecks} (widgets, containers, custom widgets, tables and trees,
 * layouts), {@link SwtGraphicsChecks} (graphics, text layout, fonts, images, colors and cursors) and
 * {@link SwtServiceChecks} (data transfer, desktop services, printing, accessibility, threads).
 */
public final class SwtChecks {

    /**
     * The name that the extension gives to SWT ({@code quarkus.desktop.swt.application-name}).
     */
    static final String APPLICATION_NAME = "Quarkus Desktop SWT IT";

    static final RGB WHITE = new RGB(255, 255, 255);

    private static final long TIMEOUT_MILLIS = 20_000;

    /**
     * How long a shell just shown is left to paint itself before it is rendered.
     */
    private static final long SETTLE_MILLIS = 400;

    /**
     * The interval between the renderings of a shell that must be the same, and their number.
     */
    private static final long STILL_MILLIS = 150;
    private static final int STILL_RENDERINGS = 3;

    private final Path directory;
    private final String mode;
    private final boolean quarkus;
    private final PrintStream out;
    private final List<String> failures = new ArrayList<>();
    private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
    private int ok;
    private Display display;

    /**
     * @param directory where the rendered images are written
     * @param mode {@code jvm} or {@code native}
     */
    public SwtChecks(Path directory, String mode) {
        this(directory, mode, true);
    }

    /**
     * @param quarkus whether the {@code Display} is the one of the extension (configured by the application)
     */
    private SwtChecks(Path directory, String mode, boolean quarkus) {
        this.directory = directory;
        this.mode = mode;
        this.quarkus = quarkus;
        this.out = System.out;
    }

    /**
     * Runs the checks in a plain JVM : {@code [directory]}.
     */
    public static void main(String[] args) {
        Path directory = Path.of(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"));
        Display display = new Display();
        int status;
        try {
            status = new SwtChecks(directory, "jvm", false).run(display);
        } finally {
            display.dispose();
        }
        System.exit(status);
    }

    /**
     * Runs the checks on the user interface thread of the display.
     *
     * @return the exit status : 0 when every check passed, 1 otherwise
     */
    public int run(Display display) {
        this.display = display;
        PrintStream previousErr = System.err;
        ErrorWatcher errors = new ErrorWatcher(previousErr);
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        var previousExceptionHandler = display.getRuntimeExceptionHandler();
        var previousErrorHandler = display.getErrorHandler();
        display.setRuntimeExceptionHandler(uncaught::add);
        display.setErrorHandler(uncaught::add);
        try {
            Files.createDirectories(directory);
            check("environment", this::environment);
            check("static-initializer", () -> {
                require(SwtPalette.ACCENT.equals(new RGB(0, 150, 201)), "SwtPalette.ACCENT " + SwtPalette.ACCENT);
                return "accent=" + SwtPalette.ACCENT + " minimum=" + SwtPalette.MINIMUM_SIZE;
            });
            check("gallery", this::gallery);
            check("graphics", this::graphics);
            SwtWidgetChecks widgets = new SwtWidgetChecks(this);
            check("widgets", widgets::widgets);
            check("containers", widgets::containers);
            check("custom-widgets", widgets::customWidgets);
            check("table-tree", widgets::tableAndTree);
            check("layouts", widgets::layouts);
            SwtGraphicsChecks graphics = new SwtGraphicsChecks(this);
            check("graphics-advanced", graphics::advanced);
            check("text-layout", graphics::textLayout);
            check("fonts", graphics::fonts);
            check("images", graphics::images);
            check("colors-cursors", graphics::colorsAndCursors);
            SwtServiceChecks services = new SwtServiceChecks(this);
            check("data-transfer", services::dataTransfer);
            check("desktop-services", services::desktopServices);
            check("printing", services::printing);
            check("accessibility", services::accessibility);
            check("async", services::async);
            // The Edge (Windows) and WebKitGTK (Linux) runtimes may be missing : a Browser cannot be created everywhere
            skip("browser", "the Browser widget is not checked");
            pump(() -> true);
            check("errors", () -> {
                require(uncaught.isEmpty(), "uncaught exceptions " + uncaught);
                require(errors.lines().isEmpty(), "standard error " + errors.lines());
                return "none";
            });
        } catch (Throwable e) {
            failures.add("setup");
            out.println("RESULT setup FAILED " + e);
            e.printStackTrace(out);
        } finally {
            System.setErr(previousErr);
            if (!display.isDisposed()) {
                display.setRuntimeExceptionHandler(previousExceptionHandler);
                display.setErrorHandler(previousErrorHandler);
            }
        }
        out.println("SUMMARY ok=" + ok + " failed=" + failures.size() + (failures.isEmpty() ? "" : " " + failures));
        return failures.isEmpty() ? 0 : 1;
    }

    // ------------------------------------------------------------------------------------------------------- checks

    private Object environment() {
        Monitor primary = display.getPrimaryMonitor();
        Point dpi = display.getDPI();
        return "platform=" + SWT.getPlatform() + " version=" + SWT.getVersion() + " dpi=" + dpi.x + "x" + dpi.y
                + " zoom=" + primary.getZoom() + " monitors=" + display.getMonitors().length + " font="
                + display.getSystemFont().getFontData()[0].getName() + " dark=" + Display.isSystemDarkTheme()
                + " highContrast=" + display.getHighContrast() + " depth=" + display.getDepth();
    }

    /**
     * Widgets of the platform in a shell, rendered once still ({@link #show(Shell, Control, Control)}).
     */
    private Object gallery() throws Exception {
        Shell shell = new Shell(display, SWT.SHELL_TRIM);
        try {
            shell.setText("Gallery");
            shell.setLayout(new FillLayout());
            // the content is rendered with its children (Control.print does not support a shell on every platform)
            Composite content = new Composite(shell, SWT.NONE);
            content.setLayout(new GridLayout(3, false));
            new Label(content, SWT.NONE).setText("Label");
            Button push = new Button(content, SWT.PUSH);
            push.setText("Push");
            Button check = new Button(content, SWT.CHECK);
            check.setText("Check");
            check.setSelection(true);
            Text text = new Text(content, SWT.BORDER);
            text.setText("Text");
            Button radio = new Button(content, SWT.RADIO);
            radio.setText("Radio");
            Button toggle = new Button(content, SWT.TOGGLE);
            toggle.setText("Toggle");
            shell.setBounds(40, 40, 360, 160);
            ImageData image = show(shell, null, content);
            writeImage("gallery", image);
            return "size=" + image.width + "x" + image.height;
        } finally {
            shell.dispose();
        }
    }

    /**
     * Graphics drawn into an image : shapes, antialiasing, alpha, text.
     */
    private Object graphics() throws Exception {
        Image image = new Image(display, 200, 120);
        GC gc = new GC(image);
        try {
            gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, 200, 120);
            gc.setAntialias(SWT.ON);
            Color accent = new Color(SwtPalette.ACCENT);
            gc.setBackground(accent);
            gc.fillOval(10, 10, 80, 60);
            gc.setAlpha(128);
            gc.setBackground(display.getSystemColor(SWT.COLOR_RED));
            gc.fillRoundRectangle(50, 40, 120, 60, 20, 20);
            gc.setAlpha(255);
            gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
            gc.drawLine(0, 110, 200, 90);
            gc.setTextAntialias(SWT.OFF);
            gc.drawText("SWT", 100, 10, true);
        } finally {
            gc.dispose();
        }
        try {
            ImageData data = image.getImageData();
            writeImage("graphics", data);
            return "size=" + data.width + "x" + data.height + " depth=" + data.depth;
        } finally {
            image.dispose();
        }
    }

    // ---------------------------------------------------------------------------------------------- shared support

    Display display() {
        return display;
    }

    /**
     * Whether the checks run in the application of the extension (not in a plain JVM).
     */
    boolean quarkus() {
        return quarkus;
    }

    /**
     * A shell of the checks, with a fill layout : its content is a single composite, rendered once the shell is shown.
     */
    Shell shell(String title) {
        Shell shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText(title);
        shell.setLayout(new FillLayout());
        return shell;
    }

    /**
     * Sizes the shell to its content, and moves it near the corner of the primary monitor, away from the mouse pointer
     * (a control under the pointer would be painted hovered).
     */
    void place(Shell shell) {
        shell.pack();
        locate(shell);
    }

    private void locate(Shell shell) {
        Point size = shell.getSize();
        Rectangle area = display.getPrimaryMonitor().getClientArea();
        Point cursor = display.getCursorLocation();
        int x = area.x + 40;
        int y = area.y + 40;
        if (new Rectangle(x - 8, y - 8, size.x + 16, size.y + 16).contains(cursor)) {
            // right of the pointer when the shell fits there, else below it
            if (cursor.x + 40 + size.x <= area.x + area.width) {
                x = cursor.x + 40;
            } else {
                y = cursor.y + 40;
            }
        }
        shell.setLocation(x, y);
    }

    /**
     * Shows the shell, gives the focus to a control that does not paint it (no caret, no focus ring), lets the shell
     * paint itself, and renders the content once it is still : when {@link #STILL_RENDERINGS} renderings in a row are
     * the same (the platform animates some controls : the transitions of the states of the controls of Windows and
     * GTK, the glow of the progress bars of Windows...), and the mouse pointer is not over the shell (it is moved away
     * from the pointer).
     */
    ImageData show(Shell shell, Control focus, Control content) {
        shell.setVisible(true);
        if (focus != null) {
            focus.setFocus();
        }
        settle();
        // Control.print, also on Windows where the pixels of the renderings are copied from the window
        render(content);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        ImageData previous = null;
        int still = 0;
        while (true) {
            pumpFor(STILL_MILLIS);
            ImageData current = capture(content);
            boolean hovered = shell.getBounds().contains(display.getCursorLocation());
            still = previous != null && same(previous, current) && !hovered ? still + 1 : 1;
            if (still == STILL_RENDERINGS || System.nanoTime() > deadline) {
                return current;
            }
            if (hovered) {
                locate(shell);
            }
            previous = current;
        }
    }

    /**
     * The pixels of a control shown on the screen, and of its children : rendered with {@code Control.print}, except on
     * Windows, where they are copied from the window ({@code GC.copyArea}). On Windows, {@code Control.print} takes
     * them from the surface composed by the window manager ({@code PrintWindow} with {@code PW_RENDERFULLCONTENT}),
     * which misses child controls now and then (a few renderings in a hundred, at random, even when nothing changes in
     * the window), while the window holds them all.
     */
    private ImageData capture(Control control) {
        if (!"win32".equals(SWT.getPlatform())) {
            return render(control);
        }
        Point size = control.getSize();
        Image image = new Image(display, size.x, size.y);
        GC gc = new GC(control);
        try {
            gc.copyArea(image, 0, 0);
        } finally {
            gc.dispose();
        }
        try {
            return image.getImageData();
        } finally {
            image.dispose();
        }
    }

    private static boolean same(ImageData first, ImageData second) {
        return first.width == second.width && first.height == second.height && first.depth == second.depth
                && Arrays.equals(first.data, second.data) && Arrays.equals(first.alphaData, second.alphaData);
    }

    /**
     * Draws into an image of the size, white at first, and returns its pixels.
     */
    ImageData draw(int width, int height, Consumer<GC> painter) {
        Image image = new Image(display, width, height);
        try {
            GC gc = new GC(image);
            try {
                gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, width, height);
                painter.accept(gc);
            } finally {
                gc.dispose();
            }
            return image.getImageData();
        } finally {
            image.dispose();
        }
    }

    /**
     * A 16 x 16 icon with transparency : a disc, a square or a triangle of the color with a darker outline, computed
     * pixel by pixel (the same on every platform).
     */
    static ImageData icon(RGB color, int shape) {
        int size = 16;
        ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        RGB outline = new RGB(color.red / 2, color.green / 2, color.blue / 2);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double inside;
                switch (shape % 3) {
                    case 0 -> inside = 7.5 - Math.sqrt((x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5));
                    case 1 -> inside = Math.min(Math.min(x - 1, 14 - x), Math.min(y - 1, 14 - y)) + 0.5;
                    default -> inside = Math.min(14.5 - y, (y - 1) / 2.0 - Math.abs(x - 7.5) + 0.5);
                }
                RGB rgb = inside < 0 ? WHITE : inside < 1.5 ? outline : color;
                data.setPixel(x, y, data.palette.getPixel(rgb));
                data.setAlpha(x, y, inside < 0 ? 0 : 255);
            }
        }
        return data;
    }

    void check(String name, Callable<Object> check) {
        try {
            Object value = check.call();
            ok++;
            out.println("RESULT " + name + " OK " + value);
        } catch (Throwable e) {
            failures.add(name);
            out.println("RESULT " + name + " FAILED " + e);
            e.printStackTrace(out);
        }
    }

    private void skip(String name, String reason) {
        out.println("RESULT " + name + " SKIPPED " + reason);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Runs the event loop until it is idle and the condition holds, at most {@link #TIMEOUT_MILLIS}.
     */
    void pump(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (!display.readAndDispatch() && condition.getAsBoolean()) {
                return;
            }
        }
        throw new IllegalStateException("timed out");
    }

    /**
     * Runs the event loop, sleeping when it is idle, until the condition holds, at most {@link #TIMEOUT_MILLIS} : for
     * conditions that other threads fulfill (they do not always wake the event loop up, a timer does).
     */
    void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> display.timerExec(10, tick[0]);
        display.timerExec(10, tick[0]);
        try {
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out");
                }
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        } finally {
            display.timerExec(-1, tick[0]);
        }
    }

    /**
     * Runs the event loop for {@link #SETTLE_MILLIS}, then until it is idle : a shell just shown is painted, and
     * composed by the window manager (Windows prints the windows from their composed surface).
     */
    void settle() {
        pumpFor(SETTLE_MILLIS);
    }

    /**
     * Runs the event loop for the duration, then until it is idle.
     */
    private void pumpFor(long millis) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean[] woken = new boolean[1];
        // Windows may run a timer before its delay, as measured by System.nanoTime (seen on Windows 11 arm64) : the
        // timer is armed again until the duration elapsed, the event loop would otherwise sleep without any timer to
        // wake it up
        Runnable[] timer = new Runnable[1];
        timer[0] = () -> {
            long left = end - System.nanoTime();
            if (left > 0) {
                display.timerExec((int) TimeUnit.NANOSECONDS.toMillis(left) + 1, timer[0]);
            } else {
                woken[0] = true;
            }
        };
        display.timerExec((int) millis, timer[0]);
        while (!woken[0]) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        pump(() -> true);
    }

    /**
     * Renders a control and its children with {@code Control.print}, into an image of its size.
     */
    ImageData render(Control control) {
        Point size = control.getSize();
        Image image = new Image(display, size.x, size.y);
        GC gc = new GC(image);
        try {
            require(control.print(gc), "Control.print is not supported");
        } finally {
            gc.dispose();
        }
        try {
            return image.getImageData();
        } finally {
            image.dispose();
        }
    }

    /**
     * Writes {@code <directory>/<name>-<mode>.png}.
     */
    void writeImage(String name, ImageData data) throws IOException {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { data };
        try (OutputStream file = Files.newOutputStream(directory.resolve(name + "-" + mode + ".png"))) {
            loader.save(file, SWT.IMAGE_PNG);
        }
    }

    /**
     * Writes {@code <directory>/<name>-<mode>.txt} : values that must be the same in JVM mode and in a native
     * executable.
     */
    void writeText(String name, String text) throws IOException {
        Files.writeString(directory.resolve(name + "-" + mode + ".txt"), text, StandardCharsets.UTF_8);
    }

    /**
     * The resources of a check, disposed in the reverse order of their creation.
     */
    static final class Resources implements AutoCloseable {

        private final List<Resource> resources = new ArrayList<>();

        <T extends Resource> T add(T resource) {
            resources.add(resource);
            return resource;
        }

        @Override
        public void close() {
            for (int i = resources.size() - 1; i >= 0; i--) {
                resources.get(i).dispose();
            }
            resources.clear();
        }
    }

    /**
     * Forwards the standard error, and keeps its lines.
     */
    private static final class ErrorWatcher extends OutputStream {

        private final PrintStream delegate;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        ErrorWatcher(PrintStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void write(int b) {
            delegate.write(b);
            if (b == '\n') {
                String text = line.toString(StandardCharsets.UTF_8).strip();
                // the warnings of the JDK about the native access of SWT are not errors of the checks
                if (!text.isEmpty() && !text.startsWith("WARNING:")) {
                    lines.add(text);
                }
                line.reset();
            } else {
                line.write(b);
            }
        }

        List<String> lines() {
            return List.copyOf(lines);
        }
    }
}
