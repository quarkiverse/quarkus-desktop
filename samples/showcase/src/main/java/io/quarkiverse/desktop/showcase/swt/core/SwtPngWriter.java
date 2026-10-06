package io.quarkiverse.desktop.showcase.swt.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.eclipse.swt.graphics.ImageData;

/**
 * Minimal RGBA PNG encoder in pure Java, so that the snapshots of the SWT variant are saved without SWT's
 * {@code ImageLoader} ({@code ImageLoader} is under test, as ImageIO is for the AWT variants) : the SWT counterpart of
 * {@code core/PngWriter}, the same bytes for the same pixels.
 */
public final class SwtPngWriter {

    private static final byte[] SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    private SwtPngWriter() {
    }

    /**
     * Writes the pixels of {@code data} ({@link SwtSnapshots#argb}) to {@code file}.
     */
    public static void write(ImageData data, Path file) throws IOException {
        write(data.width, data.height, SwtSnapshots.argb(data), file);
    }

    /**
     * Writes {@code width x height} non premultiplied ARGB pixels (row by row) to {@code file}.
     */
    public static void write(int width, int height, int[] argb, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(encode(width, height, argb));
        }
    }

    /**
     * The PNG file of {@code width x height} non premultiplied ARGB pixels (row by row) : RGBA, 8 bits per channel, no
     * filter, no interlace.
     */
    public static byte[] encode(int width, int height, int[] argb) throws IOException {
        if (argb.length != width * height) {
            throw new IllegalArgumentException(argb.length + " pixels for " + width + "x" + height);
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(height * (width * 4 + 1));
        for (int y = 0; y < height; y++) {
            raw.write(0); // filter: none
            for (int x = 0; x < width; x++) {
                int p = argb[y * width + x];
                raw.write((p >> 16) & 0xFF);
                raw.write((p >> 8) & 0xFF);
                raw.write(p & 0xFF);
                raw.write((p >> 24) & 0xFF);
            }
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(compressed, new Deflater(Deflater.BEST_SPEED))) {
            raw.writeTo(out);
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(header);
        h.writeInt(width);
        h.writeInt(height);
        h.writeByte(8); // bit depth
        h.writeByte(6); // color type: RGBA
        h.writeByte(0); // compression
        h.writeByte(0); // filter
        h.writeByte(0); // interlace

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SIGNATURE);
        chunk(png, "IHDR", header.toByteArray());
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(typeBytes);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
