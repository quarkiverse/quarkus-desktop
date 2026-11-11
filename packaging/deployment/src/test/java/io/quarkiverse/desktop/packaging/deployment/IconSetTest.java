package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IconSetTest {

    @Test
    void theGenericIconIsARoundedSquare() throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(IconSet.generic().png(256)));
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        // transparent corners, an opaque blue background, a white window frame
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(0xff, image.getRGB(128, 40) >>> 24);
        assertTrue((image.getRGB(128, 40) & 0xff) > 0xc0, Integer.toHexString(image.getRGB(128, 40)));
        assertTrue((image.getRGB(128, 82) & 0xffffff) > 0xe0e0e0, Integer.toHexString(image.getRGB(128, 82)));
    }

    @Test
    void anImageIsScaledToEverySize(@TempDir Path directory) throws IOException {
        // a red image, wider than high : centered in a transparent square
        BufferedImage source = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                source.setRGB(x, y, 0xffff0000);
            }
        }
        Path file = directory.resolve("icon.png");
        ImageIO.write(source, "png", file.toFile());
        IconSet icons = IconSet.of(file);
        assertEquals(64, icons.sourceSize());
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(icons.png(16)));
        assertEquals(16, small.getWidth());
        assertEquals(0, small.getRGB(8, 1) >>> 24);
        assertEquals(0xffff0000, small.getRGB(8, 8));
        BufferedImage large = ImageIO.read(new ByteArrayInputStream(icons.png(128)));
        assertEquals(128, large.getHeight());
        assertEquals(0xffff0000, large.getRGB(64, 64));
        assertEquals(0, large.getRGB(64, 4) >>> 24);
    }

    @Test
    void theIconOfTheProject(@TempDir Path project) throws IOException {
        assertEquals(1024, IconSet.load(Optional.empty(), project).sourceSize());
        BufferedImage source = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        Path icon = project.resolve("src/main/packaging/icon.png");
        java.nio.file.Files.createDirectories(icon.getParent());
        ImageIO.write(source, "png", icon.toFile());
        assertEquals(48, IconSet.load(Optional.empty(), project).sourceSize());
        assertEquals(48, IconSet.load(Optional.of("src/main/packaging/icon.png"), project).sourceSize());
        org.junit.jupiter.api.Assertions.assertThrows(PackagingException.class,
                () -> IconSet.load(Optional.of("missing.png"), project));
    }

    @Test
    void theWindowsIconHasABitmapOfEachSizeAndAPngOf256Pixels() {
        byte[] ico = IconSet.generic().ico();
        ByteBuffer buffer = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buffer.getShort(0));
        assertEquals(1, buffer.getShort(2));
        int count = buffer.getShort(4);
        assertEquals(IconSet.ICO_SIZES.length, count);
        int expectedOffset = 6 + 16 * count;
        for (int i = 0; i < count; i++) {
            int entry = 6 + 16 * i;
            int size = IconSet.ICO_SIZES[i];
            assertEquals(size == 256 ? 0 : size, buffer.get(entry) & 0xff);
            assertEquals(32, buffer.getShort(entry + 6));
            int length = buffer.getInt(entry + 8);
            int offset = buffer.getInt(entry + 12);
            assertEquals(expectedOffset, offset);
            expectedOffset += length;
            if (size == 256) {
                // a PNG file
                assertEquals(0x89, ico[offset] & 0xff);
                assertEquals('P', ico[offset + 1]);
            } else {
                // a bitmap header, whose height counts the pixels and the mask
                assertEquals(40, buffer.getInt(offset));
                assertEquals(size, buffer.getInt(offset + 4));
                assertEquals(size * 2, buffer.getInt(offset + 8));
                assertEquals(40 + size * size * 4 + ((size + 31) / 32) * 4 * size, length);
            }
        }
        assertEquals(ico.length, expectedOffset);
    }

    @Test
    void theMacIconHasAPngOfEachType() {
        byte[] icns = IconSet.generic().icns();
        ByteBuffer buffer = ByteBuffer.wrap(icns);
        assertEquals("icns", new String(icns, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(icns.length, buffer.getInt(4));
        int offset = 8;
        int entries = 0;
        while (offset < icns.length) {
            int length = buffer.getInt(offset + 4);
            assertEquals(0x89, icns[offset + 8] & 0xff, new String(icns, offset, 4, StandardCharsets.US_ASCII));
            offset += length;
            entries++;
        }
        assertEquals(icns.length, offset);
        assertEquals(10, entries);
    }
}
