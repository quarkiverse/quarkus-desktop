package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchTest {

    @Test
    void theArchitecturesOfTheJvm() {
        assertEquals(Arch.X64, Arch.of("amd64"));
        assertEquals(Arch.X64, Arch.of("AMD64"));
        assertEquals(Arch.X64, Arch.of("x86_64"));
        assertEquals(Arch.ARM64, Arch.of("aarch64"));
        assertEquals(Arch.ARM64, Arch.of("arm64"));
    }

    /**
     * The names of the architectures in the file names of the packages : the ones of each operating system.
     */
    @Test
    void theNamesOfTheArchitectures() {
        assertEquals(List.of("x64", "amd64", "x86_64", "x86_64"),
                List.of(Arch.X64.windows(), Arch.X64.debian(), Arch.X64.gnu(), Arch.X64.macos()));
        assertEquals(List.of("arm64", "arm64", "aarch64", "arm64"),
                List.of(Arch.ARM64.windows(), Arch.ARM64.debian(), Arch.ARM64.gnu(), Arch.ARM64.macos()));
    }

    /**
     * The packages are not labelled x64 for another architecture, which jpackage runs on in JVM mode.
     */
    @Test
    void theOtherArchitecturesAreRejected() {
        for (String osArch : List.of("arm", "riscv64", "ppc64le", "s390x", "x86", "")) {
            PackagingException e = assertThrows(PackagingException.class, () -> Arch.of(osArch), osArch);
            assertTrue(e.getMessage().contains("not for " + osArch + " :"), e.getMessage());
        }
    }

    /**
     * The application image is built on any architecture : only the packagers ask for it.
     */
    @Test
    void theArchitectureIsTheOneOfThePackagers(@TempDir Path project) throws IOException {
        String osArch = System.getProperty("os.arch");
        System.setProperty("os.arch", "riscv64");
        try {
            PackagingContext context = Packaging.context(TestConfig.defaults(), "Acme Editor", "1.0", "org.acme", project,
                    project, TargetOs.LINUX, Arch::host);
            assertThrows(PackagingException.class, context::arch);
        } finally {
            System.setProperty("os.arch", osArch);
        }
    }

    /**
     * The architecture of the native executable is read when a packager first asks for it, once.
     */
    @Test
    void theArchitectureIsReadOnce(@TempDir Path project) throws IOException {
        AtomicInteger reads = new AtomicInteger();
        PackagingContext context = Packaging.context(TestConfig.defaults(), "Acme Editor", "1.0", "org.acme", project,
                project, TargetOs.WINDOWS, () -> {
                    reads.incrementAndGet();
                    return Arch.ARM64;
                });
        assertEquals(0, reads.get());
        assertEquals(Arch.ARM64, context.arch());
        assertEquals(Arch.ARM64, context.arch());
        assertEquals(1, reads.get());
    }

    /**
     * An x64 executable built with an x64 GraalVM on Windows arm64, or in a container of another platform : the
     * packages have the architecture of the executable, not the one of the JVM of the build.
     */
    @Test
    void theArchitectureOfAnExecutable() {
        assertEquals(Optional.of(Arch.X64), Arch.ofExecutable(pe(0x8664)));
        assertEquals(Optional.of(Arch.ARM64), Arch.ofExecutable(pe(0xaa64)));
        assertEquals(Optional.of(Arch.X64), Arch.ofExecutable(elf(0x3e, ByteOrder.LITTLE_ENDIAN)));
        assertEquals(Optional.of(Arch.ARM64), Arch.ofExecutable(elf(0xb7, ByteOrder.LITTLE_ENDIAN)));
        assertEquals(Optional.of(Arch.ARM64), Arch.ofExecutable(elf(0xb7, ByteOrder.BIG_ENDIAN)));
        assertEquals(Optional.of(Arch.X64), Arch.ofExecutable(machO(0x01000007)));
        assertEquals(Optional.of(Arch.ARM64), Arch.ofExecutable(machO(0x0100000c)));
        // a universal Mach-O file of one architecture, as the libraries of JNA for macOS
        assertEquals(Optional.of(Arch.ARM64), Arch.ofExecutable(universal(1, 0x0100000c)));
        assertEquals(Optional.of(Arch.X64), Arch.ofExecutable(universal(1, 0x01000007)));
    }

    /**
     * The executable of another architecture fails the packagers that label it, as a build machine of another
     * architecture does.
     */
    @Test
    void theOtherArchitecturesOfAnExecutableAreRejected() {
        for (byte[] header : List.of(pe(0x14c), elf(0xf3, ByteOrder.LITTLE_ENDIAN), elf(0x16, ByteOrder.BIG_ENDIAN),
                machO(0x7), universal(1, 0x01000012))) {
            PackagingException e = assertThrows(PackagingException.class, () -> Arch.ofExecutable(header));
            assertTrue(e.getMessage().startsWith("The packages are built for x64 and arm64, not for the native executable"
                    + " of the "), e.getMessage());
            assertTrue(e.getMessage().contains("quarkus.desktop.packaging.formats"), e.getMessage());
        }
    }

    /**
     * The files without the headers of an executable : the packages get the architecture of the build.
     */
    @Test
    void theFilesThatAreNotExecutables() {
        assertEquals(Optional.empty(), Arch.ofExecutable(new byte[0]));
        assertEquals(Optional.empty(), Arch.ofExecutable(new byte[10]));
        assertEquals(Optional.empty(),
                Arch.ofExecutable("#!/bin/sh\necho Acme Editor\n".getBytes(StandardCharsets.US_ASCII)));
        // cut headers
        assertEquals(Optional.empty(), Arch.ofExecutable(Arrays.copyOf(pe(0x8664), 0x40)));
        assertEquals(Optional.empty(), Arch.ofExecutable(Arrays.copyOf(pe(0x8664), 0x45)));
        assertEquals(Optional.empty(), Arch.ofExecutable(Arrays.copyOf(elf(0x3e, ByteOrder.LITTLE_ENDIAN), 19)));
        // an offset of the PE header out of the file, or without its signature
        byte[] outside = pe(0x8664);
        ByteBuffer.wrap(outside).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c, 0x7ffffff0);
        assertEquals(Optional.empty(), Arch.ofExecutable(outside));
        ByteBuffer.wrap(outside).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c, -4);
        assertEquals(Optional.empty(), Arch.ofExecutable(outside));
        byte[] unsigned = pe(0x8664);
        unsigned[0x40] = 'N';
        assertEquals(Optional.empty(), Arch.ofExecutable(unsigned));
        // a universal Mach-O file of several architectures, which runs on both, and a Java class file
        assertEquals(Optional.empty(), Arch.ofExecutable(universal(2, 0x01000007)));
        assertEquals(Optional.empty(), Arch.ofExecutable(ByteBuffer.allocate(64).putInt(0, 0xcafebabe).putInt(4, 65)
                .array()));
    }

    @Test
    void theArchitectureOfAnExecutableFile(@TempDir Path directory) throws IOException {
        // after the headers of the PE file, the rest of the executable
        byte[] executable = Arrays.copyOf(pe(0xaa64), 1024 * 1024);
        assertEquals(Arch.ARM64, Arch.executable(Files.write(directory.resolve("acme-runner.exe"), executable)));
        // the launcher of the JDK of the build
        Path java = Path.of(System.getProperty("java.home"), "bin",
                TargetOs.host() == TargetOs.WINDOWS ? "java.exe" : "java");
        assertEquals(Arch.host(), Arch.executable(java));

        String osArch = System.getProperty("os.arch");
        System.setProperty("os.arch", "aarch64");
        try {
            assertEquals(Arch.ARM64, Arch.executable(Files.writeString(directory.resolve("acme-runner"), "not an executable")));
            assertEquals(Arch.ARM64, Arch.executable(directory.resolve("missing")));
        } finally {
            System.setProperty("os.arch", osArch);
        }
    }

    /**
     * The headers of a PE file : the DOS header, with the offset of the PE header, and the PE header.
     */
    private static byte[] pe(int machine) {
        ByteBuffer buffer = ByteBuffer.allocate(0x100).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(0, (short) 0x5a4d).putInt(0x3c, 0x40);
        buffer.putInt(0x40, 0x00004550).putShort(0x44, (short) machine);
        return buffer.array();
    }

    /**
     * The header of a 64-bit ELF file.
     */
    private static byte[] elf(int machine, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(order);
        buffer.put(new byte[] { 0x7f, 'E', 'L', 'F', 2, (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 2), 1 });
        buffer.putShort(16, (short) 2).putShort(18, (short) machine);
        return buffer.array();
    }

    /**
     * The header of a 64-bit Mach-O file.
     */
    private static byte[] machO(int cpuType) {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0xfeedfacf).putInt(4, cpuType)
                .putInt(12, 2).array();
    }

    /**
     * The header of a universal Mach-O file, big endian : its number of architectures, and the first one.
     */
    private static byte[] universal(int architectures, int cpuType) {
        return ByteBuffer.allocate(64).putInt(0, 0xcafebabe).putInt(4, architectures).putInt(8, cpuType)
                .putInt(16, 0x4000).array();
    }
}
