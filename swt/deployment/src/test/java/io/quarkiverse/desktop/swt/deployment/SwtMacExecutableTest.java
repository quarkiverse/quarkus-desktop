package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.swt.deployment.SwtPlatformBuildItem.WindowingSystem;

/**
 * The minimum macOS version and the SDK version of Mach-O files, and of the {@code java} launcher of a JDK.
 */
class SwtMacExecutableTest {

    @TempDir
    Path directory;

    @Test
    void buildVersion() {
        // LC_BUILD_VERSION, platform macOS, minos 11.0, sdk 14.5, after another load command
        byte[] buildVersion = machO(command(0x19, 72), command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0));
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion("11.0", "14.5")),
                SwtMacExecutable.buildVersion(buildVersion));
        assertEquals("-H:NativeLinkerOption=-Wl,-platform_version,macos,11.0,14.5",
                SwtMacExecutable.buildVersion(buildVersion).orElseThrow().linkerOption());
        // the older LC_VERSION_MIN_MACOSX
        byte[] versionMin = machO(command(0x24, 16, 0x000a0f07, 0x000b0300));
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion("10.15.7", "11.3")),
                SwtMacExecutable.buildVersion(versionMin));
        // another platform (iOS), no version command, not a Mach-O file
        assertEquals(Optional.empty(),
                SwtMacExecutable.buildVersion(machO(command(0x32, 24, 2, 0x00110000, 0x00110000, 0))));
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(machO(command(0x19, 72))));
        assertEquals(Optional.empty(),
                SwtMacExecutable.buildVersion("MZ not a Mach-O file".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(new byte[0]));
    }

    @Test
    void thinFiles() {
        // the 64-bit Mach-O files of both architectures : the CPU type of the header does not matter
        byte[] arm64 = machO(command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0));
        byte[] x86 = machO(0x01000007, command(0x32, 24, 1, 0x000a0f00, 0x000e0500, 0));
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion("11.0", "14.5")),
                SwtMacExecutable.buildVersion(arm64));
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion("10.15", "14.5")),
                SwtMacExecutable.buildVersion(x86));
    }

    @Test
    void universalFile() {
        byte[] arm64 = machO(command(0x32, 24, 1, 0x000c0000, 0x000f0000, 0));
        byte[] x86 = machO(0x01000007, command(0x32, 24, 1, 0x000a0f00, 0x000f0000, 0));
        ByteBuffer fat = ByteBuffer.allocate(8 + 2 * 20 + x86.length + arm64.length).order(ByteOrder.BIG_ENDIAN);
        fat.putInt(0xcafebabe).putInt(2);
        int offset = 8 + 2 * 20;
        fat.putInt(0x01000007).putInt(3).putInt(offset).putInt(x86.length).putInt(12);
        fat.putInt(0x0100000c).putInt(0).putInt(offset + x86.length).putInt(arm64.length).putInt(12);
        fat.put(x86).put(arm64);
        // the slice of the architecture of the build
        String minimum = "x86_64".equals(System.getProperty("os.arch")) ? "10.15" : "12.0";
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion(minimum, "15.0")),
                SwtMacExecutable.buildVersion(fat.array()));

        // fat_arch_64 entries : 64-bit offsets and sizes, a reserved field
        ByteBuffer fat64 = ByteBuffer.allocate(8 + 2 * 32 + x86.length + arm64.length).order(ByteOrder.BIG_ENDIAN);
        fat64.putInt(0xcafebabf).putInt(2);
        int offset64 = 8 + 2 * 32;
        fat64.putInt(0x01000007).putInt(3).putLong(offset64).putLong(x86.length).putInt(12).putInt(0);
        fat64.putInt(0x0100000c).putInt(0).putLong(offset64 + x86.length).putLong(arm64.length).putInt(12).putInt(0);
        fat64.put(x86).put(arm64);
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion(minimum, "15.0")),
                SwtMacExecutable.buildVersion(fat64.array()));
    }

    @Test
    void malformedFiles() {
        byte[] buildVersion = machO(command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0));
        // truncated in the sdk field of LC_BUILD_VERSION, or in its minos field
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(Arrays.copyOf(buildVersion, 32 + 18)));
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(Arrays.copyOf(buildVersion, 32 + 14)));
        // a load command size that would overflow the offset
        byte[] huge = machO(command(0x19, 16), command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0));
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(32 + 4, 0x7ffffff8);
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(huge));
        // a load command size below the size of a load command
        byte[] small = machO(command(0x19, 16), command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0));
        ByteBuffer.wrap(small).order(ByteOrder.LITTLE_ENDIAN).putInt(32 + 4, 4);
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(small));
        // a fat header whose entries point outside the file, or with more entries than the file holds
        ByteBuffer fat = ByteBuffer.allocate(8 + 20).order(ByteOrder.BIG_ENDIAN);
        fat.putInt(0xcafebabe).putInt(0x7fffffff);
        fat.putInt("x86_64".equals(System.getProperty("os.arch")) ? 0x01000007 : 0x0100000c).putInt(0).putInt(0x7ffffff0)
                .putInt(0x7ffffff0).putInt(12);
        assertEquals(Optional.empty(), SwtMacExecutable.buildVersion(fat.array()));
    }

    @Test
    void versionEncoding() {
        assertEquals("11.0", SwtMacExecutable.version(0x000b0000));
        assertEquals("14.5", SwtMacExecutable.version(0x000e0500));
        assertEquals("26.5", SwtMacExecutable.version(0x001a0500));
        assertEquals("10.15.7", SwtMacExecutable.version(0x000a0f07));
        assertEquals("0.0", SwtMacExecutable.version(0));
        // the major version takes the 16 high bits, unsigned
        assertEquals("65535.255.255", SwtMacExecutable.version(0xffffffff));
    }

    @Test
    void launcherOfAJdk() throws IOException {
        Path jdkHome = jdkHome(directory, machO(command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0)));
        assertEquals(Optional.of(new SwtMacExecutable.BuildVersion("11.0", "14.5")),
                SwtMacExecutable.launcherBuildVersion(jdkHome));
        // no launcher, a launcher that is not a Mach-O file (a JDK of another platform)
        assertEquals(Optional.empty(), SwtMacExecutable.launcherBuildVersion(directory.resolve("missing")));
        Path linuxJdk = jdkHome(directory.resolve("linux"), "\u007fELF".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(Optional.empty(), SwtMacExecutable.launcherBuildVersion(linuxJdk));
    }

    /**
     * The java launcher of the JDK running the tests (the JDK of the native build, GraalVM) has a build version,
     * written in the executables built for macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void launcherBuildVersion() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Optional<SwtMacExecutable.BuildVersion> version = SwtMacExecutable.launcherBuildVersion(javaHome);
        assertTrue(version.isPresent() && version.get().minimum().matches("\\d+\\.\\d+(\\.\\d+)?")
                && version.get().sdk().matches("\\d+\\.\\d+(\\.\\d+)?"), String.valueOf(version));
        assertEquals(List.of(version.get().linkerOption()),
                DesktopSwtProcessor.macExecutableArgs(WindowingSystem.COCOA, true, false, javaHome));
    }

    /**
     * A JDK home whose {@code bin/java} launcher has the given content.
     */
    static Path jdkHome(Path directory, byte[] launcher) throws IOException {
        Files.createDirectories(directory.resolve("bin"));
        Files.write(directory.resolve("bin").resolve("java"), launcher);
        return directory;
    }

    /**
     * A 64-bit Mach-O file for arm64 with the given load commands.
     */
    static byte[] machO(byte[]... commands) {
        return machO(0x0100000c, commands);
    }

    /**
     * A 64-bit Mach-O file for the given CPU type with the given load commands.
     */
    static byte[] machO(int cpuType, byte[]... commands) {
        int size = 0;
        for (byte[] command : commands) {
            size += command.length;
        }
        ByteBuffer file = ByteBuffer.allocate(32 + size).order(ByteOrder.LITTLE_ENDIAN);
        file.putInt(0xfeedfacf).putInt(cpuType).putInt(0).putInt(2).putInt(commands.length).putInt(size).putInt(0)
                .putInt(0);
        for (byte[] command : commands) {
            file.put(command);
        }
        return file.array();
    }

    /**
     * A load command : its type, size and first words (padded with zeros to its size).
     */
    static byte[] command(int type, int size, int... words) {
        ByteBuffer command = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(type).putInt(size);
        for (int word : words) {
            command.putInt(word);
        }
        return command.array();
    }
}
