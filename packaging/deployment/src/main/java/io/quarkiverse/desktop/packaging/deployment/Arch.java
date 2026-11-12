package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.jboss.logging.Logger;

/**
 * The architecture of the packaged application : the one of its native executable, or of the build in JVM mode, with
 * the name that each packaging tool gives it.
 */
public enum Arch {

    X64("x64", "amd64", "x86_64", "x86_64"),
    ARM64("arm64", "arm64", "aarch64", "arm64");

    private final String windows;

    private final String debian;

    private final String gnu;

    private final String macos;

    Arch(String windows, String debian, String gnu, String macos) {
        this.windows = windows;
        this.debian = debian;
        this.gnu = gnu;
        this.macos = macos;
    }

    /**
     * The architecture of the JVM of the build.
     *
     * @throws PackagingException for an architecture other than x64 and arm64
     */
    public static Arch host() {
        return of(System.getProperty("os.arch", ""));
    }

    /**
     * The architecture of a value of {@code os.arch}.
     *
     * @throws PackagingException for an architecture other than x64 and arm64
     */
    static Arch of(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> X64;
            case "aarch64", "arm64" -> ARM64;
            default -> throw new PackagingException("The packages are built for x64 and arm64, not for " + osArch
                    + " : build them on an x64 or arm64 machine, or keep only the directory format in"
                    + " quarkus.desktop.packaging.formats");
        };
    }

    private static final Logger LOGGER = Logger.getLogger(Arch.class);

    /**
     * The bytes of an executable that have its machine : the PE header of an executable that a linker wrote is within
     * its first kilobyte.
     */
    private static final int HEADERS = 4096;

    /**
     * The architecture of a native executable, from its headers : the one of the native image builder, which may not
     * be the one of the JVM of the build (an x64 GraalVM on Windows arm64, a container of another platform). The one
     * of the build for a file without the headers of an executable.
     *
     * @throws PackagingException for an executable of another architecture than x64 and arm64
     */
    static Arch executable(Path file) {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(HEADERS);
        } catch (IOException e) {
            LOGGER.debugf(e, "Unable to read %s : the packages are built for the architecture of the build", file);
            return host();
        }
        Optional<Arch> arch = ofExecutable(header);
        if (arch.isEmpty()) {
            LOGGER.debugf("%s has no headers of a PE, ELF or Mach-O file : the packages are built for the architecture"
                    + " of the build", file);
            return host();
        }
        LOGGER.debugf("The packages are built for %s, the architecture of %s", arch.get(), file);
        return arch.get();
    }

    /**
     * The architecture of an executable, from its first bytes : the machine of a PE file (Windows), of an ELF file
     * (Linux) or of a 64-bit Mach-O file (macOS), universal or not.
     *
     * @return empty for another file (a universal Mach-O file of several architectures), or for headers that are cut
     * @throws PackagingException for an executable of another architecture than x64 and arm64
     */
    static Optional<Arch> ofExecutable(byte[] header) {
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (header.length >= 0x40 && buffer.getShort(0) == 0x5a4d) {
            // MZ : the PE signature is at the offset of e_lfanew, followed by the machine
            int pe = buffer.getInt(0x3c);
            if (pe < 0 || pe > header.length - 6 || buffer.getInt(pe) != 0x00004550) {
                return Optional.empty();
            }
            int machine = buffer.getShort(pe + 4) & 0xffff;
            return Optional.of(machine == 0x8664 ? X64 : machine == 0xaa64 ? ARM64 : unsupported("PE", machine));
        }
        if (header.length >= 20 && buffer.getInt(0) == 0x464c457f) {
            // ELF : e_machine, in the byte order of e_ident[EI_DATA]
            buffer.order(header[5] == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            int machine = buffer.getShort(18) & 0xffff;
            return Optional.of(machine == 0x3e ? X64 : machine == 0xb7 ? ARM64 : unsupported("ELF", machine));
        }
        if (header.length >= 8 && buffer.getInt(0) == 0xfeedfacf) {
            // a 64-bit Mach-O file : its CPU type
            return Optional.of(machO(buffer.getInt(4)));
        }
        buffer.order(ByteOrder.BIG_ENDIAN);
        if (header.length >= 12 && (buffer.getInt(0) == 0xcafebabe || buffer.getInt(0) == 0xcafebabf)
                && buffer.getInt(4) == 1) {
            // a universal Mach-O file of one architecture (a Java class file has its version there, 45 or more) : the
            // CPU type of its first fat_arch
            return Optional.of(machO(buffer.getInt(8)));
        }
        return Optional.empty();
    }

    /**
     * The architecture of a CPU type of a Mach-O file.
     *
     * @throws PackagingException for another architecture than x64 and arm64
     */
    private static Arch machO(int cpuType) {
        return cpuType == 0x01000007 ? X64 : cpuType == 0x0100000c ? ARM64 : unsupported("Mach-O", cpuType);
    }

    /**
     * Fails for an executable of another architecture than x64 and arm64.
     *
     * @param format the format of the executable
     * @param machine its machine or CPU type
     */
    private static Arch unsupported(String format, int machine) {
        throw new PackagingException("The packages are built for x64 and arm64, not for the native executable of the "
                + format + " machine 0x" + Integer.toHexString(machine) + " : build it for x64 or arm64, or keep only"
                + " the directory format in quarkus.desktop.packaging.formats");
    }

    /**
     * {@code x64} or {@code arm64} : WiX, MSIX, Inno Setup.
     */
    public String windows() {
        return windows;
    }

    /**
     * {@code amd64} or {@code arm64} : Debian packages, snaps.
     */
    public String debian() {
        return debian;
    }

    /**
     * {@code x86_64} or {@code aarch64} : RPM packages, AppImage, Flatpak.
     */
    public String gnu() {
        return gnu;
    }

    /**
     * {@code x86_64} or {@code arm64}, as {@code uname -m} and {@code lipo} name them : macOS disk images and installer
     * packages.
     */
    public String macos() {
        return macos;
    }
}
