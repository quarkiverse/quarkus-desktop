package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.deployment.windows.PeImports;
import io.quarkiverse.desktop.packaging.deployment.windows.PeImportsTest;

/**
 * The name of the Windows native executable in the application image : the libraries that native-image links with the
 * executable by its file name import the name of the application, or the executable keeps the name of the build.
 */
class WindowsExecutableNameTest {

    /**
     * Writes the executable of the application image under its new name, and libraries.
     */
    static void image(Path directory, String launcher, Map<String, byte[]> libraries) throws IOException {
        Files.writeString(directory.resolve(launcher), "executable");
        for (Map.Entry<String, byte[]> library : libraries.entrySet()) {
            Files.write(directory.resolve(library.getKey()), library.getValue());
        }
    }

    /**
     * Whether the file system takes the names of these tests that are not ASCII : not in the POSIX locale.
     */
    static boolean nonAsciiFileNames(Path directory) {
        try {
            directory.resolve("Éditeur.exe");
            return true;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    @Test
    void theLibrariesImportTheNameOfTheApplication(@TempDir Path directory) throws IOException {
        byte[] jvm = PeImportsTest.library(0x200, "acme-runner.exe");
        byte[] awt = "awt.dll".getBytes(StandardCharsets.US_ASCII);
        image(directory, "Acme Editor.exe", Map.of("jvm.dll", jvm, "awt.dll", awt));
        assertEquals("Acme Editor.exe",
                NativeApplicationImage.renameWindowsExecutable(directory, "acme-runner.exe", "Acme Editor.exe"));
        assertEquals(List.of("KERNEL32.dll", "Acme Editor.exe"),
                PeImports.names(Files.readAllBytes(directory.resolve("jvm.dll"))));
        // a library that does not import the executable is not changed
        assertArrayEquals(awt, Files.readAllBytes(directory.resolve("awt.dll")));
        assertTrue(Files.isRegularFile(directory.resolve("Acme Editor.exe")));
        assertFalse(Files.exists(directory.resolve("acme-runner.exe")));
    }

    /**
     * A name of the application that is not ASCII cannot be imported : the executable keeps the name of the build.
     */
    @Test
    void theNameOfTheApplicationIsNotAscii(@TempDir Path directory) throws IOException {
        assumeTrue(nonAsciiFileNames(directory), "The file system does not take a name that is not ASCII");
        byte[] jvm = PeImportsTest.library(0x200, "acme-runner.exe");
        image(directory, "Éditeur.exe", Map.of("jvm.dll", jvm));
        assertEquals("acme-runner.exe",
                NativeApplicationImage.renameWindowsExecutable(directory, "acme-runner.exe", "Éditeur.exe"));
        assertTrue(Files.isRegularFile(directory.resolve("acme-runner.exe")));
        assertFalse(Files.exists(directory.resolve("Éditeur.exe")));
        assertArrayEquals(jvm, Files.readAllBytes(directory.resolve("jvm.dll")));
    }

    /**
     * The libraries import the new name all or none : the executable keeps the name of the build when one of them has
     * no room for it, and the other one is not changed either.
     */
    @Test
    void theLibrariesAreChangedAllOrNone(@TempDir Path directory) throws IOException {
        byte[] room = PeImportsTest.library(0x200, "acme-runner.exe");
        // no room after the content of the section
        byte[] full = PeImportsTest.library(100, "acme-runner.exe");
        String launcher = "Quarkus Desktop Showcase.exe";
        image(directory, launcher, Map.of("a.dll", room, "b.dll", full));
        assertEquals("acme-runner.exe", NativeApplicationImage.renameWindowsExecutable(directory, "acme-runner.exe",
                launcher));
        assertArrayEquals(room, Files.readAllBytes(directory.resolve("a.dll")));
        assertArrayEquals(full, Files.readAllBytes(directory.resolve("b.dll")));
        assertTrue(Files.isRegularFile(directory.resolve("acme-runner.exe")));
        assertFalse(Files.exists(directory.resolve(launcher)));
    }

    /**
     * The name of the build in another case is the same file name for Windows : nothing changes.
     */
    @Test
    void theNameOfTheBuildInAnotherCase(@TempDir Path directory) throws IOException {
        byte[] jvm = PeImportsTest.library(0x200, "acme-runner.exe");
        image(directory, "ACME-RUNNER.EXE", Map.of("jvm.dll", jvm));
        assertEquals("ACME-RUNNER.EXE",
                NativeApplicationImage.renameWindowsExecutable(directory, "acme-runner.exe", "ACME-RUNNER.EXE"));
        assertArrayEquals(jvm, Files.readAllBytes(directory.resolve("jvm.dll")));
        assertTrue(Files.isRegularFile(directory.resolve("ACME-RUNNER.EXE")));
    }

    /**
     * A name of the build that is not ASCII, which the linker writes in UTF-8 : the libraries import the ASCII name of
     * the application, which loads whatever the ANSI code page of the machine.
     */
    @Test
    void theNameOfTheBuildIsNotAscii(@TempDir Path directory) throws IOException {
        String built = "Éditeur-runner.exe";
        image(directory, "Editor.exe", Map.of("jvm.dll", PeImportsTest.library(0x200, built)));
        assertEquals("Editor.exe", NativeApplicationImage.renameWindowsExecutable(directory, built, "Editor.exe"));
        assertEquals(List.of("KERNEL32.dll", "Editor.exe"), PeImports.names(Files.readAllBytes(directory.resolve("jvm.dll"))));
        assertTrue(Files.isRegularFile(directory.resolve("Editor.exe")));
    }

    /**
     * A name of the build that is not ASCII, in an encoding that is not known : the executable keeps the name of the
     * build, which the libraries may import in that encoding.
     */
    @Test
    void theNameOfTheBuildIsInAnUnknownEncoding(@TempDir Path directory) throws IOException {
        assumeTrue(nonAsciiFileNames(directory), "The file system does not take a name that is not ASCII");
        String built = "Éditeur-runner.exe";
        // the E with an acute accent of the code page 437
        byte[] name = "?diteur-runner.exe".getBytes(StandardCharsets.US_ASCII);
        name[0] = (byte) 0x90;
        byte[] jvm = PeImportsTest.library(0x200, name);
        image(directory, "Editor.exe", Map.of("jvm.dll", jvm));
        assertEquals(built, NativeApplicationImage.renameWindowsExecutable(directory, built, "Editor.exe"));
        assertTrue(Files.isRegularFile(directory.resolve(built)));
        assertFalse(Files.exists(directory.resolve("Editor.exe")));
        assertArrayEquals(jvm, Files.readAllBytes(directory.resolve("jvm.dll")));
    }

    /**
     * The image of a Windows native executable has the libraries of the build directory, which import the executable
     * under the name of the application : the libraries of the build directory are not changed.
     */
    @Test
    void theImageOfANativeExecutableWithItsLibraries(@TempDir Path project) throws IOException {
        Path target = Files.createDirectories(project.resolve("target"));
        Files.writeString(target.resolve("acme-runner.exe"), "acme-runner.exe");
        byte[] jvm = PeImportsTest.library(0x200, "acme-runner.exe");
        Files.write(target.resolve("jvm.dll"), jvm);
        PackagingContext context = ApplicationImageTest.context(project, TargetOs.WINDOWS, Map.of());
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner.exe"), List.of(),
                List.of());
        assertEquals("Acme Editor.exe", image.launcher());
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("jvm.dll")), FileTrees.files(image.root()));
        assertEquals(List.of("KERNEL32.dll", "Acme Editor.exe"),
                PeImports.names(Files.readAllBytes(image.root().resolve("jvm.dll"))));
        assertArrayEquals(jvm, Files.readAllBytes(target.resolve("jvm.dll")));
    }
}
