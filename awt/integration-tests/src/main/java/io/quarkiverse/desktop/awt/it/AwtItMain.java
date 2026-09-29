package io.quarkiverse.desktop.awt.it;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Canvas;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Label;
import java.awt.List;
import java.awt.MediaTracker;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.Panel;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.accessibility.AccessibleContext;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.StreamPrintService;
import javax.print.StreamPrintServiceFactory;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.MediaSizeName;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;

import jakarta.inject.Inject;

import io.quarkiverse.desktop.awt.DesktopLifecycle;
import io.quarkus.runtime.ImageMode;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Runs the checks of the scenario named by the first argument, prints one {@code RESULT <check> OK|FAILED|SKIPPED}
 * line per check and a {@code SUMMARY} line, and exits with 1 when a check failed.
 * <p>
 * Scenarios :
 * <ul>
 * <li>{@code awt [directory]} : AWT with heavyweight components, Java2D, fonts, images, printing to a PostScript stream,
 * data transfer, desktop integration and sound. The rendered frame is written as a PNG file to the directory (the
 * temporary directory by default).</li>
 * <li>{@code access-bridge} : the toolkit starts with the Java Access Bridge enabled (Windows).</li>
 * </ul>
 * It never prints on a printer (only to a PostScript stream in memory), never uses the Desktop actions, does not take
 * the focus, and disposes its windows.
 */
@QuarkusMain
public class AwtItMain implements QuarkusApplication {

    private static final long TIMEOUT_SECONDS = 20;

    @Inject
    DesktopLifecycle lifecycle;

    @Inject
    UserInterface userInterface;

    private final java.util.List<String> failures = new ArrayList<>();
    private int ok;

    @Override
    public int run(String... args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "awt";
        switch (scenario) {
            case "awt" -> awt(Path.of(args.length > 1 ? args[1] : System.getProperty("java.io.tmpdir")));
            case "access-bridge" -> accessBridge();
            default -> {
                System.out.println("Unknown scenario " + scenario);
                return 2;
            }
        }
        System.out.println("SUMMARY ok=" + ok + " failed=" + failures.size() + " " + failures);
        return failures.isEmpty() ? 0 : 1;
    }

    // ------------------------------------------------------------------------------------------------------------ AWT

    private void awt(Path directory) {
        boolean headless = GraphicsEnvironment.isHeadless();
        check("environment", () -> environment(headless));
        check("static-initializer", () -> {
            require(Palette.ACCENT.getRGB() == 0xff0096c9, "Palette.ACCENT " + Palette.ACCENT);
            return "accent=" + Integer.toHexString(Palette.ACCENT.getRGB()) + " title=" + Palette.TITLE.getFamily();
        });
        check("java2d", AwtItMain::java2d);
        check("fonts", AwtItMain::fonts);
        check("imageio", AwtItMain::imageio);
        check("imageio-plugins", AwtItMain::imageioPlugins);
        check("text-attributes", AwtItMain::textAttributes);
        check("java-beans", AwtItMain::javaBeans);
        check("print-stream", AwtItMain::printStream);
        check("print-services", () -> {
            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            PrintService defaultService = PrintServiceLookup.lookupDefaultPrintService();
            return "services=" + services.length + " default=" + (defaultService == null ? null : defaultService.getName());
        });
        check("flavor-map", () -> {
            SystemFlavorMap flavorMap = (SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap();
            java.util.List<String> natives = flavorMap.getNativesForFlavor(DataFlavor.stringFlavor);
            require(!natives.isEmpty(), "no native format for strings");
            return "stringFlavor=" + natives.toString().replace(' ', '_') + " imageFlavor="
                    + flavorMap.getNativesForFlavor(DataFlavor.imageFlavor).toString().replace(' ', '_');
        });
        check("sound", () -> {
            AudioFileFormat.Type[] types = AudioSystem.getAudioFileTypes();
            require(types.length > 0, "no audio file type");
            return "mixers=" + AudioSystem.getMixerInfo().length + " fileTypes=" + types.length;
        });
        if (headless) {
            skip("desktop", "headless");
            skip("frame", "headless");
            // DesktopLifecycle.start() would stop the application with exit code 1
            skip("startup-event", "headless");
            return;
        }
        check("desktop", () -> "desktop=" + Desktop.isDesktopSupported() + " browse="
                + (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                + " taskbar=" + Taskbar.isTaskbarSupported() + " tray=" + SystemTray.isSupported());
        check("frame", () -> frame(directory));
        check("startup-event", this::startupEvent);
    }

    /**
     * {@code DesktopStartupEvent} fired on the event dispatch thread ({@code DesktopLifecycle.start()}, manual mode).
     */
    private String startupEvent() throws InterruptedException {
        lifecycle.start();
        require(userInterface.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "DesktopStartupEvent is not fired");
        require(userInterface.eventDispatchThread, "DesktopStartupEvent observed on " + userInterface.thread);
        return "thread=" + userInterface.thread.replace(' ', '_');
    }

    private static String environment(boolean headless) {
        StringBuilder environment = new StringBuilder();
        environment.append("headless=").append(headless);
        environment.append(" mode=").append(ImageMode.current().isNativeImage() ? "native" : "jvm");
        // macOS : Quarkus runs on a new thread named main, the first thread runs the Cocoa event loop
        environment.append(" thread=").append(Thread.currentThread().getName());
        environment.append(" mainThreadParked=").append(System.getProperty("io.quarkiverse.desktop.main-thread-parked"));
        environment.append(" dpiaware=").append(System.getProperty("sun.java2d.dpiaware"));
        environment.append(" javaHome=").append(System.getProperty("java.home") != null);
        environment.append(" fontconfig=").append(System.getProperty("sun.awt.fontconfig") != null);
        environment.append(" unicodeEncoding=").append(System.getProperty("sun.io.unicode.encoding"));
        environment.append(" assistiveTechnologies=")
                .append(System.getProperty("javax.accessibility.assistive_technologies", "<unset>").isEmpty() ? "<empty>"
                        : System.getProperty("javax.accessibility.assistive_technologies", "<unset>"));
        if (!headless) {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            environment.append(" toolkit=").append(toolkit.getClass().getName());
            environment.append(" configuration=").append(configuration.getClass().getName());
            environment.append(" resolution=").append(toolkit.getScreenResolution());
            environment.append(" scale=").append(configuration.getDefaultTransform().getScaleX());
        }
        return environment.toString();
    }

    // --------------------------------------------------------------------------------------------------------- Java2D

    /**
     * Paints shapes, a gradient, anti aliased text and XOR mode : the colors are checked by the callers.
     */
    static void draw(Graphics2D g, int width, int height) {
        g.setColor(Palette.BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.setColor(Palette.ACCENT);
        g.fillRect(10, 10, 60, 40);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Palette.HIGHLIGHT);
        g.fill(new Ellipse2D.Double(90, 10, 60, 40));
        g.setStroke(Palette.OUTLINE);
        g.setColor(Color.DARK_GRAY);
        g.draw(new RoundRectangle2D.Double(5, 5, width - 10, height - 10, 12, 12));
        g.setPaint(new GradientPaint(160, 10, Color.YELLOW, 220, 50, Color.MAGENTA));
        g.fillRect(160, 10, 60, 40);
        g.setFont(Palette.TITLE);
        g.setColor(Color.BLACK);
        g.drawString("AWT مرحبا é", 10, 90);
        // XOR mode : (background ^ black ^ white) in the top left square of the bottom right corner
        g.setColor(Color.BLACK);
        g.setXORMode(Color.WHITE);
        g.fillRect(width - 40, height - 40, 20, 20);
        g.setPaintMode();
    }

    private static Object java2d() {
        int width = 240;
        int height = 120;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        draw(g, width, height);
        g.dispose();
        requireColor(image, 20, 20, Palette.ACCENT.getRGB(), "filled rectangle");
        requireColor(image, width - 30, height - 30, 0xff000000 | (Palette.BACKGROUND.getRGB() ^ 0xffffff), "XOR mode");
        require(countDifferent(image, 10, 70, 200, 30, Palette.BACKGROUND.getRGB()) > 50, "no text drawn");
        // General loops of the image types without their own XOR loops (loaded by name)
        for (int type : new int[] { BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_BYTE_BINARY,
                BufferedImage.TYPE_4BYTE_ABGR_PRE, BufferedImage.TYPE_USHORT_565_RGB }) {
            BufferedImage other = new BufferedImage(width, height, type);
            Graphics2D og = other.createGraphics();
            draw(og, width, height);
            og.dispose();
        }
        return "text=" + countDifferent(image, 10, 70, 200, 30, Palette.BACKGROUND.getRGB());
    }

    // ---------------------------------------------------------------------------------------------------------- fonts

    private static Object fonts() {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT);
        require(families.length > 0, "no font family");
        Font dialog = new Font(Font.DIALOG, Font.PLAIN, 12);
        // the legacy encodings of fonts (TrueType cmap subtables and names, the Windows font configuration) and of text
        // (RTF font charsets)
        for (String charset : new String[] { "Shift_JIS", "GBK", "Big5", "EUC-KR", "windows-1251", "windows-31j" }) {
            require(Charset.isSupported(charset), charset + " not supported");
        }
        return "families=" + families.length + " dialog=" + dialog.getFontName(Locale.ROOT).replace(' ', '_')
                + " arabic=" + (dialog.canDisplay('م')) + " cjk=" + dialog.canDisplay('你') + " charsets="
                + Charset.availableCharsets().size();
    }

    // -------------------------------------------------------------------------------------------------------- ImageIO

    private static Object imageio() throws Exception {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Palette.ACCENT);
        g.fillRect(0, 0, 64, 48);
        g.dispose();
        StringBuilder result = new StringBuilder();
        for (String format : new String[] { "png", "jpg", "gif", "bmp" }) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            require(ImageIO.write(image, format, bytes), "no " + format + " writer");
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
            require(read != null && read.getWidth() == 64, format + " not read back");
            result.append(format).append('=').append(bytes.size()).append(' ');
            if (format.equals("jpg")) {
                // The Toolkit image decoders (JPEGImageDecoder, native)
                Image toolkitImage = Toolkit.getDefaultToolkit().createImage(bytes.toByteArray());
                MediaTracker tracker = new MediaTracker(new Canvas());
                tracker.addImage(toolkitImage, 0);
                tracker.waitForAll(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                require(!tracker.isErrorAny() && toolkitImage.getWidth(null) == 64, "Toolkit JPEG image not decoded");
                result.append("toolkitJpeg=").append(toolkitImage.getWidth(null)).append(' ');
            }
        }
        return result.toString().trim();
    }

    /**
     * The JDK plugins looked up by name : the writer of a reader and the reader of a writer, the metadata formats and
     * their descriptions (resource bundles).
     */
    private static Object imageioPlugins() throws Exception {
        StringBuilder result = new StringBuilder();
        for (String format : new String[] { "png", "jpeg", "gif", "bmp", "wbmp", "tiff" }) {
            ImageReader reader = ImageIO.getImageReadersByFormatName(format).next();
            ImageWriter writer = ImageIO.getImageWriter(reader);
            require(writer != null, "no writer for the " + format + " reader");
            require(ImageIO.getImageReader(writer) != null, "no reader for the " + format + " writer");
            IIOMetadataFormat metadataFormat = reader.getOriginatingProvider().getImageMetadataFormat(
                    reader.getOriginatingProvider().getNativeImageMetadataFormatName());
            require(metadataFormat != null, "no " + format + " metadata format");
            // null for the WBMP and TIFF formats, whose resource bundles do not exist (looked up anyway)
            String rootDescription = metadataFormat.getElementDescription(metadataFormat.getRootName(), Locale.ENGLISH);
            result.append(format).append('=').append(metadataFormat.getRootName())
                    .append(rootDescription == null ? "" : "(described)").append(' ');
        }
        String description = IIOMetadataFormatImpl.getStandardFormatInstance().getElementDescription("Chroma",
                Locale.ENGLISH);
        require(description != null, "no description of the standard metadata format");
        result.append("standard=").append(description.replace(' ', '_'));
        // TIFF : the resource bundle of the stream metadata format does not exist either, and the native metadata format
        // classes that the image and stream metadata name do not exist (JDK bugs : null, IllegalStateException)
        ImageReader tiff = ImageIO.getImageReadersByFormatName("tiff").next();
        IIOMetadataFormat streamFormat = tiff.getOriginatingProvider()
                .getStreamMetadataFormat("javax_imageio_tiff_stream_1.0");
        require(streamFormat != null, "no TIFF stream metadata format");
        require(streamFormat.getElementDescription("ByteOrder", Locale.US) == null, "TIFF stream metadata described");
        ImageWriter tiffWriter = ImageIO.getImageWriter(tiff);
        for (IIOMetadata metadata : java.util.List.of(tiffWriter.getDefaultStreamMetadata(null),
                tiffWriter.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB),
                        tiffWriter.getDefaultWriteParam()))) {
            try {
                metadata.getMetadataFormat(metadata.getNativeMetadataFormatName());
                require(false, "the " + metadata.getNativeMetadataFormatName() + " format exists");
            } catch (IllegalStateException e) {
                result.append(' ').append(metadata.getNativeMetadataFormatName()).append("=no_format");
            }
        }
        return result.toString();
    }

    /**
     * The text attributes are serializable : the constant is read back.
     */
    private static Object textAttributes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(TextAttribute.KERNING);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            require(in.readObject() == TextAttribute.KERNING, "TextAttribute.KERNING not read back");
        }
        return "serialized=" + bytes.size();
    }

    /**
     * The core of the JavaBeans API : the property editors of the JDK, XMLEncoder and XMLDecoder with the persistence
     * delegates of the JDK types, and the bean properties of the AWT classes (quarkus.desktop.awt.java-beans.jdk-classes,
     * enabled by default).
     */
    private static Object javaBeans() throws Exception {
        PropertyEditor editor = PropertyEditorManager.findEditor(int.class);
        require(editor != null, "no int editor");
        editor.setAsText("42");
        require(Integer.valueOf(42).equals(editor.getValue()), "int editor value " + editor.getValue());
        require(PropertyEditorManager.findEditor(String.class) != null, "no String editor");
        java.util.List<Object> values = new ArrayList<>(java.util.List.of(new Color(30, 136, 229, 200),
                new Font(Font.SERIF, Font.BOLD, 13), new Insets(1, 2, 3, 4), new Point(-5, 7),
                new Rectangle(10, 20, 300, 400), new Dimension(640, 480), new Date(0), new TreeMap<>(Map.of("a", 1)),
                Locale.Category.FORMAT, "text",
                // primitive classes (the component types of primitive arrays) : the TYPE fields of the wrappers
                int.class, boolean.class));
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        java.util.List<Exception> exceptions = new ArrayList<>();
        try (XMLEncoder encoder = new XMLEncoder(xml)) {
            encoder.setExceptionListener(exceptions::add);
            encoder.writeObject(values);
        }
        require(exceptions.isEmpty(), "encoding : " + exceptions);
        Object decoded;
        try (XMLDecoder decoder = new XMLDecoder(new ByteArrayInputStream(xml.toByteArray()), null, exceptions::add)) {
            decoded = decoder.readObject();
        }
        require(exceptions.isEmpty(), "decoding : " + exceptions);
        require(values.equals(decoded), "decoded " + decoded + " instead of " + values);
        PropertyDescriptor[] properties = Introspector.getBeanInfo(Button.class).getPropertyDescriptors();
        require(Arrays.stream(properties).anyMatch(p -> p.getName().equals("label") && p.getWriteMethod() != null),
                "no label property of java.awt.Button");
        return "editor=" + editor.getClass().getSimpleName() + " xml=" + xml.size() + " buttonProperties="
                + properties.length;
    }

    // ------------------------------------------------------------------------------------------------------- printing

    private static Object printStream() throws Exception {
        DocFlavor flavor = DocFlavor.SERVICE_FORMATTED.PRINTABLE;
        StreamPrintServiceFactory[] factories = StreamPrintServiceFactory.lookupStreamPrintServiceFactories(flavor,
                "application/postscript");
        require(factories.length > 0, "no PostScript stream print service");
        ByteArrayOutputStream postScript = new ByteArrayOutputStream();
        StreamPrintService service = factories[0].getPrintService(postScript);
        Printable printable = (graphics, pageFormat, pageIndex) -> {
            if (pageIndex > 0) {
                return Printable.NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) graphics;
            g.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            draw(g, 240, 120);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SERIF, Font.PLAIN, 12));
            g.drawString("Printed by Quarkus Desktop AWT", 10, 150);
            return Printable.PAGE_EXISTS;
        };
        DocPrintJob job = service.createPrintJob();
        HashPrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
        attributes.add(MediaSizeName.ISO_A4);
        job.print(new SimpleDoc(printable, flavor, null), attributes);
        String header = new String(postScript.toByteArray(), 0, Math.min(postScript.size(), 14),
                StandardCharsets.ISO_8859_1);
        require(header.startsWith("%!PS-Adobe"), "not PostScript : " + header);
        require(postScript.size() > 1000, "PostScript too small : " + postScript.size());
        return "bytes=" + postScript.size() + " service=" + service.getClass().getName() + " pageFormat="
                + new PageFormat().getWidth();
    }

    // ---------------------------------------------------------------------------------------------------------- frame

    /**
     * A canvas painted with Java2D.
     */
    static final class Drawing extends Canvas {

        Drawing() {
            setPreferredSize(new Dimension(240, 120));
        }

        @Override
        public void paint(Graphics g) {
            draw((Graphics2D) g, getWidth(), getHeight());
        }
    }

    private Object frame(Path directory) throws Exception {
        Frame[] frames = new Frame[1];
        Dialog[] dialogs = new Dialog[1];
        try {
            EventQueue.invokeAndWait(() -> {
                Frame frame = new Frame("Quarkus Desktop AWT مرحبا");
                frames[0] = frame;
                // Do not take the focus of the user
                frame.setAutoRequestFocus(false);
                frame.setFocusableWindowState(false);
                MenuBar menuBar = new MenuBar();
                Menu menu = new Menu("File");
                menu.add(new MenuItem("Open"));
                menuBar.add(menu);
                frame.setMenuBar(menuBar);
                Panel controls = new Panel(new FlowLayout(FlowLayout.LEFT));
                controls.add(new Button("Button"));
                controls.add(new Label("Label"));
                controls.add(new TextField("Text مرحبا", 12));
                controls.add(new Checkbox("Checkbox", true));
                Choice choice = new Choice();
                choice.add("One");
                choice.add("Two");
                controls.add(choice);
                List list = new List(3);
                list.add("First");
                list.add("Second");
                list.add("Third");
                list.select(1);
                controls.add(list);
                frame.add(controls, BorderLayout.NORTH);
                frame.add(new Drawing(), BorderLayout.CENTER);
                PopupMenu popup = new PopupMenu();
                popup.add("Popup");
                frame.add(popup);
                BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = icon.createGraphics();
                g.setColor(Palette.ACCENT);
                g.fillOval(0, 0, 32, 32);
                g.dispose();
                frame.setIconImage(icon);
                frame.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                frame.pack();
                frame.setLocation(40, 40);
                frame.setVisible(true);
                Dialog dialog = new Dialog(frame, "Dialog", false);
                dialogs[0] = dialog;
                dialog.setAutoRequestFocus(false);
                dialog.setFocusableWindowState(false);
                dialog.add(new Label("Modeless dialog"));
                dialog.pack();
                dialog.setLocation(80, 80);
                dialog.setVisible(true);
            });
            Frame frame = frames[0];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!frame.isShowing() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            require(frame.isShowing(), "the frame is not showing");
            Toolkit.getDefaultToolkit().sync();
            Thread.sleep(300);
            StringBuilder result = new StringBuilder();
            Exception[] error = new Exception[1];
            EventQueue.invokeAndWait(() -> {
                try {
                    result.append(renderFrame(frame, directory));
                } catch (Exception e) {
                    error[0] = e;
                }
            });
            if (error[0] != null) {
                throw error[0];
            }
            return result;
        } finally {
            EventQueue.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
            });
        }
    }

    private static String renderFrame(Frame frame, Path directory) throws Exception {
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        frame.printAll(g);
        g.dispose();
        Canvas drawing = (Canvas) frame.getComponent(1);
        requireColor(image, drawing.getX() + 20, drawing.getY() + 20, Palette.ACCENT.getRGB(), "printed canvas");
        Path file = directory.resolve("awt-it-" + (ImageMode.current().isNativeImage() ? "native" : "jvm") + ".png");
        Files.createDirectories(directory);
        require(ImageIO.write(image, "png", file.toFile()), "no PNG writer");
        BufferedImage read = ImageIO.read(file.toFile());
        require(read.getWidth() == image.getWidth(), "PNG not read back");
        AccessibleContext accessibleContext = frame.getAccessibleContext();
        return "size=" + frame.getWidth() + "x" + frame.getHeight() + " insets=" + frame.getInsets().top + ","
                + frame.getInsets().left + " png=" + file.getFileName() + " accessibleChildren="
                + accessibleContext.getAccessibleChildrenCount() + " role="
                + accessibleContext.getAccessibleRole().toDisplayString(Locale.ROOT).replace(' ', '_');
    }

    // ------------------------------------------------------------------------------------------------- access bridge

    private void accessBridge() {
        if (!System.getProperty("os.name", "").startsWith("Windows") || GraphicsEnvironment.isHeadless()) {
            skip("access-bridge", "Windows only");
            return;
        }
        // Read when the toolkit starts : the same as a user who enabled the Java Access Bridge (jabswitch -enable)
        System.setProperty("javax.accessibility.assistive_technologies", "com.sun.java.accessibility.AccessBridge");
        check("access-bridge", () -> {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Frame[] frames = new Frame[1];
            try {
                EventQueue.invokeAndWait(() -> {
                    Frame frame = new Frame("Access bridge");
                    frames[0] = frame;
                    frame.setAutoRequestFocus(false);
                    frame.setFocusableWindowState(false);
                    frame.add(new Button("Accessible button"));
                    frame.pack();
                    frame.setLocation(40, 40);
                    frame.setVisible(true);
                });
                Thread.sleep(500);
                return "toolkit=" + toolkit.getClass().getName() + " children="
                        + frames[0].getAccessibleContext().getAccessibleChildrenCount();
            } finally {
                EventQueue.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) {
                        window.dispose();
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------- support

    private void check(String name, Callable<Object> check) {
        try {
            Object value = check.call();
            System.out.println("RESULT " + name + " OK " + value);
            ok++;
        } catch (Throwable t) {
            System.out.println("RESULT " + name + " FAILED " + t);
            t.printStackTrace(System.out);
            failures.add(name);
        }
    }

    private static void skip(String name, String reason) {
        System.out.println("RESULT " + name + " SKIPPED " + reason);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireColor(BufferedImage image, int x, int y, int expected, String what) {
        int actual = image.getRGB(x, y);
        require(actual == expected, what + " : pixel (" + x + "," + y + ") is " + Integer.toHexString(actual)
                + ", expected " + Integer.toHexString(expected));
    }

    private static int countDifferent(BufferedImage image, int x, int y, int width, int height, int rgb) {
        int count = 0;
        for (int i = x; i < x + width; i++) {
            for (int j = y; j < y + height; j++) {
                if (image.getRGB(i, j) != rgb) {
                    count++;
                }
            }
        }
        return count;
    }
}
