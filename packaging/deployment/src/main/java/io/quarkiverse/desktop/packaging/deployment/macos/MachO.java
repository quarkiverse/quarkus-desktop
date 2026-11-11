package io.quarkiverse.desktop.packaging.deployment.macos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The Mach-O files of macOS, its executables and libraries : what tells them from the other files, and whether they are
 * signed with a certificate.
 */
final class MachO {

    private static final int MH_MAGIC = 0xfeedface;

    private static final int MH_MAGIC_64 = 0xfeedfacf;

    private static final int FAT_MAGIC = 0xcafebabe;

    private static final int FAT_MAGIC_64 = 0xcafebabf;

    /**
     * The limit of the number of architectures of a universal file : a Java class file, which starts with the same
     * magic number, has its version there, 45 or more.
     */
    private static final int FAT_ARCHITECTURES = 20;

    private static final int MH_EXECUTE = 0x2;

    private static final int MH_DYLIB = 0x6;

    private static final int MH_BUNDLE = 0x8;

    private static final int LC_CODE_SIGNATURE = 0x1d;

    private static final int CSMAGIC_EMBEDDED_SIGNATURE = 0xfade0cc0;

    private static final int CSMAGIC_BLOBWRAPPER = 0xfade0b01;

    /**
     * The slot of the CMS signature in a code signature.
     */
    private static final int CSSLOT_SIGNATURESLOT = 0x10000;

    private MachO() {
    }

    /**
     * Whether a file is a Mach-O file, from its first 8 bytes : a 32-bit or 64-bit one, or a universal one.
     */
    static boolean isMachO(byte[] header) {
        if (header.length < 8) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) == MH_MAGIC || buffer.getInt(0) == MH_MAGIC_64) {
            return true;
        }
        buffer.order(ByteOrder.BIG_ENDIAN);
        int architectures = buffer.getInt(4);
        return (buffer.getInt(0) == FAT_MAGIC || buffer.getInt(0) == FAT_MAGIC_64) && architectures > 0
                && architectures < FAT_ARCHITECTURES;
    }

    /**
     * Whether a Mach-O file is code that runs, which is signed : an executable, a dynamic library or a bundle, in each
     * of its architectures. Not an object file, or the debug symbols of a library ({@code .dSYM}), which
     * {@code codesign} cannot sign.
     */
    static boolean isCode(byte[] file) {
        List<ByteBuffer> architectures = architectures(file);
        for (ByteBuffer architecture : architectures) {
            int type = architecture.getInt(12);
            if (type != MH_EXECUTE && type != MH_DYLIB && type != MH_BUNDLE) {
                return false;
            }
        }
        return !architectures.isEmpty();
    }

    /**
     * Whether each architecture of a Mach-O file is signed with a certificate : its code signature has a CMS
     * signature, which an ad hoc signature and the one of the linker do not have.
     */
    static boolean signedWithCertificate(byte[] file) {
        List<ByteBuffer> architectures = architectures(file);
        for (ByteBuffer architecture : architectures) {
            if (!signedWithCertificate(architecture)) {
                return false;
            }
        }
        return !architectures.isEmpty();
    }

    /**
     * The architectures of a Mach-O file, in little-endian order : the file itself, or the ones of a universal file.
     * None when it is not a Mach-O file of macOS, or a truncated one.
     */
    private static List<ByteBuffer> architectures(byte[] file) {
        if (!isMachO(file)) {
            return List.of();
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        List<ByteBuffer> architectures = new ArrayList<>();
        if (buffer.getInt(0) != FAT_MAGIC && buffer.getInt(0) != FAT_MAGIC_64) {
            architectures.add(buffer);
        } else {
            // fat_arch : cputype, cpusubtype, offset, size, align (20 bytes) ; fat_arch_64 : 64-bit offset and size,
            // and a reserved field (32 bytes)
            boolean fat64 = buffer.getInt(0) == FAT_MAGIC_64;
            int entrySize = fat64 ? 32 : 20;
            int count = buffer.getInt(4);
            for (int i = 0; i < count; i++) {
                int entry = 8 + entrySize * i;
                if (entry + entrySize > file.length) {
                    return List.of();
                }
                long offset = fat64 ? buffer.getLong(entry + 8) : Integer.toUnsignedLong(buffer.getInt(entry + 8));
                long size = fat64 ? buffer.getLong(entry + 16) : Integer.toUnsignedLong(buffer.getInt(entry + 12));
                if (offset < 0 || size <= 0 || offset + size > file.length) {
                    return List.of();
                }
                architectures.add(buffer.slice((int) offset, (int) size));
            }
        }
        for (ByteBuffer architecture : architectures) {
            // a big-endian architecture (PowerPC) is not one of macOS anymore
            architecture.order(ByteOrder.LITTLE_ENDIAN);
            if (architecture.limit() < 28
                    || (architecture.getInt(0) != MH_MAGIC && architecture.getInt(0) != MH_MAGIC_64)) {
                return List.of();
            }
        }
        return architectures;
    }

    /**
     * Whether the Mach-O file of one architecture has a CMS signature : its {@code LC_CODE_SIGNATURE} load command
     * points to a code signature with a non-empty CMS blob.
     */
    private static boolean signedWithCertificate(ByteBuffer file) {
        int size = file.limit();
        long commands = Integer.toUnsignedLong(file.getInt(16));
        long offset = file.getInt(0) == MH_MAGIC_64 ? 32 : 28;
        for (long i = 0; i < commands && offset + 8 <= size; i++) {
            int at = (int) offset;
            long commandSize = Integer.toUnsignedLong(file.getInt(at + 4));
            // linkedit_data_command : cmd, cmdsize, dataoff, datasize
            if (file.getInt(at) == LC_CODE_SIGNATURE) {
                if (offset + 16 > size) {
                    return false;
                }
                long dataOffset = Integer.toUnsignedLong(file.getInt(at + 8));
                long dataSize = Integer.toUnsignedLong(file.getInt(at + 12));
                return dataOffset + dataSize <= size && hasCms(file.slice((int) dataOffset, (int) dataSize));
            }
            if (commandSize < 8) {
                return false;
            }
            offset += commandSize;
        }
        return false;
    }

    /**
     * Whether a code signature has a CMS signature. It is a super blob, in big-endian order : its magic number, its
     * length, the number of its blobs, and the type and offset of each one. The CMS blob of an ad hoc signature is
     * empty.
     */
    private static boolean hasCms(ByteBuffer signature) {
        int size = signature.limit();
        signature.order(ByteOrder.BIG_ENDIAN);
        if (size < 12 || signature.getInt(0) != CSMAGIC_EMBEDDED_SIGNATURE) {
            return false;
        }
        long count = Integer.toUnsignedLong(signature.getInt(8));
        for (long i = 0; i < count && 12 + 8 * (i + 1) <= size; i++) {
            int at = (int) (12 + 8 * i);
            long blob = Integer.toUnsignedLong(signature.getInt(at + 4));
            if (signature.getInt(at) == CSSLOT_SIGNATURESLOT) {
                return blob + 8 <= size && signature.getInt((int) blob) == CSMAGIC_BLOBWRAPPER
                        && Integer.toUnsignedLong(signature.getInt((int) blob + 4)) > 8;
            }
        }
        return false;
    }
}
