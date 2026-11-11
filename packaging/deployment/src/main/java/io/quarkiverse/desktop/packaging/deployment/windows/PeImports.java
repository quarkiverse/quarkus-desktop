package io.quarkiverse.desktop.packaging.deployment.windows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The imports of a Windows library (a PE file) : the names of the files that it is linked with.
 * <p>
 * The libraries of the JDK that a native executable loads ({@code awt.dll}...) call the JVM : native-image builds the
 * libraries that they are linked with, {@code jvm.dll} and {@code java.dll}, as shims that are themselves linked with
 * the executable, <em>by its file name</em>. An executable that is renamed does not start then : Windows looks for a
 * file of the former name. The application image has the executable under the name of the application, so the name
 * that the shims import is changed here too.
 */
public final class PeImports {

    /**
     * The size of an entry of the import directory.
     */
    private static final int DESCRIPTOR_SIZE = 20;

    private static final int SECTION_SIZE = 40;

    /**
     * The encodings of an imported name that is not ASCII : UTF-8, which the linker of Visual Studio writes, and the
     * ANSI code page of the build.
     */
    private static final List<Charset> ENCODINGS = encodings(System.getProperty("native.encoding"));

    private PeImports() {
    }

    /**
     * The encodings of an imported name that is not ASCII : UTF-8, and the encoding of the platform when this JVM has
     * it.
     *
     * @param nativeEncoding the encoding of the platform ({@code native.encoding} : the ANSI code page on Windows), or
     *        {@code null}
     */
    static List<Charset> encodings(String nativeEncoding) {
        List<Charset> encodings = new ArrayList<>(List.of(StandardCharsets.UTF_8));
        try {
            if (nativeEncoding != null && !encodings.contains(Charset.forName(nativeEncoding))) {
                encodings.add(Charset.forName(nativeEncoding));
            }
        } catch (IllegalArgumentException e) {
            // an encoding that this JVM does not have
        }
        return List.copyOf(encodings);
    }

    /**
     * Whether a name that a library imports, a character per byte, is a file name in one of the encodings, whatever the
     * case of its letters.
     */
    static boolean matches(String imported, String name, List<Charset> encodings) {
        for (Charset encoding : encodings) {
            if (imported.equalsIgnoreCase(new String(name.getBytes(encoding), StandardCharsets.ISO_8859_1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c < 0x80);
    }

    /**
     * A section of the file.
     */
    private record Section(int header, int virtualSize, int virtualAddress, int rawSize, int rawPointer) {

        boolean contains(int rva) {
            return rva >= virtualAddress && rva < virtualAddress + Math.max(virtualSize, rawSize);
        }
    }

    /**
     * An entry of the import directory.
     *
     * @param nameField the offset of the field that has the address of the name
     * @param nameOffset the offset of the name
     * @param name the name of the imported file
     */
    private record Import(int nameField, int nameOffset, String name) {
    }

    /**
     * The names of the files that a library imports, a character per byte (ISO-8859-1) : a name that is not ASCII is
     * as the file has it, in the encoding of the linker.
     *
     * @return an empty list for a file that is not a PE file
     */
    public static List<String> names(byte[] file) {
        return imports(file).stream().map(Import::name).toList();
    }

    /**
     * Whether a PE file has no signature : the certificate table of its data directories is empty.
     *
     * @param file the file, or its first bytes : its headers
     * @return false for a file that is not a PE file, which cannot be signed
     */
    public static boolean unsigned(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int optional = optionalHeader(buffer);
        if (optional < 0) {
            return false;
        }
        // the data directories of a 64-bit file (PE32+) and of a 32-bit file, after their number : the certificate
        // table is the fifth
        int directories = optional + ((buffer.getShort(optional) & 0xffff) == 0x20b ? 112 : 96);
        if (buffer.getInt(directories - 4) <= 4) {
            return false;
        }
        return buffer.getInt(directories + 32) == 0 || buffer.getInt(directories + 36) == 0;
    }

    /**
     * Whether a library imports a file, whatever the case of its name. A name that is not ASCII is compared in UTF-8,
     * which the linker of Visual Studio writes, and in the ANSI code page of the build.
     */
    public static boolean imports(byte[] file, String name) {
        return names(file).stream().anyMatch(imported -> matches(imported, name, ENCODINGS));
    }

    /**
     * Whether a library may import a file whose name is not ASCII in another encoding than the ones that
     * {@link #imports} compares : it does not import that name, but an executable whose name is not ASCII either.
     */
    public static boolean importsUnknownExecutable(byte[] file, String name) {
        List<String> names = names(file);
        return !isAscii(name) && names.stream().noneMatch(imported -> matches(imported, name, ENCODINGS))
                && names.stream().anyMatch(imported -> !isAscii(imported)
                        && imported.toLowerCase(Locale.ROOT).endsWith(".exe"));
    }

    /**
     * A copy of a library that imports a file under another name : the name is written over the former one when it
     * is not longer, and otherwise in the unused bytes at the end of a section of the file.
     *
     * @return empty when the library cannot import the new name : a name with characters that are not ASCII, or a
     *         name that is longer than the former one in a file whose sections have no room for it ; or when the former
     *         name may be imported in an unknown encoding ({@link #importsUnknownExecutable})
     */
    public static Optional<byte[]> rename(byte[] file, String name, String newName) {
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(newName) || importsUnknownExecutable(file, name)) {
            return Optional.empty();
        }
        byte[] renamed = file.clone();
        ByteBuffer buffer = ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN);
        byte[] bytes = (newName + "\0").getBytes(StandardCharsets.US_ASCII);
        for (Import imported : imports(file)) {
            if (!matches(imported.name(), name, ENCODINGS)) {
                continue;
            }
            int length = imported.name().length() + 1;
            if (bytes.length <= length) {
                java.util.Arrays.fill(renamed, imported.nameOffset(), imported.nameOffset() + length, (byte) 0);
                System.arraycopy(bytes, 0, renamed, imported.nameOffset(), bytes.length);
                continue;
            }
            // the bytes between the end of the content of a section and the end of its bytes in the file : zeros,
            // which Windows maps with the section. In long : the fields of a malformed file overflow an int, and the
            // bytes that a section declares may pass the end of the file
            boolean written = false;
            for (Section section : sections(buffer)) {
                long free = (long) section.rawSize() - section.virtualSize();
                long offset = (long) section.rawPointer() + section.virtualSize();
                if (section.virtualSize() <= 0 || free < bytes.length || offset < 0 || offset + free > renamed.length
                        || !isZero(renamed, (int) offset, (int) free)) {
                    continue;
                }
                System.arraycopy(bytes, 0, renamed, (int) offset, bytes.length);
                buffer.putInt(imported.nameField(), section.virtualAddress() + section.virtualSize());
                buffer.putInt(section.header() + 8, section.virtualSize() + bytes.length);
                written = true;
                break;
            }
            if (!written) {
                return Optional.empty();
            }
        }
        return Optional.of(renamed);
    }

    private static boolean isZero(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The offset of the optional header of a PE file, or -1 for another file.
     */
    private static int optionalHeader(ByteBuffer buffer) {
        if (buffer.capacity() < 0x40 || buffer.getShort(0) != 0x5a4d) {
            return -1;
        }
        int pe = buffer.getInt(0x3c);
        // the signature, the file header (20 bytes), and at least the fields of the optional header read here : a
        // subtraction, which does not overflow for an offset near the largest int
        if (pe < 0 || pe > buffer.capacity() - (24 + 240) || buffer.getInt(pe) != 0x00004550) {
            return -1;
        }
        return pe + 24;
    }

    private static List<Section> sections(ByteBuffer buffer) {
        List<Section> sections = new ArrayList<>();
        int optional = optionalHeader(buffer);
        if (optional < 0) {
            return sections;
        }
        int pe = optional - 24;
        int count = buffer.getShort(pe + 6) & 0xffff;
        int first = optional + (buffer.getShort(pe + 20) & 0xffff);
        for (int i = 0; i < count && first + SECTION_SIZE * (i + 1) <= buffer.capacity(); i++) {
            int header = first + SECTION_SIZE * i;
            sections.add(new Section(header, buffer.getInt(header + 8), buffer.getInt(header + 12),
                    buffer.getInt(header + 16), buffer.getInt(header + 20)));
        }
        return sections;
    }

    /**
     * The offset in the file of a relative virtual address, or -1 when no section has it.
     */
    private static int offset(List<Section> sections, int rva, int capacity) {
        for (Section section : sections) {
            if (section.contains(rva)) {
                int offset = rva - section.virtualAddress() + section.rawPointer();
                return offset >= 0 && offset < capacity ? offset : -1;
            }
        }
        return -1;
    }

    private static List<Import> imports(byte[] file) {
        List<Import> imports = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int optional = optionalHeader(buffer);
        if (optional < 0) {
            return imports;
        }
        // the data directories of a 64-bit file (PE32+) and of a 32-bit file : the import directory is the second
        int magic = buffer.getShort(optional) & 0xffff;
        int directories = optional + (magic == 0x20b ? 112 : 96);
        int importRva = buffer.getInt(directories + 8);
        List<Section> sections = sections(buffer);
        int descriptor = importRva == 0 ? -1 : offset(sections, importRva, file.length);
        while (descriptor >= 0 && descriptor + DESCRIPTOR_SIZE <= file.length) {
            int nameRva = buffer.getInt(descriptor + 12);
            if (nameRva == 0) {
                break;
            }
            int nameOffset = offset(sections, nameRva, file.length);
            if (nameOffset < 0) {
                break;
            }
            int end = nameOffset;
            while (end < file.length && file[end] != 0) {
                end++;
            }
            if (end == file.length) {
                // a truncated file : a name without its terminating zero, which could not be renamed in place
                break;
            }
            // a character per byte : the length of the name is the one of its bytes, which it is renamed in
            imports.add(new Import(descriptor + 12, nameOffset,
                    new String(file, nameOffset, end - nameOffset, StandardCharsets.ISO_8859_1)));
            descriptor += DESCRIPTOR_SIZE;
        }
        return imports;
    }
}
