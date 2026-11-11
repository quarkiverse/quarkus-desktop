package io.quarkiverse.desktop.packaging.deployment;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * The icon of the application at every size and in every format that the packages need, from one image : PNG files,
 * a Windows icon ({@code .ico}) and a macOS icon ({@code .icns}).
 * <p>
 * The images are scaled here, on arrays of pixels : {@code ImageIO} reads and writes the PNG files without the AWT
 * toolkit, which a {@code Graphics2D} would start in the JVM of the build (a Dock icon on macOS).
 */
public final class IconSet {

    /**
     * The sizes of the images of a Windows icon. The largest one is stored as a PNG file, the others as bitmaps.
     */
    static final int[] ICO_SIZES = { 16, 24, 32, 48, 64, 128, 256 };

    /**
     * The sizes of the icons of a Linux icon theme ({@code hicolor}).
     */
    public static final int[] LINUX_SIZES = { 16, 24, 32, 48, 64, 128, 256, 512 };

    private static final int GENERIC_SIZE = 1024;

    /**
     * The source image : square, non-premultiplied ARGB.
     */
    private final int[] pixels;

    private final int size;

    private final Map<Integer, byte[]> pngs = new HashMap<>();

    private IconSet(int[] pixels, int size) {
        this.pixels = pixels;
        this.size = size;
    }

    /**
     * The icons of an image file. An image that is not square is centered in a transparent square.
     */
    public static IconSet of(Path image) throws IOException {
        ImageIO.setUseCache(false);
        BufferedImage read = ImageIO.read(image.toFile());
        if (read == null) {
            throw new PackagingException(image + " is not an image that the JDK reads : the icon of the application is a"
                    + " PNG file");
        }
        int width = read.getWidth();
        int height = read.getHeight();
        int size = Math.max(width, height);
        int[] source = read.getRGB(0, 0, width, height, null, 0, width);
        if (width == height) {
            return new IconSet(source, size);
        }
        int[] square = new int[size * size];
        int left = (size - width) / 2;
        int top = (size - height) / 2;
        for (int y = 0; y < height; y++) {
            System.arraycopy(source, y * width, square, (y + top) * size + left, width);
        }
        return new IconSet(square, size);
    }

    /**
     * The icons of an application without icon : a window on a blue rounded square.
     */
    public static IconSet generic() {
        int n = GENERIC_SIZE;
        int[] pixels = new int[n * n];
        double center = n / 2.0;
        for (int y = 0; y < n; y++) {
            double t = y / (double) (n - 1);
            double red = 0x4f + (0x2a - 0x4f) * t;
            double green = 0x9c + (0x5b - 0x9c) * t;
            double blue = 0xf5 + (0xd7 - 0xf5) * t;
            for (int x = 0; x < n; x++) {
                double px = x + 0.5 - center;
                double py = y + 0.5 - center;
                double background = coverage(roundedRectangle(px, py, n * 0.44, n * 0.44, n * 0.2));
                if (background <= 0) {
                    continue;
                }
                // a window : a frame with a title bar
                double frame = coverage(roundedRectangle(px, py, n * 0.27, n * 0.21, n * 0.045));
                double inside = coverage(roundedRectangle(px, py - n * 0.04, n * 0.235, n * 0.135, n * 0.02));
                double white = Math.max(0, frame - inside) * 0.96;
                int r = (int) Math.round(red + (255 - red) * white);
                int g = (int) Math.round(green + (255 - green) * white);
                int b = (int) Math.round(blue + (255 - blue) * white);
                pixels[y * n + x] = (int) Math.round(background * 255) << 24 | r << 16 | g << 8 | b;
            }
        }
        return new IconSet(pixels, n);
    }

    /**
     * The signed distance of a point to a rounded rectangle centered on the origin.
     */
    private static double roundedRectangle(double x, double y, double halfWidth, double halfHeight, double radius) {
        double qx = Math.abs(x) - (halfWidth - radius);
        double qy = Math.abs(y) - (halfHeight - radius);
        double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
        return outside + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /**
     * How much of a pixel a shape covers, from the signed distance of its center to the edge of the shape.
     */
    private static double coverage(double distance) {
        return Math.max(0, Math.min(1, 0.5 - distance));
    }

    /**
     * The size of the source image.
     */
    public int sourceSize() {
        return size;
    }

    /**
     * The icon as a PNG file of the given size.
     */
    public byte[] png(int target) {
        return pngs.computeIfAbsent(target, wanted -> encode(scale(pixels, size, wanted), wanted));
    }

    /**
     * Writes the icon as a PNG file of the given size.
     */
    public Path writePng(Path file, int target) throws IOException {
        return FileTrees.write(file, png(target));
    }

    private static byte[] encode(int[] argb, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, argb, 0, size);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.setUseCache(false);
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------------------------------------------- scaling

    /**
     * Scales a square image : the average of the covered pixels when it gets smaller, a linear interpolation when it
     * gets larger, on premultiplied colors (the color of the transparent pixels does not bleed).
     */
    static int[] scale(int[] source, int from, int to) {
        if (from == to) {
            return source;
        }
        float[] channels = new float[from * from * 4];
        for (int i = 0; i < from * from; i++) {
            int argb = source[i];
            float alpha = (argb >>> 24) / 255f;
            channels[i * 4] = alpha;
            channels[i * 4 + 1] = ((argb >> 16) & 0xff) * alpha;
            channels[i * 4 + 2] = ((argb >> 8) & 0xff) * alpha;
            channels[i * 4 + 3] = (argb & 0xff) * alpha;
        }
        // the rows, then the columns (as the rows of the transposed image)
        float[] rows = scaleRows(channels, from, from, to);
        float[] scaled = transpose(scaleRows(transpose(rows, to, from), from, to, to), to, to);
        int[] target = new int[to * to];
        for (int i = 0; i < to * to; i++) {
            float alpha = Math.min(1f, Math.max(0f, scaled[i * 4]));
            if (alpha <= 0f) {
                continue;
            }
            int a = Math.round(alpha * 255);
            int r = clamp(Math.round(scaled[i * 4 + 1] / alpha));
            int g = clamp(Math.round(scaled[i * 4 + 2] / alpha));
            int b = clamp(Math.round(scaled[i * 4 + 3] / alpha));
            target[i] = a << 24 | r << 16 | g << 8 | b;
        }
        return target;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * Scales the rows of an image of four channels to a new width.
     */
    private static float[] scaleRows(float[] source, int width, int height, int newWidth) {
        float[] target = new float[newWidth * height * 4];
        double ratio = (double) width / newWidth;
        for (int x = 0; x < newWidth; x++) {
            int first;
            int last;
            double[] weights;
            if (ratio >= 1) {
                double start = x * ratio;
                double end = (x + 1) * ratio;
                first = (int) Math.floor(start);
                last = Math.min(width - 1, (int) Math.ceil(end) - 1);
                weights = new double[last - first + 1];
                for (int i = first; i <= last; i++) {
                    weights[i - first] = (Math.min(end, i + 1) - Math.max(start, i)) / ratio;
                }
            } else {
                double position = (x + 0.5) * ratio - 0.5;
                int floor = (int) Math.floor(position);
                double fraction = position - floor;
                first = Math.max(0, floor);
                last = Math.min(width - 1, floor + 1);
                weights = first == last ? new double[] { 1 } : new double[] { 1 - fraction, fraction };
            }
            for (int y = 0; y < height; y++) {
                for (int channel = 0; channel < 4; channel++) {
                    double sum = 0;
                    for (int i = first; i <= last; i++) {
                        sum += source[(y * width + i) * 4 + channel] * weights[i - first];
                    }
                    target[(y * newWidth + x) * 4 + channel] = (float) sum;
                }
            }
        }
        return target;
    }

    private static float[] transpose(float[] source, int width, int height) {
        float[] target = new float[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                System.arraycopy(source, (y * width + x) * 4, target, (x * height + y) * 4, 4);
            }
        }
        return target;
    }

    // -------------------------------------------------------------------------------------------------- Windows icon

    /**
     * An image of a Windows icon : a bitmap, or a PNG file for the size of 256 pixels.
     *
     * @param size the width and height in pixels
     * @param data the image, as the {@code .ico} files and the icon resources of the executables store it
     */
    public record IcoImage(int size, byte[] data) {
    }

    /**
     * The images of the Windows icon, from the smallest one.
     */
    public List<IcoImage> icoImages() {
        List<IcoImage> images = new ArrayList<>();
        for (int icoSize : ICO_SIZES) {
            images.add(new IcoImage(icoSize, icoSize >= 256 ? png(icoSize) : bitmap(scale(pixels, size, icoSize), icoSize)));
        }
        return images;
    }

    /**
     * The icon as a Windows icon file ({@code .ico}).
     */
    public byte[] ico() {
        List<IcoImage> images = icoImages();
        int offset = 6 + 16 * images.size();
        int length = offset;
        for (IcoImage image : images) {
            length += image.data().length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        // ICONDIR : reserved, type (1 : icon), count
        buffer.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        for (IcoImage image : images) {
            // ICONDIRENTRY : width and height (0 : 256), colors, reserved, planes, bits per pixel, size, offset
            buffer.put((byte) (image.size() >= 256 ? 0 : image.size())).put((byte) (image.size() >= 256 ? 0 : image.size()));
            buffer.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            buffer.putInt(image.data().length).putInt(offset);
            offset += image.data().length;
        }
        for (IcoImage image : images) {
            buffer.put(image.data());
        }
        return buffer.array();
    }

    /**
     * An image of a Windows icon as a device independent bitmap : the header, the pixels from the bottom row, and the
     * transparency mask of one bit per pixel that the 32-bit images still carry.
     */
    private static byte[] bitmap(int[] argb, int size) {
        int maskRow = ((size + 31) / 32) * 4;
        ByteBuffer buffer = ByteBuffer.allocate(40 + size * size * 4 + maskRow * size).order(ByteOrder.LITTLE_ENDIAN);
        // BITMAPINFOHEADER : the height counts the pixels and the mask
        buffer.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32);
        buffer.putInt(0).putInt(size * size * 4 + maskRow * size).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                // the bytes of a little endian ARGB integer are blue, green, red, alpha
                buffer.putInt(argb[y * size + x]);
            }
        }
        for (int y = size - 1; y >= 0; y--) {
            byte[] row = new byte[maskRow];
            for (int x = 0; x < size; x++) {
                if (argb[y * size + x] >>> 24 == 0) {
                    row[x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
            buffer.put(row);
        }
        return buffer.array();
    }

    // ---------------------------------------------------------------------------------------------------- macOS icon

    /**
     * The types of the images of a macOS icon and their sizes in pixels : the PNG types, at both scales (ic11 is 16
     * points at scale 2, ic10 is 512 points at scale 2).
     */
    private static final Object[][] ICNS_TYPES = { { "icp4", 16 }, { "icp5", 32 }, { "ic07", 128 }, { "ic08", 256 },
            { "ic09", 512 }, { "ic10", 1024 }, { "ic11", 32 }, { "ic12", 64 }, { "ic13", 256 }, { "ic14", 512 } };

    /**
     * The icon as a macOS icon file ({@code .icns}).
     */
    public byte[] icns() {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (Object[] type : ICNS_TYPES) {
            byte[] png = png((Integer) type[1]);
            entries.writeBytes(((String) type[0]).getBytes(StandardCharsets.US_ASCII));
            entries.writeBytes(ByteBuffer.allocate(4).putInt(png.length + 8).array());
            entries.writeBytes(png);
        }
        ByteBuffer buffer = ByteBuffer.allocate(8 + entries.size());
        buffer.put("icns".getBytes(StandardCharsets.US_ASCII)).putInt(8 + entries.size()).put(entries.toByteArray());
        return buffer.array();
    }

    /**
     * The icon of the application : the one of the configuration, the one of the project, or the generic one.
     *
     * @param configured {@code quarkus.desktop.packaging.icon}, relative to the project directory
     */
    static IconSet load(java.util.Optional<String> configured, Path projectDirectory) throws IOException {
        if (configured.isPresent()) {
            Path icon = projectDirectory.resolve(configured.get());
            if (!Files.isRegularFile(icon)) {
                throw new PackagingException("quarkus.desktop.packaging.icon : " + icon + " not found");
            }
            return of(icon);
        }
        Path icon = projectDirectory.resolve("src/main/packaging/icon.png");
        return Files.isRegularFile(icon) ? of(icon) : generic();
    }
}
