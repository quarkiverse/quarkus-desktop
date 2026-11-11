package io.quarkiverse.desktop.packaging.deployment.macos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The Mach-O files : what tells them from the other files, their kind, and their signature.
 */
class MachOTest {

    /**
     * The signature of a Mach-O file.
     */
    enum Signature {
        /**
         * No {@code LC_CODE_SIGNATURE} load command.
         */
        NONE,
        /**
         * The one of the linker : a code directory alone.
         */
        LINKER,
        /**
         * An ad hoc one : a code directory and an empty CMS blob.
         */
        AD_HOC,
        /**
         * One with a certificate : a code directory and a CMS signature.
         */
        CERTIFICATE
    }

    /**
     * The first bytes of a Java class file : the magic number of a universal file, then its version (Java 21).
     */
    static final byte[] CLASS_FILE = { (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 65, 0, 1, 2, 3 };

    /**
     * A Mach-O file of one architecture : its header, one load command, then its code signature.
     *
     * @param is64 whether it is a 64-bit file, rather than a 32-bit one
     * @param type its file type : {@code 6} for a dynamic library, {@code 10} for debug symbols
     */
    static byte[] machO(boolean is64, int type, Signature signature) {
        int header = is64 ? 32 : 28;
        // the super blob : its header and the index of 2 blobs, a code directory of 12 bytes, a CMS blob of 8 bytes
        // and of its data
        int signatureSize = switch (signature) {
            case NONE -> 0;
            case LINKER -> 12 + 8 + 12;
            case AD_HOC -> 12 + 16 + 12 + 8;
            case CERTIFICATE -> 12 + 16 + 12 + 8 + 16;
        };
        ByteBuffer file = ByteBuffer.allocate(header + 16 + signatureSize).order(ByteOrder.LITTLE_ENDIAN);
        file.putInt(0, is64 ? 0xfeedfacf : 0xfeedface).putInt(4, is64 ? 0x0100000c : 7).putInt(12, type);
        file.putInt(16, 1).putInt(20, 16);
        if (signature == Signature.NONE) {
            // LC_UUID, cut to 16 bytes
            file.putInt(header, 0x1b).putInt(header + 4, 16);
            return file.array();
        }
        file.putInt(header, 0x1d).putInt(header + 4, 16).putInt(header + 8, header + 16).putInt(header + 12,
                signatureSize);
        ByteBuffer blobs = file.slice(header + 16, signatureSize).order(ByteOrder.BIG_ENDIAN);
        int count = signature == Signature.LINKER ? 1 : 2;
        int directory = 12 + 8 * count;
        blobs.putInt(0, 0xfade0cc0).putInt(4, signatureSize).putInt(8, count);
        blobs.putInt(12, 0).putInt(16, directory);
        blobs.putInt(directory, 0xfade0c02).putInt(directory + 4, 12);
        if (signature != Signature.LINKER) {
            int cms = directory + 12;
            blobs.putInt(20, 0x10000).putInt(24, cms);
            blobs.putInt(cms, 0xfade0b01).putInt(cms + 4, signatureSize - cms);
        }
        return file.array();
    }

    /**
     * A 64-bit dynamic library.
     */
    static byte[] library(Signature signature) {
        return machO(true, 6, signature);
    }

    /**
     * A universal file of the given architectures.
     *
     * @param fat64 whether it has the 64-bit offsets and sizes of {@code FAT_MAGIC_64}
     */
    static byte[] universal(boolean fat64, byte[]... architectures) {
        int entrySize = fat64 ? 32 : 20;
        int offset = 8 + entrySize * architectures.length;
        int size = offset + Arrays.stream(architectures).mapToInt(architecture -> architecture.length).sum();
        ByteBuffer file = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        file.putInt(0, fat64 ? 0xcafebabf : 0xcafebabe).putInt(4, architectures.length);
        for (int i = 0; i < architectures.length; i++) {
            int entry = 8 + entrySize * i;
            if (fat64) {
                file.putLong(entry + 8, offset).putLong(entry + 16, architectures[i].length);
            } else {
                file.putInt(entry + 8, offset).putInt(entry + 12, architectures[i].length);
            }
            file.put(offset, architectures[i]);
            offset += architectures[i].length;
        }
        return file.array();
    }

    @Test
    void theMachOFiles() {
        assertTrue(MachO.isMachO(library(Signature.NONE)));
        assertTrue(MachO.isMachO(machO(false, 6, Signature.NONE)));
        assertTrue(MachO.isMachO(universal(false, library(Signature.NONE))));
        assertTrue(MachO.isMachO(universal(true, library(Signature.NONE))));
        // a class file, a jar, a big-endian file of PowerPC, a short file
        assertFalse(MachO.isMachO(CLASS_FILE));
        assertFalse(MachO.isMachO("PK\u0003\u0004 a jar".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(MachO.isMachO(new byte[] { (byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce, 0, 0, 0, 18 }));
        assertFalse(MachO.isMachO(new byte[] { (byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe }));
    }

    /**
     * The executables, libraries and bundles are code ; the object files and the debug symbols are not.
     */
    @Test
    void theMachOFilesThatAreCode() {
        for (int type : new int[] { 2, 6, 8 }) {
            assertTrue(MachO.isCode(machO(true, type, Signature.NONE)), "type " + type);
        }
        assertTrue(MachO.isCode(machO(false, 6, Signature.NONE)));
        assertTrue(MachO.isCode(universal(false, library(Signature.NONE), machO(false, 6, Signature.NONE))));
        assertFalse(MachO.isCode(machO(true, 1, Signature.NONE)));
        assertFalse(MachO.isCode(machO(true, 10, Signature.NONE)));
        assertFalse(MachO.isCode(universal(false, library(Signature.NONE), machO(true, 10, Signature.NONE))));
        assertFalse(MachO.isCode(CLASS_FILE));
        assertFalse(MachO.isCode(Arrays.copyOf(library(Signature.NONE), 20)));
    }

    @Test
    void theSignaturesWithACertificate() {
        assertTrue(MachO.signedWithCertificate(library(Signature.CERTIFICATE)));
        assertTrue(MachO.signedWithCertificate(machO(false, 6, Signature.CERTIFICATE)));
        assertFalse(MachO.signedWithCertificate(library(Signature.NONE)));
        assertFalse(MachO.signedWithCertificate(library(Signature.LINKER)));
        assertFalse(MachO.signedWithCertificate(library(Signature.AD_HOC)));
        // every architecture of a universal file
        assertTrue(MachO.signedWithCertificate(universal(false, library(Signature.CERTIFICATE),
                machO(false, 6, Signature.CERTIFICATE))));
        assertTrue(MachO.signedWithCertificate(universal(true, library(Signature.CERTIFICATE),
                library(Signature.CERTIFICATE))));
        assertFalse(MachO.signedWithCertificate(universal(false, library(Signature.CERTIFICATE),
                library(Signature.AD_HOC))));
        assertFalse(MachO.signedWithCertificate(CLASS_FILE));
        // a truncated file
        byte[] signed = library(Signature.CERTIFICATE);
        for (int length : new int[] { 8, 30, 40, 47, 60, signed.length - 1 }) {
            assertFalse(MachO.signedWithCertificate(Arrays.copyOf(signed, length)), "length " + length);
        }
        byte[] universal = universal(false, library(Signature.CERTIFICATE), library(Signature.CERTIFICATE));
        assertFalse(MachO.signedWithCertificate(Arrays.copyOf(universal, universal.length - 1)));
    }
}
