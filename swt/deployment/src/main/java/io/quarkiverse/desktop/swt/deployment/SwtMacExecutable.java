package io.quarkiverse.desktop.swt.deployment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import org.jboss.logging.Logger;

/**
 * The minimum macOS version and the SDK version of a native executable : the ones of the {@code java} launcher of the
 * JDK that builds it ({@code quarkus.desktop.swt.macos.jdk-build-version}), written by the linker
 * ({@code -platform_version}) with a {@code -H:NativeLinkerOption} option in a {@code native-image.properties} file
 * generated in the application jar.
 * <p>
 * Copy of the build version part of the {@code MacExecutable} of the Desktop AWT extension, which this extension does
 * not depend on.
 */
final class SwtMacExecutable {

    private static final Logger LOGGER = Logger.getLogger(SwtMacExecutable.class);

    private SwtMacExecutable() {
    }

    /**
     * The minimum macOS version and the SDK version of a Mach-O file, as {@code ld -platform_version} takes them
     * ({@code 11.0}, {@code 14.5}).
     */
    record BuildVersion(String minimum, String sdk) {

        /**
         * The native build option that writes these versions in the executable.
         */
        String linkerOption() {
            // ld : -platform_version platform min_version sdk_version
            return "-H:NativeLinkerOption=-Wl,-platform_version,macos," + minimum + "," + sdk;
        }
    }

    private static final int MH_MAGIC_64 = 0xfeedfacf;
    private static final int FAT_MAGIC = 0xcafebabe;
    private static final int FAT_MAGIC_64 = 0xcafebabf;
    private static final int CPU_TYPE_ARM64 = 0x0100000c;
    private static final int CPU_TYPE_X86_64 = 0x01000007;
    private static final int LC_VERSION_MIN_MACOSX = 0x24;
    private static final int LC_BUILD_VERSION = 0x32;
    private static final int PLATFORM_MACOS = 1;

    /**
     * The versions of the {@code LC_BUILD_VERSION} (or older {@code LC_VERSION_MIN_MACOSX}) load command of a 64-bit
     * Mach-O file, or of the slice of the current architecture of a universal file.
     *
     * @return empty when the file has none, or is not a Mach-O file
     */
    static Optional<BuildVersion> buildVersion(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        if (file.length >= 8 && (buffer.getInt(0) == FAT_MAGIC || buffer.getInt(0) == FAT_MAGIC_64)) {
            // the build runs on the target platform (no cross compilation)
            int wanted = "x86_64".equals(System.getProperty("os.arch")) ? CPU_TYPE_X86_64 : CPU_TYPE_ARM64;
            // fat_arch : cputype, cpusubtype, offset, size, align (20 bytes) ; fat_arch_64 : 64-bit offset and size,
            // and a reserved field (32 bytes)
            boolean fat64 = buffer.getInt(0) == FAT_MAGIC_64;
            int entrySize = fat64 ? 32 : 20;
            long count = Integer.toUnsignedLong(buffer.getInt(4));
            for (long i = 0; i < count && 8 + entrySize * (i + 1) <= file.length; i++) {
                int entry = (int) (8 + entrySize * i);
                if (buffer.getInt(entry) == wanted) {
                    long offset = fat64 ? buffer.getLong(entry + 8) : Integer.toUnsignedLong(buffer.getInt(entry + 8));
                    long size = fat64 ? buffer.getLong(entry + 16) : Integer.toUnsignedLong(buffer.getInt(entry + 12));
                    if (offset >= 0 && size > 0 && offset + size <= file.length) {
                        return buildVersion(Arrays.copyOfRange(file, (int) offset, (int) (offset + size)));
                    }
                }
            }
            return Optional.empty();
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < 32 || buffer.getInt(0) != MH_MAGIC_64) {
            return Optional.empty();
        }
        long commands = Integer.toUnsignedLong(buffer.getInt(16));
        long offset = 32;
        for (long i = 0; i < commands && offset + 8 <= file.length; i++) {
            int at = (int) offset;
            int command = buffer.getInt(at);
            long size = Integer.toUnsignedLong(buffer.getInt(at + 4));
            // build_version_command : cmd, cmdsize, platform, minos, sdk (20 bytes, then the tools)
            if (command == LC_BUILD_VERSION && offset + 20 <= file.length && buffer.getInt(at + 8) == PLATFORM_MACOS) {
                return Optional.of(new BuildVersion(version(buffer.getInt(at + 12)), version(buffer.getInt(at + 16))));
            }
            // version_min_command : cmd, cmdsize, version, sdk (16 bytes)
            if (command == LC_VERSION_MIN_MACOSX && offset + 16 <= file.length) {
                return Optional.of(new BuildVersion(version(buffer.getInt(at + 8)), version(buffer.getInt(at + 12))));
            }
            if (size < 8) {
                break;
            }
            offset += size;
        }
        return Optional.empty();
    }

    /**
     * The versions of the {@code java} launcher of a JDK.
     */
    static Optional<BuildVersion> launcherBuildVersion(Path jdkHome) {
        Path launcher = jdkHome.resolve("bin").resolve("java");
        try {
            return Files.isRegularFile(launcher) ? buildVersion(Files.readAllBytes(launcher)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf(e, "Unable to read %s", launcher);
            return Optional.empty();
        }
    }

    /**
     * A version encoded as {@code xxxx.yy.zz} nibbles : {@code 11.0}, {@code 14.5}, {@code 10.15.7}.
     */
    static String version(int encoded) {
        int major = encoded >>> 16;
        int minor = (encoded >> 8) & 0xff;
        int patch = encoded & 0xff;
        return major + "." + minor + (patch != 0 ? "." + patch : "");
    }
}
