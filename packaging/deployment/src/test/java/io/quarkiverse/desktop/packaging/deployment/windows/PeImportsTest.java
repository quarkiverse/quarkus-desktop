package io.quarkiverse.desktop.packaging.deployment.windows;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The name of the executable that the libraries of a Windows native executable import. The libraries of these tests
 * serve the tests of the application image too.
 */
public class PeImportsTest {

    static final int SECTION_HEADER = 0x40 + 24 + 240;

    static final int SECTION_DATA = 0x200;

    /**
     * The length of the name of the executable in {@link #library(int, byte[])}, in bytes, at most : it is at the
     * offset 80 of the content of the section, which has 100 bytes, and ends with a zero.
     */
    static final int EXECUTABLE_NAME = 100 - 80 - 1;

    /**
     * A 64-bit library with one section, which imports {@code KERNEL32.dll} and {@code app-runner.exe}.
     *
     * @param rawSize the size of the section in the file : its content, then zeros
     */
    static byte[] library(int rawSize) {
        return library(rawSize, "app-runner.exe");
    }

    /**
     * A 64-bit library with one section, which imports {@code KERNEL32.dll} and an executable.
     *
     * @param rawSize the size of the section in the file : its content (100 bytes), then zeros
     * @param executable the name of the executable, in UTF-8
     */
    public static byte[] library(int rawSize, String executable) {
        return library(rawSize, executable.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A 64-bit library with one section, which imports {@code KERNEL32.dll} and an executable.
     *
     * @param rawSize the size of the section in the file : its content (100 bytes), then zeros
     * @param executable the name of the executable, as the library has it : {@link #EXECUTABLE_NAME} bytes at most
     */
    public static byte[] library(int rawSize, byte[] executable) {
        if (executable.length > EXECUTABLE_NAME) {
            throw new IllegalArgumentException("The name of the executable has more than " + EXECUTABLE_NAME + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(SECTION_DATA + rawSize).order(ByteOrder.LITTLE_ENDIAN);
        // the DOS header and the address of the PE header
        buffer.putShort(0, (short) 0x5a4d).putInt(0x3c, 0x40);
        // the signature, then the file header : machine, sections, size of the optional header
        buffer.putInt(0x40, 0x00004550).putShort(0x44, (short) 0x8664).putShort(0x46, (short) 1).putShort(0x54, (short) 240);
        int optional = 0x58;
        // PE32+, and the import directory (the second data directory)
        buffer.putShort(optional, (short) 0x20b).putInt(optional + 112 + 8, 0x1000).putInt(optional + 112 + 12, 60);
        // the section : virtual size, virtual address, size and offset in the file
        buffer.put(SECTION_HEADER, ".rdata".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(SECTION_HEADER + 8, 100).putInt(SECTION_HEADER + 12, 0x1000).putInt(SECTION_HEADER + 16, rawSize)
                .putInt(SECTION_HEADER + 20, SECTION_DATA);
        // two import descriptors (the address of the name is their fourth field) and the empty one that ends them
        buffer.putInt(SECTION_DATA + 12, 0x1000 + 60).putInt(SECTION_DATA + 20 + 12, 0x1000 + 80);
        buffer.put(SECTION_DATA + 60, "KERNEL32.dll".getBytes(StandardCharsets.US_ASCII));
        buffer.put(SECTION_DATA + 80, executable);
        return buffer.array();
    }

    @Test
    void theImportsOfALibrary() {
        byte[] library = library(0x200);
        assertEquals(List.of("KERNEL32.dll", "app-runner.exe"), PeImports.names(library));
        assertTrue(PeImports.imports(library, "APP-RUNNER.EXE"));
        assertFalse(PeImports.imports(library, "other.exe"));
        // not a library
        assertEquals(List.of(), PeImports.names("awt.dll".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(List.of(), PeImports.names(new byte[0x400]));
    }

    @Test
    void aShorterNameReplacesTheFormerOne() {
        byte[] library = library(0x200);
        byte[] renamed = PeImports.rename(library, "app-runner.exe", "Editor.exe").orElseThrow();
        assertEquals(List.of("KERNEL32.dll", "Editor.exe"), PeImports.names(renamed));
        assertEquals(library.length, renamed.length);
        // in place : the address of the name and the size of the section are the same
        assertEquals(0x1000 + 80, ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN).getInt(SECTION_DATA + 20 + 12));
        assertEquals(100, ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN).getInt(SECTION_HEADER + 8));
        // the library is not changed
        assertEquals(List.of("KERNEL32.dll", "app-runner.exe"), PeImports.names(library));
    }

    @Test
    void aLongerNameIsWrittenAtTheEndOfTheSection() {
        byte[] library = library(0x200);
        String name = "Quarkus Desktop Showcase.exe";
        byte[] renamed = PeImports.rename(library, "app-runner.exe", name).orElseThrow();
        assertEquals(List.of("KERNEL32.dll", name), PeImports.names(renamed));
        ByteBuffer buffer = ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x1000 + 100, buffer.getInt(SECTION_DATA + 20 + 12));
        assertEquals(100 + name.length() + 1, buffer.getInt(SECTION_HEADER + 8));
        // a library that does not import the executable is not changed
        assertArrayEquals(library, PeImports.rename(library, "other.exe", name).orElseThrow());
    }

    @Test
    void aNameThatCannotBeImported() {
        // no room after the content of the section
        assertTrue(PeImports.rename(library(100), "app-runner.exe", "Quarkus Desktop Showcase.exe").isEmpty());
        // not ASCII
        assertTrue(PeImports.rename(library(0x200), "app-runner.exe", "Éditeur.exe").isEmpty());
        // the shorter name still fits
        assertTrue(PeImports.rename(library(100), "app-runner.exe", "Editor.exe").isPresent());
    }

    /**
     * An imported name that is not ASCII is as the library has it, a character per byte : the linker writes it in
     * UTF-8, or in the ANSI code page of the build. It is renamed in its bytes.
     */
    @Test
    void aNameThatIsNotAscii() {
        String built = "Éditeur-runner.exe";
        byte[] library = library(0x200, built);
        assertEquals(List.of("KERNEL32.dll", new String(built.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)),
                PeImports.names(library));
        assertTrue(PeImports.imports(library, built));
        assertTrue(PeImports.imports(library, "Éditeur-RUNNER.EXE"));
        assertFalse(PeImports.imports(library, "Editeur-runner.exe"));
        assertFalse(PeImports.importsUnknownExecutable(library, built));
        // in place : the former name has 19 bytes
        byte[] renamed = PeImports.rename(library, built, "Editor.exe").orElseThrow();
        assertEquals(List.of("KERNEL32.dll", "Editor.exe"), PeImports.names(renamed));
        assertEquals(0x1000 + 80, ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN).getInt(SECTION_DATA + 20 + 12));
        assertEquals(100, ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN).getInt(SECTION_HEADER + 8));

        // in the ANSI code page of the build : a byte per character
        String ansi = PeImports.names(library(0x200, built.getBytes(Charset.forName("windows-1252")))).get(1);
        assertEquals(built.length(), ansi.length());
        assertTrue(PeImports.matches(ansi, built, PeImports.encodings("Cp1252")));
        assertFalse(PeImports.matches(ansi, built, PeImports.encodings("UTF-8")));
    }

    /**
     * An executable whose name is not ASCII, in an encoding that is not known, may be the executable of the build : the
     * library is not renamed.
     */
    @Test
    void aNameInAnUnknownEncoding() {
        String built = "Éditeur-runner.exe";
        // the E with an acute accent of the code page 437
        byte[] name = "?diteur-runner.exe".getBytes(StandardCharsets.US_ASCII);
        name[0] = (byte) 0x90;
        byte[] library = library(0x200, name);
        assertFalse(PeImports.imports(library, built));
        assertTrue(PeImports.importsUnknownExecutable(library, built));
        assertTrue(PeImports.rename(library, built, "Editor.exe").isEmpty());
        // the linker writes an ASCII name as it is
        assertFalse(PeImports.importsUnknownExecutable(library, "app-runner.exe"));
        assertFalse(PeImports.importsUnknownExecutable(library(0x200), built));
        // a library whose name is not ASCII, rather than an executable
        byte[] dll = "?diteur.dll".getBytes(StandardCharsets.US_ASCII);
        dll[0] = (byte) 0x90;
        assertFalse(PeImports.importsUnknownExecutable(library(0x200, dll), built));
    }

    /**
     * The encodings of the imported names : UTF-8, and the one of the platform when the JVM has it.
     */
    @Test
    void theEncodingsOfTheImportedNames() {
        assertEquals(List.of(StandardCharsets.UTF_8), PeImports.encodings(null));
        assertEquals(List.of(StandardCharsets.UTF_8), PeImports.encodings("UTF-8"));
        assertEquals(List.of(StandardCharsets.UTF_8, Charset.forName("windows-1252")), PeImports.encodings("Cp1252"));
        assertEquals(List.of(StandardCharsets.UTF_8), PeImports.encodings("x-unknown"));
        assertEquals(List.of(StandardCharsets.UTF_8), PeImports.encodings("not a name"));
    }

    /**
     * The offset of the PE header near the largest int : not a PE file, rather than an exception.
     */
    @Test
    void aHeaderBeyondTheEndOfTheFile() {
        byte[] file = new byte[0x400];
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) 0x5a4d).putInt(0x3c, 0x7ffffff0);
        assertEquals(List.of(), PeImports.names(file));
        assertFalse(PeImports.unsigned(file));
    }

    /**
     * A section whose bytes pass the end of the file, or whose offset overflows an int, has no room for a longer
     * name.
     */
    @Test
    void aSectionBeyondTheEndOfTheFile() {
        String name = "Quarkus Desktop Showcase.exe";
        // more bytes than the file has
        byte[] library = library(0x100);
        ByteBuffer.wrap(library).order(ByteOrder.LITTLE_ENDIAN).putInt(SECTION_HEADER + 16, 0x1000);
        assertEquals(List.of("KERNEL32.dll", "app-runner.exe"), PeImports.names(library));
        assertTrue(PeImports.rename(library, "app-runner.exe", name).isEmpty());
        // a second section, after a full one, at an offset near the largest int or negative
        for (int rawPointer : new int[] { 0x7ffffff0, 0xffffff00 }) {
            byte[] sections = library(100);
            ByteBuffer.wrap(sections).order(ByteOrder.LITTLE_ENDIAN).putShort(0x46, (short) 2)
                    .putInt(SECTION_HEADER + 40 + 8, 100).putInt(SECTION_HEADER + 40 + 12, 0x2000)
                    .putInt(SECTION_HEADER + 40 + 16, 0x1000).putInt(SECTION_HEADER + 40 + 20, rawPointer);
            assertTrue(PeImports.rename(sections, "app-runner.exe", name).isEmpty());
        }
    }

    /**
     * A file that ends in an imported name, without its terminating zero : the name is not imported.
     */
    @Test
    void aTruncatedName() {
        byte[] library = Arrays.copyOf(library(0x200), SECTION_DATA + 80 + "app-runner.exe".length());
        assertEquals(List.of("KERNEL32.dll"), PeImports.names(library));
        assertArrayEquals(library, PeImports.rename(library, "app-runner.exe", "Editor.exe").orElseThrow());
    }

    /**
     * A library is signed when the certificate table of its data directories (the fifth) has a signature.
     */
    @Test
    void theSignatureOfALibrary() {
        byte[] library = library(0x200);
        // no data directory count : not a file that signtool signs
        assertFalse(PeImports.unsigned(library));
        ByteBuffer buffer = ByteBuffer.wrap(library).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x58 + 108, 16);
        assertTrue(PeImports.unsigned(library));
        buffer.putInt(0x58 + 112 + 32, 0x400).putInt(0x58 + 112 + 36, 1400);
        assertFalse(PeImports.unsigned(library));
        // not a library
        assertFalse(PeImports.unsigned("awt.dll".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(PeImports.unsigned(new byte[0x400]));
    }
}
