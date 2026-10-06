package io.quarkiverse.desktop.showcase.swt.pages.images;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.ImageFileNameProvider;
import org.eclipse.swt.graphics.ImageGcDrawer;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtMode;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;
import io.quarkiverse.desktop.showcase.swt.core.SwtTiles;
import io.quarkiverse.desktop.showcase.swt.core.SwtTiles.Tile;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * Images with SWT : {@link ImageData} with direct palettes (24, 32 and 16 bits) and indexed palettes (8, 4 and 1 bit),
 * per pixel alpha ({@code alphaData}), a global alpha, a transparent pixel and masks
 * ({@code Image(Device, ImageData source, ImageData mask)}), {@code Image(Device, Image, flag)} copies, gray and
 * disabled images ; {@link ImageLoader} round trips in every format SWT writes (PNG, BMP, BMP RLE, GIF, ICO, JPEG,
 * TIFF), decoded and drawn ; an animated GIF (frames with their position, delay and disposal method) built, saved,
 * loaded, drawn as decoded and composited ; scaling with {@code GC.drawImage} at the interpolations of
 * {@code GC.setInterpolation} ; multi-zoom images ({@link ImageDataProvider}, {@link ImageFileNameProvider},
 * {@link ImageGcDrawer}, {@code Image.getImageData(zoom)}, {@code GC.drawImage(image, x, y, width, height)} picking the
 * best fitting zoom) ; the system images ; transparency drawn on colored backgrounds.
 * <p>
 * On Windows, {@code new Image(device, ImageData)} creates a DIB section ({@code CreateDIBSection} : a
 * {@code BITMAPINFOHEADER} with its {@code RGBQUAD} color table or its {@code BI_BITFIELDS} masks), read back by
 * {@code getImageData} ({@code GetObject} with {@code BITMAP} and {@code DIBSECTION}, {@code GetDIBits}) ; masked
 * images are icons ({@code CreateIconIndirect}, {@code GetIconInfo} with {@code ICONINFO}) ; a plain {@code GC} blends
 * the alpha images with {@code AlphaBlend} ({@code BLENDFUNCTION}), an advanced one (the tiles, anti-aliased) draws
 * GDI+ bitmaps made from the DIB pixels with an interpolation mode ; the files of an {@code ImageFileNameProvider} are
 * decoded by GDI+ ({@code Bitmap(filename)}, {@code LockBits} into a {@code BitmapData}, {@code ColorPalette}) ; the
 * system icons come from {@code LoadIconWithScaleDown} at every zoom. {@code ImageLoader} runs the codecs of SWT, in
 * Java, on Windows and macOS (GdkPixbuf on Linux, with Cairo image surfaces). In a native executable, every struct
 * that these natives read or write needs its fields registered for JNI access : a missing field fails, or silently
 * yields a black, transparent or garbled image. Every pixel and every check must be equal in the JVM and native runs.
 * <p>
 * What SWT itself does, in both runtimes (informational checks) : Windows makes the DIBs of 16 bits 555 (a 565 image
 * loses the low bit of green) ; the ICO writer stores the pixels of 24 bits as they are (the masks of the DIBs, BGR :
 * an image of RGB masks comes back with red and blue swapped) ; Windows draws nothing of an icon whose mask is fully
 * visible ; {@code drawImage(image, x, y, width, height)} picks the image of the zoom that {@code swt.autoScale}
 * allows (with {@code -Dswt.autoScale=100}, as the tools pin it : the image of 100 %, scaled).
 * <p>
 * Everything is drawn offscreen ({@link SwtSnapshots#offscreen}, {@link SwtTiles}) from fixed pixel data computed with
 * integers : the same pixels in every run. The files of the {@code ImageFileNameProvider} are written in a temporary
 * directory by a background thread, loaded, then deleted.
 */
@Singleton
public class SwtImagesPage implements SwtPage {

    /** Size of the test images. */
    static final int W = 88;
    static final int H = 56;
    /** Size of the images of the transparency strip. */
    static final int SW = 64;
    static final int SH = 40;

    private static final int WIDTH = 1000;
    /** Width of the name column of the check tables. */
    private static final int NAME_WIDTH = 330;
    private static final int TILE_HEIGHT = 130;

    private static final int INK = 0x263238;
    private static final int MUTED = 0x607D8B;
    private static final int RED = 0xC62828;
    private static final int CHECKER = 0xDDE3E8;

    /** The 16 colors of the 4 bit images : index 0 is the background (the transparent pixel). */
    private static final int[] COLORS_16 = { 0xFFFFFF, 0x263238, 0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1,
            0x1E88E5, 0x5E35B1, 0xD81B60, 0x8D6E63, 0x90A4AE, 0xC0CA33, 0x26A69A, 0x7E57C2, 0xEC407A };

    /** The checks, set once the content is loaded : completes on the user interface thread. */
    private CompletionStage<Void> loaded = CompletableFuture.completedFuture(null);
    /** The directory of the files of the ImageFileNameProvider, until they are deleted. */
    private volatile Path files;

    @Override
    public String id() {
        return "swt-images";
    }

    @Override
    public String title() {
        return "Images and ImageLoader";
    }

    @Override
    public String category() {
        return SwtCategories.IMAGES;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Display display = parent.getDisplay();
        Composite page = SwtKit.page(parent, 8);
        SwtKit.heading(page, "Images and ImageLoader");
        SwtKit.text(page, "ImageData with direct and indexed palettes, alpha, transparent pixels and masks; ImageLoader"
                + " round trips in every format SWT writes (encoded, decoded and drawn); an animated GIF; scaling at"
                + " the interpolations of the GC; multi-zoom images; the system images; transparency on colored"
                + " backgrounds. Every image is computed from fixed data and drawn offscreen.", SwtKit.TEXT_WIDTH);

        SwtKit.title(page, "ImageData: direct and indexed palettes, alpha, transparency, masks, Image(Image, flag)");
        SwtTiles dataTiles = new SwtTiles(9, 111, TILE_HEIGHT, dataTiles(display));
        ImageData dataImage = dataTiles.paint();
        SwtKit.image(page, dataImage);

        SwtKit.title(page, "ImageLoader round trips: encoded with every SWT writer, decoded and drawn");
        List<RoundTrip> trips = roundTrips();
        SwtKit.image(page, new SwtTiles(9, 111, TILE_HEIGHT, tripTiles(trips)).paint());

        SwtKit.title(page, "Animated GIF: 5 frames with their position, delay and disposal method");
        Gif gif = gif();
        painted(page, gc -> paintGif(gc, gif));

        SwtKit.title(page, "Scaling, interpolation, multi-zoom images (100 % and 200 %) and system images");
        Composite holder = SwtKit.column(page, 0);
        SwtKit.caption(holder, "Writing the files of the ImageFileNameProvider...");

        SwtKit.title(page, "Transparency on colored backgrounds (plain GC: AlphaBlend; setAlpha: GDI+ on Windows)");
        ImageData strip = painted(page, SwtImagesPage::paintStrip);

        ChecksTable dataChecks = table(page, "ImageData, Image and transparency");
        ChecksTable tripChecks = table(page, "ImageLoader round trips: bytes, decoded size and depth, fidelity,"
                + " SHA-256");
        ChecksTable gifChecks = table(page, "Animated GIF");
        ChecksTable zoomChecks = table(page, "Scaling, multi-zoom images and system images");

        // the files of the ImageFileNameProvider are written by a background thread, then loaded on the user interface
        // thread with the checks (in the interactive mode too) ; ready() waits for it
        loaded = UiStages.background(SwtImagesPage::writeProviderFiles).handle((directory, error) -> {
            files = directory;
            try {
                if (holder.isDisposed()) {
                    return null;
                }
                for (Control child : holder.getChildren()) {
                    child.dispose();
                }
                if (error == null) {
                    ZoomResult zoom = zoomTiles(display, directory);
                    SwtKit.image(holder, zoom.image());
                    zoomChecks.setChecks(zoom.checks());
                } else {
                    String message = SwtChecks.describe(error);
                    SwtKit.text(holder, "The files of the ImageFileNameProvider could not be written: " + message,
                            SwtKit.TEXT_WIDTH);
                    zoomChecks.setChecks(List.of(Check.fail("ImageFileNameProvider files", message)));
                }
                holder.requestLayout();
                dataChecks.setChecks(dataChecks(display, dataTiles, dataImage, strip));
                tripChecks.setChecks(tripChecks(display, trips));
                gifChecks.setChecks(gifChecks(gif));
                return null;
            } finally {
                deleteFiles();
            }
        });
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        return loaded;
    }

    private static ChecksTable table(Composite parent, String title) {
        return ChecksTable.table(parent, title, List.of(Check.info("state", "pending")), NAME_WIDTH,
                ChecksTable.WIDTH);
    }

    @Override
    public void dispose(Control content) {
        deleteFiles();
    }

    /**
     * Deletes the files of the ImageFileNameProvider, if any, on a background thread.
     */
    private void deleteFiles() {
        Path directory = files;
        files = null;
        if (directory != null) {
            UiStages.background(() -> {
                delete(directory);
                return null;
            });
        }
    }

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * An image {@link #WIDTH} wide painted by {@code painter}, as high as the drawing (the painter returns its bottom,
     * measured by a first pass into an image of one pixel high : the captions are in the fonts of the machine). Returns
     * the pixels shown.
     */
    private static ImageData painted(Composite parent, ToIntFunction<GC> painter) {
        int[] bottom = { 1 };
        SwtSnapshots.offscreen(WIDTH, 1, gc -> bottom[0] = painter.applyAsInt(gc));
        ImageData data = SwtSnapshots.offscreen(WIDTH, bottom[0] + 2, painter::applyAsInt);
        SwtKit.image(parent, data);
        return data;
    }

    // ------------------------------------------------------------------------------------------------- image data

    static RGB rgb(int rgb) {
        return new RGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /**
     * The RGB of the test pattern at ({@code x}, {@code y}) of a {@code w x h} image : red grows to the right, green
     * downwards, blue in 8 x 8 checks, a white ring in the middle. Integer arithmetic only.
     */
    static int patternRgb(int x, int y, int w, int h) {
        int dx = 2 * x - (w - 1);
        int dy = 2 * y - (h - 1);
        int d = dx * dx + dy * dy;
        int outer = Math.min(w, h) * 2 / 3;
        int inner = outer - 6;
        if (d >= inner * inner && d < outer * outer) {
            return 0xFFFFFF;
        }
        int r = x * 255 / (w - 1);
        int g = y * 255 / (h - 1);
        int b = (x / 8 + y / 8) % 2 == 0 ? 0x30 : 0xD0;
        return r << 16 | g << 8 | b;
    }

    /**
     * {@code true} inside the ellipse inscribed in a {@code w x h} image, {@code margin} pixels inside its border.
     */
    static boolean inside(int x, int y, int w, int h, int margin) {
        long a = w - 1 - 2L * margin;
        long b = h - 1 - 2L * margin;
        long dx = 2L * x - (w - 1);
        long dy = 2L * y - (h - 1);
        return dx * dx * b * b + dy * dy * a * a <= a * a * b * b;
    }

    /**
     * An image of zeros : {@code scanlinePad} bytes per line at least (1 for TIFF, 4 by default).
     */
    static ImageData blank(int w, int h, int depth, PaletteData palette, int scanlinePad) {
        int bytesPerLine = ((w * depth + 7) / 8 + scanlinePad - 1) / scanlinePad * scanlinePad;
        return new ImageData(w, h, depth, palette, scanlinePad, new byte[bytesPerLine * h]);
    }

    /**
     * The test pattern in a direct palette of {@code depth} bits with the masks {@code red}, {@code green} and
     * {@code blue}.
     */
    static ImageData direct(int w, int h, int depth, int red, int green, int blue, int scanlinePad) {
        PaletteData palette = new PaletteData(red, green, blue);
        ImageData data = blank(w, h, depth, palette, scanlinePad);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data.setPixel(x, y, palette.getPixel(rgb(patternRgb(x, y, w, h))));
            }
        }
        return data;
    }

    static ImageData direct24(int w, int h, int scanlinePad) {
        return direct(w, h, 24, 0xFF0000, 0xFF00, 0xFF, scanlinePad);
    }

    /**
     * A palette of 256 colors : the 6 x 6 x 6 color cube, then 40 grays.
     */
    static PaletteData cube() {
        RGB[] colors = new RGB[256];
        for (int i = 0; i < 216; i++) {
            colors[i] = new RGB(i / 36 * 51, i / 6 % 6 * 51, i % 6 * 51);
        }
        for (int i = 216; i < 256; i++) {
            int v = (i - 216) * 255 / 39;
            colors[i] = new RGB(v, v, v);
        }
        return new PaletteData(colors);
    }

    /**
     * The index of the nearest color of the cube.
     */
    static int cubeIndex(int rgb) {
        int r = (((rgb >> 16) & 0xFF) + 25) / 51;
        int g = (((rgb >> 8) & 0xFF) + 25) / 51;
        int b = ((rgb & 0xFF) + 25) / 51;
        return r * 36 + g * 6 + b;
    }

    /**
     * The test pattern in the 256 colors of the cube.
     */
    static ImageData indexed8(int w, int h, int scanlinePad) {
        ImageData data = blank(w, h, 8, cube(), scanlinePad);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data.setPixel(x, y, cubeIndex(patternRgb(x, y, w, h)));
            }
        }
        return data;
    }

    static PaletteData colors16() {
        return new PaletteData(Arrays.stream(COLORS_16).mapToObj(SwtImagesPage::rgb).toArray(RGB[]::new));
    }

    /**
     * Concentric bands of the 15 colors in an ellipse, the background (index 0) around.
     */
    static ImageData indexed4(int w, int h, int scanlinePad) {
        ImageData data = blank(w, h, 4, colors16(), scanlinePad);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = x - w / 2;
                int dy = y - h / 2;
                data.setPixel(x, y, inside(x, y, w, h, 1) ? 1 + (dx * dx + dy * dy) / 48 % 15 : 0);
            }
        }
        return data;
    }

    /**
     * Black and white 8 x 8 checks, inverted inside an ellipse.
     */
    static ImageData indexed1(int w, int h, int scanlinePad) {
        ImageData data = blank(w, h, 1, new PaletteData(new RGB(0, 0, 0), new RGB(255, 255, 255)), scanlinePad);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data.setPixel(x, y, (x / 8 + y / 8) % 2 ^ (inside(x, y, w, h, 6) ? 1 : 0));
            }
        }
        return data;
    }

    /**
     * {@code data} with its pixel {@code index} transparent.
     */
    static ImageData transparent(ImageData data, int index) {
        data.transparentPixel = index;
        return data;
    }

    /**
     * The test pattern in 24 bits with a ramp of alpha, transparent on the left, opaque on the right.
     */
    static ImageData alphaRamp(int w, int h) {
        ImageData data = direct24(w, h, 4);
        data.alphaData = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data.alphaData[y * w + x] = (byte) (x * 255 / (w - 1));
            }
        }
        return data;
    }

    /**
     * The test pattern in 32 bits (BGR masks, the order of the DIBs of Windows) with a radial alpha : opaque in the
     * middle, transparent at the corners.
     */
    static ImageData alphaRadial(int w, int h) {
        ImageData data = direct(w, h, 32, 0xFF00, 0xFF0000, 0xFF000000, 4);
        data.alphaData = new byte[w * h];
        int r0 = Math.min(w, h) * Math.min(w, h) / 4;
        // transparent from four fifths of the half diagonal : at the corners
        int r1 = ((w - 1) * (w - 1) + (h - 1) * (h - 1)) / 5;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = 2 * x - (w - 1);
                int dy = 2 * y - (h - 1);
                int d = (dx * dx + dy * dy) / 4;
                int alpha = d <= r0 ? 255 : d >= r1 ? 0 : 255 - (d - r0) * 255 / (r1 - r0);
                data.alphaData[y * w + x] = (byte) alpha;
            }
        }
        return data;
    }

    /**
     * The source of a masked image : the test pattern, black outside the ellipse (where the mask is black).
     */
    static ImageData maskSource(int w, int h) {
        ImageData data = direct24(w, h, 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!inside(x, y, w, h, 2)) {
                    data.setPixel(x, y, 0);
                }
            }
        }
        return data;
    }

    /**
     * The mask of a masked image : white inside the ellipse (visible), black outside (transparent).
     */
    static ImageData mask(int w, int h) {
        ImageData data = blank(w, h, 1, new PaletteData(new RGB(0, 0, 0), new RGB(255, 255, 255)), 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data.setPixel(x, y, inside(x, y, w, h, 2) ? 1 : 0);
            }
        }
        return data;
    }

    /**
     * An image of {@code data}, given to {@code action}, then disposed.
     */
    static void withImage(Image image, Consumer<Image> action) {
        try {
            action.accept(image);
        } finally {
            image.dispose();
        }
    }

    /**
     * Light 8 x 8 checks : show the transparent pixels.
     */
    static void checker(GC gc, int x, int y, int w, int h) {
        gc.setBackground(SwtKit.color(SwtKit.WHITE));
        gc.fillRectangle(x, y, w, h);
        gc.setBackground(SwtKit.color(CHECKER));
        for (int cy = 0; cy < h; cy += 8) {
            for (int cx = (cy / 8) % 2 * 8; cx < w; cx += 16) {
                gc.fillRectangle(x + cx, y + cy, Math.min(8, w - cx), Math.min(8, h - cy));
            }
        }
    }

    /**
     * A tile drawing {@code data} at the top of its area, centered, over checks.
     */
    static Tile tile(String caption, Supplier<ImageData> data) {
        return new Tile(caption, (gc, w, h) -> {
            ImageData d = data.get();
            checker(gc, (w - d.width) / 2, 2, d.width, d.height);
            withImage(new Image(gc.getDevice(), d), image -> gc.drawImage(image, (w - d.width) / 2, 2));
        });
    }

    /**
     * A tile drawing an image made by {@code factory} at the top of its area, centered, over checks.
     */
    static Tile imageTile(String caption, Supplier<Image> factory) {
        return new Tile(caption, (gc, w, h) -> withImage(factory.get(), image -> {
            int x = (w - image.getBounds().width) / 2;
            checker(gc, x, 2, image.getBounds().width, image.getBounds().height);
            gc.drawImage(image, x, 2);
        }));
    }

    /**
     * An image drawn with a GC : a gradient, an oval and a text.
     */
    static Image gcDrawn(Display display) {
        Image image = new Image(display, W, H);
        GC gc = new GC(image);
        try {
            gc.setForeground(SwtKit.color(0x1E88E5));
            gc.setBackground(SwtKit.color(0xE3F2FD));
            gc.fillGradientRectangle(0, 0, W, H, false);
            gc.setBackground(SwtKit.color(0xFB8C00));
            gc.fillOval(8, 8, 40, 40);
            gc.setForeground(SwtKit.color(INK));
            gc.setFont(SwtKit.font(SWT.BOLD, 10));
            gc.drawString("GC", 54, 18, true);
        } finally {
            gc.dispose();
        }
        return image;
    }

    private static List<Tile> dataTiles(Display display) {
        List<Tile> tiles = new ArrayList<>();
        tiles.add(tile("direct 24 bit\nRGB masks", () -> direct24(W, H, 4)));
        tiles.add(tile("direct 32 bit\nBGR masks", () -> direct(W, H, 32, 0xFF00, 0xFF0000, 0xFF000000, 4)));
        tiles.add(tile("direct 16 bit\n565 masks", () -> direct(W, H, 16, 0xF800, 0x7E0, 0x1F, 4)));
        tiles.add(tile("indexed 8 bit\n256 colors", () -> indexed8(W, H, 4)));
        tiles.add(tile("indexed 4 bit\n16 colors", () -> indexed4(W, H, 4)));
        tiles.add(tile("indexed 1 bit\n2 colors", () -> indexed1(W, H, 4)));
        tiles.add(tile("alphaData\nramp", () -> alphaRamp(W, H)));
        tiles.add(tile("alphaData\nradial, 32 bit", () -> alphaRadial(W, H)));
        tiles.add(tile("global alpha\n128", () -> {
            ImageData data = direct24(W, H, 4);
            data.alpha = 128;
            return data;
        }));
        tiles.add(tile("transparent\npixel 0", () -> transparent(indexed4(W, H, 4), 0)));
        tiles.add(tile("its transparency\nmask", () -> transparent(indexed4(W, H, 4), 0).getTransparencyMask()));
        tiles.add(imageTile("Image(source,\nmask)", () -> new Image(display, maskSource(W, H), mask(W, H))));
        for (int flag : new int[] { SWT.IMAGE_COPY, SWT.IMAGE_GRAY, SWT.IMAGE_DISABLE }) {
            tiles.add(imageTile("Image(image,\n" + flagName(flag).substring("IMAGE_".length()) + ")", () -> {
                Image source = new Image(display, direct24(W, H, 4));
                try {
                    return new Image(display, source, flag);
                } finally {
                    source.dispose();
                }
            }));
        }
        tiles.add(tile("scaledTo\n(44, 28)", () -> direct24(W, H, 4).scaledTo(44, 28)));
        tiles.add(tile("drawn by a GC,\ngetImageData", () -> {
            Image image = gcDrawn(display);
            try {
                return image.getImageData(100);
            } finally {
                image.dispose();
            }
        }));
        tiles.add(tile("indexed 4 bit\nDIB round trip", () -> {
            Image image = new Image(display, indexed4(W, H, 4));
            try {
                return image.getImageData(100);
            } finally {
                image.dispose();
            }
        }));
        return tiles;
    }

    static String flagName(int flag) {
        return switch (flag) {
            case SWT.IMAGE_COPY -> "IMAGE_COPY";
            case SWT.IMAGE_GRAY -> "IMAGE_GRAY";
            case SWT.IMAGE_DISABLE -> "IMAGE_DISABLE";
            default -> String.valueOf(flag);
        };
    }

    static String transparency(int type) {
        return switch (type) {
            case SWT.TRANSPARENCY_ALPHA -> "ALPHA";
            case SWT.TRANSPARENCY_MASK -> "MASK";
            case SWT.TRANSPARENCY_PIXEL -> "PIXEL";
            default -> "NONE";
        };
    }

    /**
     * {@code depth, bytes per line, palette, transparency type} of {@code data}.
     */
    static String describe(ImageData data) {
        PaletteData p = data.palette;
        String palette = p.isDirect ? String.format("masks %X %X %X", p.redMask, p.greenMask, p.blueMask)
                : p.colors.length + " colors";
        return data.depth + " bit, " + data.bytesPerLine + " bytes per line, " + palette + ", "
                + transparency(data.getTransparencyType());
    }

    /**
     * {@code exact} when the ARGB pixels of {@code actual} are those of {@code expected}, the number of different
     * pixels otherwise.
     */
    static String compare(ImageData expected, ImageData actual) {
        if (expected.width != actual.width || expected.height != actual.height) {
            return "size " + actual.width + "x" + actual.height;
        }
        int[] a = SwtSnapshots.argb(expected);
        int[] b = SwtSnapshots.argb(actual);
        int different = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                different++;
            }
        }
        return different == 0 ? "exact" : different + " px differ";
    }

    /**
     * The mean absolute difference of the red, green and blue channels.
     */
    static double meanError(ImageData expected, ImageData actual) {
        int[] a = SwtSnapshots.argb(expected);
        int[] b = SwtSnapshots.argb(actual);
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                sum += Math.abs(((a[i] >> shift) & 0xFF) - ((b[i] >> shift) & 0xFF));
            }
        }
        return sum / (3.0 * a.length);
    }

    /**
     * {@code check} on Windows and macOS, where ImageLoader runs the codecs of SWT (in Java) ; informational on Linux,
     * where SWT decodes and encodes with GdkPixbuf (the depths, palettes and frames it returns differ).
     */
    static Check codecs(Check check) {
        return SwtMode.isLinux() && Boolean.FALSE.equals(check.ok()) ? Check.info(check.name(), check.value())
                : check;
    }

    // ------------------------------------------------------------------------------------------------ round trips

    /**
     * A variant of the round trips : the source, the format and the compression (the JPEG quality) of
     * {@code ImageLoader.save}, and the largest mean error (0 : lossless).
     */
    record Variant(String name, int format, int compression, Supplier<ImageData> source, double maxError) {
    }

    /**
     * The result of a round trip : the bytes written and the image read back, or the error.
     */
    record RoundTrip(Variant variant, ImageData source, byte[] bytes, ImageData decoded, Throwable error) {

        String fidelity() {
            return variant.maxError() == 0 ? compare(source, decoded)
                    : "mean error " + SwtChecks.num(meanError(source, decoded), 2);
        }

        boolean ok() {
            return variant.maxError() == 0 ? compare(source, decoded).equals("exact")
                    : meanError(source, decoded) <= variant.maxError();
        }
    }

    static List<Variant> variants() {
        List<Variant> v = new ArrayList<>();
        v.add(new Variant("PNG 24 bit", SWT.IMAGE_PNG, 0, () -> direct24(W, H, 4), 0));
        v.add(new Variant("PNG 32 + alpha", SWT.IMAGE_PNG, 0, () -> alphaRadial(W, H), 0));
        v.add(new Variant("PNG 8 palette", SWT.IMAGE_PNG, 0, () -> indexed8(W, H, 4), 0));
        v.add(new Variant("PNG 4 + transp.", SWT.IMAGE_PNG, 0, () -> transparent(indexed4(W, H, 4), 0), 0));
        v.add(new Variant("BMP 24 bit", SWT.IMAGE_BMP, 0, () -> direct24(W, H, 4), 0));
        v.add(new Variant("BMP 16 bit 555", SWT.IMAGE_BMP, 0, () -> direct(W, H, 16, 0x7C00, 0x3E0, 0x1F, 4), 0));
        v.add(new Variant("BMP 8 palette", SWT.IMAGE_BMP, 0, () -> indexed8(W, H, 4), 0));
        v.add(new Variant("BMP_RLE 8 bit", SWT.IMAGE_BMP_RLE, 0, () -> indexed8(W, H, 4), 0));
        v.add(new Variant("BMP_RLE 4 bit", SWT.IMAGE_BMP_RLE, 0, () -> indexed4(W, H, 4), 0));
        v.add(new Variant("GIF 8 palette", SWT.IMAGE_GIF, 0, () -> indexed8(W, H, 4), 0));
        v.add(new Variant("GIF 4 + transp.", SWT.IMAGE_GIF, 0, () -> transparent(indexed4(W, H, 4), 0), 0));
        // the white ring transparent : the mask of the icon
        v.add(new Variant("ICO 8 + mask", SWT.IMAGE_ICO, 0, () -> transparent(indexed8(W, H, 4), cubeIndex(0xFFFFFF)),
                0));
        // the ICO writer stores the pixels as they are : BGR masks (see the check of the RGB masks) ; a mask with
        // transparent pixels (Windows draws nothing of an icon whose mask is fully visible, see the checks)
        v.add(new Variant("ICO 24 + mask", SWT.IMAGE_ICO, 0, () -> {
            ImageData data = direct(W, H, 24, 0xFF, 0xFF00, 0xFF0000, 4);
            ImageData mask = mask(W, H);
            data.maskData = mask.data;
            data.maskPad = mask.scanlinePad;
            return data;
        }, 0));
        v.add(new Variant("JPEG quality 75", SWT.IMAGE_JPEG, 75, () -> direct24(W, H, 4), 12));
        v.add(new Variant("JPEG quality 20", SWT.IMAGE_JPEG, 20, () -> direct24(W, H, 4), 20));
        // TIFF : one byte of scanline pad
        v.add(new Variant("TIFF 24 bit", SWT.IMAGE_TIFF, 0, () -> direct24(W, H, 1), 0));
        v.add(new Variant("TIFF 8 palette", SWT.IMAGE_TIFF, 0, () -> indexed8(W, H, 1), 0));
        v.add(new Variant("TIFF 1 bit", SWT.IMAGE_TIFF, 0, () -> indexed1(W, H, 1), 0));
        return v;
    }

    static byte[] save(int format, int compression, ImageData... data) {
        ImageLoader loader = new ImageLoader();
        loader.data = data;
        loader.compression = compression;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        loader.save(out, format);
        return out.toByteArray();
    }

    /**
     * {@code data} with its red and blue channels swapped (24 bits, RGB masks).
     */
    static ImageData swapRedBlue(ImageData data) {
        ImageData swapped = new ImageData(data.width, data.height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                RGB c = data.palette.getRGB(data.getPixel(x, y));
                swapped.setPixel(x, y, c.blue << 16 | c.green << 8 | c.red);
            }
        }
        return swapped;
    }

    static ImageLoader load(byte[] bytes) {
        ImageLoader loader = new ImageLoader();
        loader.load(new ByteArrayInputStream(bytes));
        return loader;
    }

    static List<RoundTrip> roundTrips() {
        List<RoundTrip> trips = new ArrayList<>();
        for (Variant variant : variants()) {
            ImageData source = variant.source().get();
            try {
                byte[] bytes = save(variant.format(), variant.compression(), source);
                trips.add(new RoundTrip(variant, source, bytes, load(bytes).data[0], null));
            } catch (Throwable t) {
                trips.add(new RoundTrip(variant, source, null, null, t));
            }
        }
        return trips;
    }

    private static List<Tile> tripTiles(List<RoundTrip> trips) {
        List<Tile> tiles = new ArrayList<>();
        for (RoundTrip trip : trips) {
            if (trip.error() != null) {
                tiles.add(new Tile(trip.variant().name() + "\nerror", (gc, w, h) -> {
                    gc.setForeground(SwtKit.color(RED));
                    gc.drawLine(0, 0, w, h);
                    gc.drawLine(w, 0, 0, h);
                }));
                continue;
            }
            String fidelity = trip.variant().maxError() == 0 ? trip.fidelity()
                    : "\u00B1" + SwtChecks.num(meanError(trip.source(), trip.decoded()), 1);
            tiles.add(tile(trip.variant().name() + "\n" + trip.bytes().length + " B, " + fidelity, trip::decoded));
        }
        return tiles;
    }

    private static List<Check> tripChecks(Display display, List<RoundTrip> trips) {
        List<Check> checks = new ArrayList<>();
        for (RoundTrip trip : trips) {
            String name = trip.variant().name();
            if (trip.error() != null) {
                checks.add(codecs(Check.fail(name, SwtChecks.describe(trip.error()))));
                continue;
            }
            ImageData decoded = trip.decoded();
            String value = trip.bytes().length + " B, " + decoded.width + "x" + decoded.height + " " + decoded.depth
                    + " bit, " + trip.fidelity() + ", " + SwtChecks.sha256(decoded);
            checks.add(codecs(Check.of(name, trip.ok(), value)));
        }
        checks.add(SwtChecks.info("ICO 24 bit with RGB masks : the decoded pixels", () -> {
            ImageData source = direct24(W, H, 4);
            ImageData decoded = load(save(SWT.IMAGE_ICO, 0, source)).data[0];
            String fidelity = compare(source, decoded);
            return fidelity.equals("exact") || !compare(swapRedBlue(source), decoded).equals("exact") ? fidelity
                    : "red and blue swapped (the writer stores the pixels unconverted)";
        }));
        byte[] png = trips.getFirst().bytes();
        checks.add(SwtChecks.info("ImageLoader.load(PNG) : images, logical screen, repeatCount", () -> {
            ImageLoader loader = load(png);
            return loader.data.length + ", " + loader.logicalScreenWidth + "x" + loader.logicalScreenHeight + ", "
                    + loader.repeatCount;
        }));
        checks.add(codecs(SwtChecks.expect("new Image(display, InputStream) of the PNG : bounds, pixels",
                "0,0 88x56, exact", () -> {
                    Image image = new Image(display, new ByteArrayInputStream(png));
                    try {
                        return SwtChecks.rect(image.getBounds()) + ", " + compare(trips.getFirst().source(),
                                image.getImageData(100));
                    } finally {
                        image.dispose();
                    }
                })));
        checks.add(codecs(SwtChecks.expect("save(IMAGE_GIF) of a 24 bit image", "SWTException: Unsupported color depth",
                () -> {
                    try {
                        return save(SWT.IMAGE_GIF, 0, direct24(W, H, 4)).length + " B";
                    } catch (SWTException e) {
                        return "SWTException: " + e.getMessage();
                    }
                })));
        checks.add(codecs(SwtChecks.expect("load of a truncated PNG", "SWTException", () -> {
            try {
                return load(Arrays.copyOf(png, png.length / 2)).data.length + " images";
            } catch (SWTException e) {
                return "SWTException";
            }
        })));
        return checks;
    }

    // ------------------------------------------------------------------------------------------------ animated GIF

    /** The logical screen of the animated GIF. */
    static final int GIF_W = 96;
    static final int GIF_H = 64;

    /**
     * The animated GIF : its source frames, its bytes, the loader that read them back, and the logical screen after
     * every frame (composited).
     */
    record Gif(List<ImageData> frames, byte[] bytes, ImageLoader loader, List<ImageData> composited, Throwable error) {
    }

    /**
     * A frame of {@code w x h} at ({@code x}, {@code y}) in the 16 colors, its pixels from {@code pixel}.
     */
    static ImageData frame(int x, int y, int w, int h, int delay, int disposal, int transparentPixel,
            PixelFunction pixel) {
        ImageData data = blank(w, h, 4, colors16(), 4);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                data.setPixel(px, py, pixel.index(px, py));
            }
        }
        data.x = x;
        data.y = y;
        data.delayTime = delay;
        data.disposalMethod = disposal;
        data.transparentPixel = transparentPixel;
        return data;
    }

    @FunctionalInterface
    interface PixelFunction {
        int index(int x, int y);
    }

    /** The transparent index of the frames : the last of the 16 colors. */
    static final int KEY = 15;

    static List<ImageData> gifFrames() {
        List<ImageData> frames = new ArrayList<>();
        // the background, a frame and dots : left in place
        frames.add(frame(0, 0, GIF_W, GIF_H, 50, SWT.DM_FILL_NONE, -1, (x, y) -> x < 2 || y < 2 || x >= GIF_W - 2
                || y >= GIF_H - 2 ? 1 : x % 12 == 6 && y % 12 == 6 ? 11 : 0));
        // a red disc : left in place
        frames.add(frame(6, 16, 32, 32, 30, SWT.DM_FILL_NONE, KEY, (x, y) -> inside(x, y, 32, 32, 1) ? 2 : KEY));
        // a green square with a hole : restored to the background after it
        frames.add(frame(32, 16, 32, 32, 30, SWT.DM_FILL_BACKGROUND, KEY, (x, y) -> inside(x, y, 32, 32, 8) ? KEY
                : 5));
        // a blue diamond : restored to the previous state after it
        frames.add(frame(58, 16, 32, 32, 40, SWT.DM_FILL_PREVIOUS, KEY,
                (x, y) -> Math.abs(2 * x - 31) + Math.abs(2 * y - 31) <= 30 ? 7 : KEY));
        // a yellow bar across : unspecified
        frames.add(frame(16, 36, 64, 16, 100, SWT.DM_UNSPECIFIED, -1, (x, y) -> y < 2 || y >= 14 ? 1
                : (x / 4) % 2 == 0 ? 4 : 3));
        return frames;
    }

    static Gif gif() {
        List<ImageData> frames = gifFrames();
        try {
            ImageLoader writer = new ImageLoader();
            writer.data = frames.toArray(ImageData[]::new);
            writer.logicalScreenWidth = GIF_W;
            writer.logicalScreenHeight = GIF_H;
            writer.backgroundPixel = 0;
            writer.repeatCount = 0;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.save(out, SWT.IMAGE_GIF);
            byte[] bytes = out.toByteArray();
            ImageLoader loader = load(bytes);
            return new Gif(frames, bytes, loader, composite(loader), null);
        } catch (Throwable t) {
            return new Gif(frames, null, null, List.of(), t);
        }
    }

    /**
     * The logical screen after every frame of {@code loader} : the frames drawn in order at their position with their
     * transparency, each one disposed before the next as its disposal method says (DM_FILL_BACKGROUND : the background
     * color, DM_FILL_PREVIOUS : the screen before the frame ; DM_FILL_NONE and DM_UNSPECIFIED : left in place). Pure
     * Java, on the ARGB pixels.
     */
    static List<ImageData> composite(ImageLoader loader) {
        int w = Math.max(1, loader.logicalScreenWidth);
        int h = Math.max(1, loader.logicalScreenHeight);
        RGB rgb = loader.data[0].palette.isDirect ? new RGB(255, 255, 255)
                : loader.data[0].palette.getRGB(Math.max(0, loader.backgroundPixel));
        int background = 0xFF000000 | rgb.red << 16 | rgb.green << 8 | rgb.blue;
        int[] screen = new int[w * h];
        Arrays.fill(screen, background);
        List<ImageData> composited = new ArrayList<>();
        for (ImageData frame : loader.data) {
            int[] before = screen.clone();
            int[] pixels = SwtSnapshots.argb(frame);
            for (int y = 0; y < frame.height && frame.y + y < h; y++) {
                for (int x = 0; x < frame.width && frame.x + x < w; x++) {
                    int p = pixels[y * frame.width + x];
                    if (p >>> 24 != 0) {
                        screen[(frame.y + y) * w + frame.x + x] = p | 0xFF000000;
                    }
                }
            }
            composited.add(fromArgb(w, h, screen));
            if (frame.disposalMethod == SWT.DM_FILL_BACKGROUND) {
                for (int y = frame.y; y < Math.min(h, frame.y + frame.height); y++) {
                    Arrays.fill(screen, y * w + frame.x, y * w + Math.min(w, frame.x + frame.width), background);
                }
            } else if (frame.disposalMethod == SWT.DM_FILL_PREVIOUS) {
                screen = before;
            }
        }
        return composited;
    }

    /**
     * An opaque 24 bit image of the ARGB pixels {@code argb}.
     */
    static ImageData fromArgb(int w, int h, int[] argb) {
        ImageData data = new ImageData(w, h, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data.setPixel(x, y, argb[y * w + x] & 0xFFFFFF);
            }
        }
        return data;
    }

    static String disposal(int method) {
        return switch (method) {
            case SWT.DM_FILL_NONE -> "NONE";
            case SWT.DM_FILL_BACKGROUND -> "BACKGROUND";
            case SWT.DM_FILL_PREVIOUS -> "PREVIOUS";
            default -> "UNSPECIFIED";
        };
    }

    private static int paintGif(GC gc, Gif gif) {
        int label = 150;
        int step = 172;
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        int line = gc.getFontMetrics().getHeight();
        if (gif.error() != null) {
            gc.setForeground(SwtKit.color(RED));
            gc.drawString("GIF round trip failed: " + SwtChecks.describe(gif.error()), 0, 0, true);
            return line;
        }
        ImageData[] decoded = gif.loader().data;
        int rowHeight = GIF_H + 6 + 2 * line + 10;
        for (int row = 0; row < 2; row++) {
            int y = row * rowHeight;
            gc.setForeground(SwtKit.color(INK));
            gc.drawString(row == 0 ? "decoded frames" : "composited", 0, y + 4, true);
            gc.setForeground(SwtKit.color(MUTED));
            gc.drawString(row == 0 ? "(as stored)" : "(disposal applied)", 0, y + 4 + line, true);
            for (int i = 0; i < decoded.length; i++) {
                int x = label + i * step;
                ImageData frame = decoded[i];
                if (row == 0) {
                    checker(gc, x, y, GIF_W, GIF_H);
                    withImage(new Image(gc.getDevice(), frame), image -> gc.drawImage(image, x + frame.x,
                            y + frame.y));
                    gc.setForeground(SwtKit.color(RED));
                    gc.setLineStyle(SWT.LINE_DOT);
                    gc.drawRectangle(x + frame.x, y + frame.y, frame.width - 1, frame.height - 1);
                    gc.setLineStyle(SWT.LINE_SOLID);
                    gc.setForeground(SwtKit.color(MUTED));
                    gc.drawString(frame.x + "," + frame.y + " " + frame.width + "x" + frame.height, x, y + GIF_H + 4,
                            true);
                    gc.drawString(frame.delayTime * 10 + " ms, " + disposal(frame.disposalMethod), x,
                            y + GIF_H + 4 + line, true);
                } else {
                    ImageData screen = gif.composited().get(i);
                    withImage(new Image(gc.getDevice(), screen), image -> gc.drawImage(image, x, y));
                    gc.setForeground(SwtKit.color(MUTED));
                    gc.drawString("after frame " + i, x, y + GIF_H + 4, true);
                }
                gc.setForeground(SwtKit.color(0xB0BEC5));
                gc.drawRectangle(x - 1, y - 1, GIF_W + 1, GIF_H + 1);
            }
        }
        return 2 * rowHeight - 10;
    }

    private static List<Check> gifChecks(Gif gif) {
        List<Check> checks = new ArrayList<>();
        if (gif.error() != null) {
            checks.add(codecs(Check.fail("animated GIF round trip", SwtChecks.describe(gif.error()))));
            return checks;
        }
        ImageLoader loader = gif.loader();
        checks.add(Check.info("GIF bytes : size, SHA-256", gif.bytes().length + " B, "
                + SwtChecks.sha256(gif.bytes())));
        checks.add(codecs(SwtChecks.expect("frames, logical screen, background pixel, repeatCount",
                "5, 96x64, 0, 0", () -> loader.data.length + ", " + loader.logicalScreenWidth + "x"
                        + loader.logicalScreenHeight + ", " + loader.backgroundPixel + ", " + loader.repeatCount)));
        checks.add(codecs(SwtChecks.expect("frames : x,y size, delay (1/100 s), disposal, transparent pixel",
                describeFrames(gif.frames()), () -> describeFrames(Arrays.asList(loader.data)))));
        checks.add(codecs(SwtChecks.expect("decoded frames equal their sources (ARGB)", "exact exact exact exact exact",
                () -> {
                    List<String> results = new ArrayList<>();
                    for (int i = 0; i < gif.frames().size(); i++) {
                        results.add(compare(gif.frames().get(i), loader.data[i]));
                    }
                    return String.join(" ", results);
                })));
        checks.add(codecs(SwtChecks.expect("decoded frames : depth, palette colors", "4 16, 4 16, 4 16, 4 16, 4 16",
                () -> Arrays.stream(loader.data).map(d -> d.depth + " " + (d.palette.isDirect ? "direct"
                        : String.valueOf(d.palette.colors.length))).collect(Collectors.joining(", ")))));
        checks.add(SwtChecks.info("composited screens : SHA-256 of the last one",
                () -> SwtChecks.sha256(gif.composited().getLast())));
        checks.add(codecs(SwtChecks.expect("the last screen : green square cleared, blue diamond restored", true,
                () -> {
                    ImageData last = gif.composited().getLast();
                    int background = 0xFF000000 | COLORS_16[0];
                    // the middle of the edge of the green square, and of the diamond (both outside the yellow bar)
                    return SwtSnapshots.pixel(last, 34, 31) == background
                            && SwtSnapshots.pixel(last, 74, 18) == background
                            && SwtSnapshots.pixel(last, 22, 31) == (0xFF000000 | COLORS_16[2]);
                })));
        return checks;
    }

    static String describeFrames(List<ImageData> frames) {
        return frames.stream().map(f -> f.x + "," + f.y + " " + f.width + "x" + f.height + " " + f.delayTime + " "
                + disposal(f.disposalMethod) + " " + f.transparentPixel).collect(Collectors.joining(" | "));
    }

    // ---------------------------------------------------------------------------------------- scaling and zooms

    /** The small source of the scaled tiles : 11 x 7 pixels drawn 8 times larger. */
    static final int SMALL_W = 11;
    static final int SMALL_H = 7;
    static final int SCALE = 8;

    static final int[] INTERPOLATIONS = { SWT.NONE, SWT.LOW, SWT.DEFAULT, SWT.HIGH };

    static String interpolationName(int interpolation) {
        return switch (interpolation) {
            case SWT.NONE -> "SWT.NONE";
            case SWT.LOW -> "SWT.LOW";
            case SWT.HIGH -> "SWT.HIGH";
            default -> "SWT.DEFAULT";
        };
    }

    /**
     * Fine vertical stripes (1 pixel black, 1 pixel white) and a diagonal : aliasing when scaled down.
     */
    static ImageData stripes(int w, int h) {
        ImageData data = new ImageData(w, h, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean ink = x % 2 == 0 || Math.abs(x - y * w / h) < 3;
                data.setPixel(x, y, ink ? 0x263238 : 0xFFFFFF);
            }
        }
        return data;
    }

    /**
     * A {@code size x size} image of a zoom : a frame and checks of 4 points in {@code color}, so that the variant
     * drawn is recognizable (blue at 100 %, orange at 200 %).
     */
    static ImageData zoomData(int size, int scale, int color) {
        ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        int cell = 4 * scale;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean frame = x < scale || y < scale || x >= size - scale || y >= size - scale;
                boolean check = (x / cell + y / cell) % 2 == 0;
                data.setPixel(x, y, frame ? 0x263238 : check ? color : 0xFFFFFF);
            }
        }
        return data;
    }

    /** The 100 % and 200 % images of the providers. */
    static final int PROVIDER = 32;

    static ImageData providerData(int zoom, int color100, int color200) {
        return zoom == 200 ? zoomData(2 * PROVIDER, 2, color200) : zoom == 100 ? zoomData(PROVIDER, 1, color100)
                : null;
    }

    /**
     * Writes the 100 % and 200 % PNG files of the ImageFileNameProvider into a new temporary directory (a background
     * thread : ImageData and ImageLoader are plain Java objects).
     */
    static Path writeProviderFiles() throws IOException {
        Path directory = Files.createTempDirectory("showcase-swt-images");
        for (int zoom : new int[] { 100, 200 }) {
            byte[] png = save(SWT.IMAGE_PNG, 0, providerData(zoom, 0x43A047, 0x8E24AA));
            Files.write(directory.resolve(fileName(zoom)), png);
        }
        return directory;
    }

    static String fileName(int zoom) {
        return zoom == 200 ? "provider@2x.png" : "provider.png";
    }

    /**
     * The tiles of scaling, zooms and system images, and their checks.
     */
    record ZoomResult(ImageData image, List<Check> checks) {
    }

    /**
     * A tile drawing {@code image} (owned by the caller) at its size ({@code drawImage(image, x, y)}) and at twice its
     * size ({@code drawImage(image, x, y, width, height)} : the image of the best fitting zoom that
     * {@code swt.autoScale} allows).
     */
    static Tile zoomTile(String caption, Supplier<Image> image) {
        return new Tile(caption, (gc, w, h) -> {
            Image i = image.get();
            Point size = new Point(i.getBounds().width, i.getBounds().height);
            int x = (w - 3 * size.x - 6) / 2;
            gc.drawImage(i, x, 2);
            gc.drawImage(i, x + size.x + 6, 2, 2 * size.x, 2 * size.y);
        });
    }

    static final String DATA_PROVIDER = "ImageDataProvider\nat 32 and 64";

    static final int[] SYSTEM_IMAGES = { SWT.ICON_ERROR, SWT.ICON_INFORMATION, SWT.ICON_QUESTION, SWT.ICON_WARNING,
            SWT.ICON_WORKING };

    static String systemImageName(int id) {
        return switch (id) {
            case SWT.ICON_ERROR -> "ICON_ERROR";
            case SWT.ICON_INFORMATION -> "ICON_INFORMATION";
            case SWT.ICON_QUESTION -> "ICON_QUESTION";
            case SWT.ICON_WARNING -> "ICON_WARNING";
            default -> "ICON_WORKING";
        };
    }

    private static ZoomResult zoomTiles(Display display, Path directory) {
        String file100 = directory.resolve(fileName(100)).toString();
        String file200 = directory.resolve(fileName(200)).toString();
        ImageFileNameProvider files = zoom -> zoom == 100 ? file100 : zoom == 200 ? file200 : null;
        ImageDataProvider data = zoom -> providerData(zoom, 0x1E88E5, 0xFB8C00);
        ImageGcDrawer drawer = (gc, width, height) -> {
            gc.setAntialias(SWT.ON);
            gc.setBackground(SwtKit.color(0xFFF8E1));
            gc.fillRectangle(0, 0, width, height);
            gc.setForeground(SwtKit.color(0x6D4C41));
            gc.setLineWidth(2);
            gc.drawOval(3, 3, width - 7, height - 7);
            gc.drawLine(width / 2, 6, width / 2, height / 2);
            gc.drawLine(width / 2, height / 2, width - 9, height / 2);
        };
        Image fileImage = new Image(display, files);
        Image dataImage = new Image(display, data);
        Image gcImage = new Image(display, drawer, PROVIDER, PROVIDER);
        Image small = new Image(display, direct24(SMALL_W, SMALL_H, 4));
        Image fine = new Image(display, stripes(W, H));
        try {
            List<Tile> tiles = new ArrayList<>();
            for (int interpolation : INTERPOLATIONS) {
                tiles.add(new Tile("drawImage x " + SCALE + "\n" + interpolationName(interpolation), (gc, w, h) -> {
                    gc.setInterpolation(interpolation);
                    gc.drawImage(small, 0, 0, SMALL_W, SMALL_H, (w - SCALE * SMALL_W) / 2, 2, SCALE * SMALL_W,
                            SCALE * SMALL_H);
                }));
            }
            for (int interpolation : new int[] { SWT.NONE, SWT.HIGH }) {
                tiles.add(new Tile("drawImage x 1/2\n" + interpolationName(interpolation), (gc, w, h) -> {
                    gc.setInterpolation(interpolation);
                    gc.drawImage(fine, 0, 0, W, H, (w - W / 2) / 2, 2, W / 2, H / 2);
                }));
            }
            tiles.add(zoomTile(DATA_PROVIDER, () -> dataImage));
            tiles.add(zoomTile("file name provider\nat 32 and 64", () -> fileImage));
            tiles.add(zoomTile("ImageGcDrawer\nat 32 and 64", () -> gcImage));
            for (int id : SYSTEM_IMAGES) {
                tiles.add(zoomTile(systemImageName(id).substring("ICON_".length()) + "\nat 32 and 64",
                        () -> display.getSystemImage(id)));
            }
            SwtTiles zoomTiles = new SwtTiles(7, 142, TILE_HEIGHT, tiles);
            ImageData image = zoomTiles.paint();
            List<Check> checks = zoomChecks(display, zoomTiles, image, dataImage, fileImage, gcImage, small);
            return new ZoomResult(image, checks);
        } finally {
            fileImage.dispose();
            dataImage.dispose();
            gcImage.dispose();
            small.dispose();
            fine.dispose();
        }
    }

    private static List<Check> zoomChecks(Display display, SwtTiles tiles, ImageData image, Image dataImage,
            Image fileImage, Image gcImage, Image small) {
        List<Check> checks = new ArrayList<>();
        ImageData smallData = direct24(SMALL_W, SMALL_H, 4);
        int areaWidth = tiles.areaWidth();
        int left = (areaWidth - SCALE * SMALL_W) / 2;
        // the middle of the block of the source pixel (5, 3) : that pixel, whatever the interpolation
        checks.add(SwtChecks.expect("drawImage x 8, SWT.NONE : the middle of a block is its source pixel",
                SwtChecks.argb(SwtSnapshots.pixel(smallData, 5, 3)),
                () -> tiles.probe(image, "drawImage x " + SCALE + "\nSWT.NONE", left + 5 * SCALE + SCALE / 2,
                        2 + 3 * SCALE + SCALE / 2)));
        checks.add(SwtChecks.expect("drawImage x 8, SWT.NONE : the edge of a block is still its source pixel",
                SwtChecks.argb(SwtSnapshots.pixel(smallData, 5, 3)),
                () -> tiles.probe(image, "drawImage x " + SCALE + "\nSWT.NONE", left + 5 * SCALE + SCALE - 1,
                        2 + 3 * SCALE + SCALE / 2)));
        checks.add(SwtChecks.info("drawImage x 8 : pixel at the edge of a block (NONE, LOW, DEFAULT, HIGH)",
                () -> Arrays.stream(INTERPOLATIONS).mapToObj(i -> tiles.probe(image, "drawImage x " + SCALE + "\n"
                        + interpolationName(i), left + 5 * SCALE + SCALE - 1, 2 + 3 * SCALE + SCALE / 2))
                        .collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("drawImage x 8 : HIGH blends the blocks, NONE does not", true, () -> {
            String none = tiles.probe(image, "drawImage x " + SCALE + "\nSWT.NONE", left + 5 * SCALE + SCALE - 1,
                    2 + 3 * SCALE + SCALE / 2);
            String high = tiles.probe(image, "drawImage x " + SCALE + "\nSWT.HIGH", left + 5 * SCALE + SCALE - 1,
                    2 + 3 * SCALE + SCALE / 2);
            return !none.equals(high);
        }));
        checks.add(SwtChecks.info("drawImage x 1/2 of 1 pixel stripes : pixel (10, 10) with NONE and HIGH",
                () -> tiles.probe(image, "drawImage x 1/2\nSWT.NONE", (areaWidth - W / 2) / 2 + 10, 12) + " "
                        + tiles.probe(image, "drawImage x 1/2\nSWT.HIGH", (areaWidth - W / 2) / 2 + 10, 12)));
        checks.add(SwtChecks.expect("Image from ImageData : getImageData(100) and (200) sizes", "11x7 22x14",
                () -> size(small.getImageData(100)) + " " + size(small.getImageData(200))));
        checks.add(SwtChecks.expect("ImageDataProvider : getImageData(100) and (200) are the provider's", "exact exact",
                () -> compare(providerData(100, 0x1E88E5, 0xFB8C00), dataImage.getImageData(100)) + " "
                        + compare(providerData(200, 0x1E88E5, 0xFB8C00), dataImage.getImageData(200))));
        checks.add(SwtChecks.info("ImageDataProvider : getImageData(150) (the provider has none) : size",
                () -> size(dataImage.getImageData(150))));
        checks.add(SwtChecks.expect("ImageFileNameProvider : getImageData(100) and (200) are the files", "exact exact",
                () -> compare(providerData(100, 0x43A047, 0x8E24AA), fileImage.getImageData(100)) + " "
                        + compare(providerData(200, 0x43A047, 0x8E24AA), fileImage.getImageData(200))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "ImageFileNameProvider (GDI+) : depth of getImageData(100)", 24,
                () -> fileImage.getImageData(100).depth)));
        checks.add(SwtChecks.expect("ImageGcDrawer : getImageData(100) and (200) sizes", "32x32 64x64",
                () -> size(gcImage.getImageData(100)) + " " + size(gcImage.getImageData(200))));
        checks.add(SwtChecks.info("ImageGcDrawer : SHA-256 at 100 % and 200 %",
                () -> SwtChecks.sha256(gcImage.getImageData(100)) + " " + SwtChecks.sha256(gcImage.getImageData(200))));
        int zoomLeft = (areaWidth - 3 * PROVIDER - 6) / 2;
        // the zoom of the best fitting image follows swt.autoScale : 100 % when the tools pin it to 100
        checks.add(SwtChecks.info("drawImage(image, x, y, 64, 64) of the ImageDataProvider : the image drawn", () -> {
            String probe = tiles.probe(image, DATA_PROVIDER, zoomLeft + PROVIDER + 6 + 2 + 2, 2 + 2 + 2);
            return probe.equals(SwtChecks.argb(0xFFFB8C00)) ? "200 % (orange)"
                    : probe.equals(SwtChecks.argb(0xFF1E88E5)) ? "100 % (blue), scaled" : probe;
        }));
        checks.add(SwtChecks.expect("drawImage(image, x, y) draws the 100 % image (blue)", SwtChecks.argb(0xFF1E88E5),
                () -> tiles.probe(image, DATA_PROVIDER, zoomLeft + 1 + 1, 2 + 1 + 1)));
        checks.add(SwtChecks.expect("getSystemImage returns a shared image", true,
                () -> display.getSystemImage(SWT.ICON_ERROR) == display.getSystemImage(SWT.ICON_ERROR)));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect("ICON_WORKING is ICON_INFORMATION", true,
                () -> display.getSystemImage(SWT.ICON_WORKING) == display.getSystemImage(SWT.ICON_INFORMATION))));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "system images : getImageData(100) and (200) sizes", "32x32 64x64", () -> Arrays.stream(SYSTEM_IMAGES)
                        .mapToObj(id -> size(display.getSystemImage(id).getImageData(100)) + " "
                                + size(display.getSystemImage(id).getImageData(200)))
                        .distinct().collect(Collectors.joining(", ")))));
        for (int id : SYSTEM_IMAGES) {
            checks.add(SwtChecks.info(systemImageName(id) + " : bounds, depth, transparency, SHA-256", () -> {
                Image system = display.getSystemImage(id);
                ImageData d = system.getImageData(100);
                return SwtChecks.rect(system.getBounds()) + ", " + d.depth + " bit, "
                        + transparency(d.getTransparencyType()) + ", " + SwtChecks.sha256(d);
            }));
        }
        return checks;
    }

    static String size(ImageData data) {
        return data.width + "x" + data.height;
    }

    // -------------------------------------------------------------------------------------------- transparency

    /** Geometry of the transparency strip : the label column, the cells and the rows. */
    static final int LABEL = 220;
    static final int CELL = 120;
    static final int ROW = 52;
    static final int[] BACKGROUNDS = { 0xFFFFFF, 0xCFD8DC, 0x263238, 0xC62828, 0x1565C0, -1 };
    static final String[] BACKGROUND_NAMES = { "white", "light gray", "dark", "red", "blue", "checks" };
    static final String[] STRIP_ROWS = { "alphaData (radial)", "global alpha 128", "transparentPixel",
            "Image(source, mask)", "GC.setAlpha(128), opaque" };

    /**
     * The top left corner of the image of the cell ({@code row}, {@code column}) of the strip.
     */
    static Point stripOrigin(int row, int column) {
        return new Point(LABEL + column * CELL + (CELL - SW) / 2, row * ROW + (ROW - SH) / 2);
    }

    private static int paintStrip(GC gc) {
        Display display = UiStages.display();
        ImageData global = direct24(SW, SH, 4);
        global.alpha = 128;
        List<Supplier<Image>> images = List.of(() -> new Image(display, alphaRadial(SW, SH)),
                () -> new Image(display, global), () -> new Image(display, transparent(indexed4(SW, SH, 4), 0)),
                () -> new Image(display, maskSource(SW, SH), mask(SW, SH)),
                () -> new Image(display, direct24(SW, SH, 4)));
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        int line = gc.getFontMetrics().getHeight();
        for (int row = 0; row < STRIP_ROWS.length; row++) {
            gc.setForeground(SwtKit.color(INK));
            gc.drawString(STRIP_ROWS[row], 0, row * ROW + (ROW - line) / 2, true);
            for (int column = 0; column < BACKGROUNDS.length; column++) {
                int x = LABEL + column * CELL + 2;
                int y = row * ROW + 2;
                if (BACKGROUNDS[column] < 0) {
                    checker(gc, x, y, CELL - 4, ROW - 4);
                } else {
                    gc.setBackground(SwtKit.color(BACKGROUNDS[column]));
                    gc.fillRectangle(x, y, CELL - 4, ROW - 4);
                }
                Point origin = stripOrigin(row, column);
                boolean alpha = row == STRIP_ROWS.length - 1;
                withImage(images.get(row).get(), image -> {
                    if (alpha) {
                        // an advanced GC from here on (GDI+ on Windows) : the last row
                        gc.setAlpha(128);
                    }
                    gc.drawImage(image, origin.x, origin.y);
                    gc.setAlpha(255);
                });
            }
        }
        int y = STRIP_ROWS.length * ROW + 2;
        gc.setForeground(SwtKit.color(MUTED));
        for (int column = 0; column < BACKGROUNDS.length; column++) {
            String name = BACKGROUND_NAMES[column];
            gc.drawString(name, LABEL + column * CELL + (CELL - gc.stringExtent(name).x) / 2, y, true);
        }
        return y + line;
    }

    /**
     * {@code #AARRGGBB} of the strip at the point ({@code x}, {@code y}) of the image of the cell ({@code row},
     * {@code column}).
     */
    static String stripProbe(ImageData strip, int row, int column, int x, int y) {
        Point origin = stripOrigin(row, column);
        return SwtChecks.argb(SwtSnapshots.pixel(strip, origin.x + x, origin.y + y));
    }

    // -------------------------------------------------------------------------------------- image data checks

    private static List<Check> dataChecks(Display display, SwtTiles tiles, ImageData tilesImage, ImageData strip) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("direct 24", "24 bit, 264 bytes per line, masks FF0000 FF00 FF, NONE",
                () -> describe(direct24(W, H, 4))));
        checks.add(SwtChecks.expect("direct 32", "32 bit, 352 bytes per line, masks FF00 FF0000 FF000000, NONE",
                () -> describe(direct(W, H, 32, 0xFF00, 0xFF0000, 0xFF000000, 4))));
        checks.add(SwtChecks.expect("direct 16", "16 bit, 176 bytes per line, masks F800 7E0 1F, NONE",
                () -> describe(direct(W, H, 16, 0xF800, 0x7E0, 0x1F, 4))));
        checks.add(SwtChecks.expect("indexed 8", "8 bit, 88 bytes per line, 256 colors, NONE",
                () -> describe(indexed8(W, H, 4))));
        checks.add(SwtChecks.expect("indexed 4 (scanline pad 1)", "4 bit, 44 bytes per line, 16 colors, NONE",
                () -> describe(indexed4(W, H, 1))));
        checks.add(SwtChecks.expect("indexed 1", "1 bit, 12 bytes per line, 2 colors, NONE",
                () -> describe(indexed1(W, H, 4))));
        checks.add(SwtChecks.expect("alphaData ramp : type, alpha at x = 0, 44, 87", "ALPHA, 0 128 255", () -> {
            ImageData data = alphaRamp(W, H);
            return transparency(data.getTransparencyType()) + ", " + data.getAlpha(0, 0) + " " + data.getAlpha(44, 0)
                    + " " + data.getAlpha(W - 1, 0);
        }));
        checks.add(SwtChecks.expect("transparentPixel : type, mask depth, mask at a transparent / opaque pixel",
                "PIXEL, 1, 0 1", () -> {
                    ImageData data = transparent(indexed4(W, H, 4), 0);
                    ImageData mask = data.getTransparencyMask();
                    return transparency(data.getTransparencyType()) + ", " + mask.depth + ", " + mask.getPixel(0, 0)
                            + " " + mask.getPixel(W / 2, H / 2);
                }));
        checks.add(SwtChecks.expect("indexed 8 : getRGB(getPixel(rgb)) is rgb for every color", true, () -> {
            PaletteData palette = cube();
            return Arrays.stream(palette.getRGBs()).allMatch(c -> palette.getRGB(palette.getPixel(c)).equals(c));
        }));
        checks.add(SwtChecks.expect("direct 16 (565) : getRGB of the pixel of (255, 128, 64)", "RGB {248, 128, 64}",
                () -> {
                    PaletteData palette = new PaletteData(0xF800, 0x7E0, 0x1F);
                    return palette.getRGB(palette.getPixel(new RGB(255, 128, 64))).toString();
                }));
        checks.add(SwtChecks.info("pixels (SHA-256) : direct 24, indexed 8, indexed 4, indexed 1, alpha radial",
                () -> Stream.of(direct24(W, H, 4), indexed8(W, H, 4), indexed4(W, H, 4), indexed1(W, H, 4),
                        alphaRadial(W, H)).map(SwtChecks::sha256).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("scaledTo(44, 28) : size, its pixel (10, 7) is the pixel (20, 14)", "44x28, true",
                () -> {
                    ImageData source = direct24(W, H, 4);
                    ImageData scaled = source.scaledTo(44, 28);
                    return size(scaled) + ", " + (scaled.getPixel(10, 7) == source.getPixel(20, 14));
                }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "DIB round trip : Image(data).getImageData(100) of 24, 32, 16 (555), 8, 4, 1 bit",
                "exact exact exact exact exact exact", () -> Stream.of(direct24(W, H, 4),
                        direct(W, H, 32, 0xFF00, 0xFF0000, 0xFF000000, 4), direct(W, H, 16, 0x7C00, 0x3E0, 0x1F, 4),
                        indexed8(W, H, 4), indexed4(W, H, 4), indexed1(W, H, 4)).map(data -> {
                            Image image = new Image(display, data);
                            try {
                                return compare(data, image.getImageData(100));
                            } finally {
                                image.dispose();
                            }
                        }).collect(Collectors.joining(" ")))));
        checks.add(SwtChecks.info("DIB round trip : depth of getImageData(100) of 24, 32, 16, 8, 4, 1 bit",
                () -> Stream.of(direct24(W, H, 4), direct(W, H, 32, 0xFF00, 0xFF0000, 0xFF000000, 4),
                        direct(W, H, 16, 0xF800, 0x7E0, 0x1F, 4), indexed8(W, H, 4), indexed4(W, H, 4),
                        indexed1(W, H, 4)).map(data -> {
                            Image image = new Image(display, data);
                            try {
                                return String.valueOf(image.getImageData(100).depth);
                            } finally {
                                image.dispose();
                            }
                        }).collect(Collectors.joining(" "))));
        // Windows : the DIBs of 16 bits are 555, a 565 image loses the low bit of green
        checks.add(SwtChecks.info("DIB round trip of 16 bit 565 : masks, pixels", () -> {
            ImageData data = direct(W, H, 16, 0xF800, 0x7E0, 0x1F, 4);
            Image image = new Image(display, data);
            try {
                ImageData back = image.getImageData(100);
                return String.format("masks %X %X %X, ", back.palette.redMask, back.palette.greenMask,
                        back.palette.blueMask) + compare(data, back);
            } finally {
                image.dispose();
            }
        }));
        checks.add(SwtChecks.info("alphaData radial : getImageData(100) of the Image", () -> {
            ImageData data = alphaRadial(W, H);
            Image image = new Image(display, data);
            try {
                ImageData back = image.getImageData(100);
                return transparency(back.getTransparencyType()) + ", " + compare(data, back);
            } finally {
                image.dispose();
            }
        }));
        checks.add(SwtChecks.info("Image(source, mask) with a fully visible mask : the middle pixel drawn", () -> {
            ImageData white = mask(W, H);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    white.setPixel(x, y, 1);
                }
            }
            ImageData source = direct24(W, H, 4);
            Image image = new Image(display, source, white);
            try {
                ImageData drawn = SwtSnapshots.offscreen(W, H, gc -> gc.drawImage(image, 0, 0));
                int pixel = SwtSnapshots.pixel(drawn, W / 2 - 8, H / 2) & 0xFFFFFF;
                return pixel == patternRgb(W / 2 - 8, H / 2, W, H) ? "the source pixel"
                        : pixel == 0xFFFFFF ? "nothing drawn (DrawIconEx on Windows)" : SwtChecks.argb(pixel);
            } finally {
                image.dispose();
            }
        }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "Image(source, mask) : transparency type of getImageData(100)", "MASK", () -> {
                    Image image = new Image(display, maskSource(W, H), mask(W, H));
                    try {
                        return transparency(image.getImageData(100).getTransparencyType());
                    } finally {
                        image.dispose();
                    }
                })));
        checks.add(SwtChecks.expect("Image(image, IMAGE_COPY) : the same pixels", "exact", () -> {
            ImageData data = direct24(W, H, 4);
            Image source = new Image(display, data);
            Image copy = new Image(display, source, SWT.IMAGE_COPY);
            try {
                return compare(data, copy.getImageData(100));
            } finally {
                copy.dispose();
                source.dispose();
            }
        }));
        checks.add(SwtChecks.expect("Image(image, IMAGE_GRAY) : every pixel is gray", true, () -> {
            Image source = new Image(display, direct24(W, H, 4));
            Image gray = new Image(display, source, SWT.IMAGE_GRAY);
            try {
                return Arrays.stream(SwtSnapshots.argb(gray.getImageData(100))).allMatch(p -> ((p >> 16) & 0xFF) == (
                        (p >> 8) & 0xFF) && ((p >> 8) & 0xFF) == (p & 0xFF));
            } finally {
                gray.dispose();
                source.dispose();
            }
        }));
        checks.add(SwtChecks.info("Image(image, IMAGE_DISABLE) : SHA-256", () -> {
            Image source = new Image(display, direct24(W, H, 4));
            Image disabled = new Image(display, source, SWT.IMAGE_DISABLE);
            try {
                return SwtChecks.sha256(disabled.getImageData(100));
            } finally {
                disabled.dispose();
                source.dispose();
            }
        }));
        checks.add(SwtChecks.info("GC drawn image : SHA-256 of getImageData(100)", () -> {
            Image image = gcDrawn(display);
            try {
                return SwtChecks.sha256(image.getImageData(100));
            } finally {
                image.dispose();
            }
        }));
        // the transparency strip : the backgrounds show through the transparent pixels
        checks.add(SwtChecks.expect("strip : alpha 0 (a corner of the radial alpha) shows the dark background",
                SwtChecks.argb(0xFF263238), () -> stripProbe(strip, 0, 2, 0, 0)));
        checks.add(SwtChecks.expect("strip : alpha 255 (the middle of the radial alpha) is the source pixel",
                SwtChecks.argb(0xFF000000 | patternRgb(SW / 2 - 8, SH / 2, SW, SH)),
                () -> stripProbe(strip, 0, 2, SW / 2 - 8, SH / 2)));
        checks.add(SwtChecks.info("strip : global alpha 128 over red, light gray (the middle)",
                () -> stripProbe(strip, 1, 3, SW / 2 - 8, SH / 2) + " "
                        + stripProbe(strip, 1, 1, SW / 2 - 8, SH / 2)));
        checks.add(SwtChecks.expect("strip : the transparent pixel shows the red background",
                SwtChecks.argb(0xFFC62828), () -> stripProbe(strip, 2, 3, 0, 0)));
        checks.add(SwtChecks.expect("strip : outside the mask shows the blue background", SwtChecks.argb(0xFF1565C0),
                () -> stripProbe(strip, 3, 4, 0, 0)));
        checks.add(SwtChecks.expect("strip : inside the mask is the source pixel",
                SwtChecks.argb(0xFF000000 | patternRgb(SW / 2 - 8, SH / 2, SW, SH)),
                () -> stripProbe(strip, 3, 4, SW / 2 - 8, SH / 2)));
        checks.add(SwtChecks.info("strip : GC.setAlpha(128) over dark, white (the middle)",
                () -> stripProbe(strip, 4, 2, SW / 2 - 8, SH / 2) + " " + stripProbe(strip, 4, 0, SW / 2 - 8, SH / 2)));
        checks.add(SwtChecks.info("tiles : SHA-256 of the ImageData tiles", () -> SwtChecks.sha256(tilesImage)));
        return checks;
    }
}
