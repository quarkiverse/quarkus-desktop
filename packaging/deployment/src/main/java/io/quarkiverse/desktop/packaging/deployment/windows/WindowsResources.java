package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
import io.quarkiverse.desktop.packaging.deployment.IconSet.IcoImage;

/**
 * The resources of a Windows executable : its icon and its version information (the Details tab of its properties),
 * as a compiled resource file ({@code .res}) that the linker takes like an object file. Written here : the resource
 * compiler of the Windows SDK ({@code rc.exe}) is not needed.
 */
public final class WindowsResources {

    private static final int RT_ICON = 3;

    private static final int RT_GROUP_ICON = 14;

    private static final int RT_VERSION = 16;

    /**
     * English (United States).
     */
    private static final int LANGUAGE = 0x0409;

    /**
     * The code page of the strings of the version information : Unicode.
     */
    private static final int CODE_PAGE = 0x04b0;

    private WindowsResources() {
    }

    /**
     * The compiled resource file of an executable.
     *
     * @param executableName the file name of the executable ({@code Acme Editor.exe})
     */
    public static byte[] res(IconSet icons, AppInfo app, String executableName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // the empty resource that marks a 32-bit resource file
        entry(out, 0, 0, 0, 0, new byte[0]);
        List<IcoImage> images = icons.icoImages();
        ByteBuffer group = ByteBuffer.allocate(6 + 14 * images.size()).order(ByteOrder.LITTLE_ENDIAN);
        // GRPICONDIR : reserved, type (1 : icon), count
        group.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        for (int i = 0; i < images.size(); i++) {
            IcoImage image = images.get(i);
            int id = i + 1;
            // MOVEABLE | PURE | DISCARDABLE
            entry(out, RT_ICON, id, 0x1010, LANGUAGE, image.data());
            // GRPICONDIRENTRY : width and height (0 : 256), colors, reserved, planes, bits per pixel, size, resource
            group.put((byte) (image.size() >= 256 ? 0 : image.size())).put((byte) (image.size() >= 256 ? 0 : image.size()));
            group.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            group.putInt(image.data().length).putShort((short) id);
        }
        // the icon of the executable is its first icon group
        entry(out, RT_GROUP_ICON, 1, 0x1030, LANGUAGE, group.array());
        entry(out, RT_VERSION, 1, 0x0030, LANGUAGE, versionInfo(app, executableName));
        return out.toByteArray();
    }

    /**
     * A resource : its header (the type and the name are numbers), and its data, aligned on 4 bytes.
     */
    private static void entry(ByteArrayOutputStream out, int type, int name, int memoryFlags, int language, byte[] data) {
        ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(data.length).putInt(32);
        header.putShort((short) 0xffff).putShort((short) type);
        header.putShort((short) 0xffff).putShort((short) name);
        // data version, memory flags, language, version, characteristics
        header.putInt(0).putShort((short) memoryFlags).putShort((short) language).putInt(0).putInt(0);
        out.writeBytes(header.array());
        out.writeBytes(data);
        pad(out);
    }

    private static void pad(ByteArrayOutputStream out) {
        while (out.size() % 4 != 0) {
            out.write(0);
        }
    }

    /**
     * The version information ({@code VS_VERSIONINFO}) : the version as numbers, and the strings that the properties
     * of the file show. The file description is the name of the application : Windows shows it as the name of the
     * program (the task manager, the Open with list). The description of the application is a comment.
     */
    static byte[] versionInfo(AppInfo app, String executableName) {
        int[] version = versionNumbers(app);
        ByteBuffer fixed = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        // VS_FIXEDFILEINFO : signature, structure version, file version, product version
        fixed.putInt(0xfeef04bd).putInt(0x00010000);
        fixed.putInt(version[0] << 16 | version[1]).putInt(version[2] << 16 | version[3]);
        fixed.putInt(version[0] << 16 | version[1]).putInt(version[2] << 16 | version[3]);
        // flags mask, flags, operating system (VOS_NT_WINDOWS32), type (VFT_APP), subtype, date
        fixed.putInt(0x3f).putInt(0).putInt(0x00040004).putInt(1).putInt(0).putInt(0).putInt(0);

        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("Comments", app.description());
        strings.put("CompanyName", app.vendor());
        strings.put("FileDescription", app.name());
        strings.put("FileVersion", app.version());
        strings.put("InternalName", app.name());
        strings.put("LegalCopyright", app.copyright().orElse(""));
        strings.put("OriginalFilename", executableName);
        strings.put("ProductName", app.name());
        strings.put("ProductVersion", app.version());
        List<byte[]> entries = new ArrayList<>();
        for (Map.Entry<String, String> string : strings.entrySet()) {
            byte[] value = (string.getValue() + "\0").getBytes(StandardCharsets.UTF_16LE);
            // the length of a string is a number of characters
            entries.add(block(string.getKey(), true, value, value.length / 2));
        }
        String table = String.format("%04X%04X", LANGUAGE, CODE_PAGE);
        byte[] stringFileInfo = block("StringFileInfo", true, new byte[0], 0,
                block(table, true, new byte[0], 0, entries.toArray(byte[][]::new)));
        byte[] translation = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) LANGUAGE)
                .putShort((short) CODE_PAGE).array();
        byte[] varFileInfo = block("VarFileInfo", true, new byte[0], 0, block("Translation", false, translation, 4));
        return block("VS_VERSION_INFO", false, fixed.array(), 52, stringFileInfo, varFileInfo);
    }

    /**
     * The four numbers of the version of the executable, of 16 bits each.
     */
    static int[] versionNumbers(AppInfo app) {
        String[] parts = app.numericVersion(4).split("\\.");
        int[] numbers = new int[4];
        for (int i = 0; i < 4; i++) {
            numbers[i] = (int) Math.min(65535, Long.parseLong(parts[i]));
        }
        return numbers;
    }

    /**
     * A block of the version information : its length, the length and the type of its value, its key, its value and
     * its children, each aligned on 4 bytes.
     */
    private static byte[] block(String key, boolean text, byte[] value, int valueLength, byte[]... children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // the length, written at the end
        out.write(0);
        out.write(0);
        writeShort(out, valueLength);
        writeShort(out, text ? 1 : 0);
        out.writeBytes((key + "\0").getBytes(StandardCharsets.UTF_16LE));
        pad(out);
        out.writeBytes(value);
        for (byte[] child : children) {
            pad(out);
            out.writeBytes(child);
        }
        byte[] block = out.toByteArray();
        block[0] = (byte) block.length;
        block[1] = (byte) (block.length >> 8);
        return block;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >> 8);
    }
}
