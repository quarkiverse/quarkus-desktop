package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileTreesTest {

    /**
     * The license file of a module, a link to the one of the repository : the package has the license, not a link.
     */
    @Test
    void aLinkIsCopiedAsTheFileThatItLinksTo(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("LICENSE"), "license");
        Path license = Files.createDirectories(directory.resolve("module")).resolve("LICENSE");
        link(license, Path.of("../LICENSE"));
        Path copyright = directory.resolve("root/usr/share/doc/acme-editor/copyright");
        FileTrees.copy(license, copyright);
        assertFalse(Files.isSymbolicLink(copyright));
        assertEquals("license", Files.readString(copyright));
    }

    /**
     * A content directory that is a link : its files are copied into the application image.
     */
    @Test
    void aLinkToADirectoryIsCopiedAsTheDirectory(@TempDir Path directory) throws IOException {
        Path tree = Files.createDirectories(directory.resolve("tree"));
        Files.writeString(tree.resolve("a.txt"), "a");
        Path content = directory.resolve("content");
        link(content, Path.of("tree"));
        Path image = Files.createDirectories(directory.resolve("image"));
        FileTrees.copy(content, image);
        assertTrue(Files.isRegularFile(image.resolve("a.txt"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("a", Files.readString(image.resolve("a.txt")));
    }

    /**
     * The links inside a directory stay links : the versions of a shared library, the frameworks of macOS.
     */
    @Test
    void theLinksOfADirectoryAreCopiedAsLinks(@TempDir Path directory) throws IOException {
        Path tree = Files.createDirectories(directory.resolve("tree"));
        Files.writeString(tree.resolve("libacme.so.1"), "library");
        link(tree.resolve("libacme.so"), Path.of("libacme.so.1"));
        try {
            Files.copy(tree.resolve("libacme.so"), directory.resolve("probe"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            // Windows, without the privilege of copying symbolic links
            Assumptions.abort("No copy of symbolic links : " + e);
        }
        Path image = directory.resolve("image");
        FileTrees.copy(tree, image);
        assertTrue(Files.isRegularFile(image.resolve("libacme.so.1"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(Path.of("libacme.so.1"), Files.readSymbolicLink(image.resolve("libacme.so")));
    }

    /**
     * A junction of Windows in an image (to try the application with a data directory) : the next build deletes the
     * junction, not the files of its target.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionIsDeletedWithoutItsTarget(@TempDir Path directory) throws IOException, InterruptedException {
        Path data = Files.createDirectories(directory.resolve("data"));
        Files.writeString(data.resolve("file.txt"), "data");
        Path image = Files.createDirectories(directory.resolve("image"));
        Files.writeString(image.resolve("application.exe"), "application");
        junction(image.resolve("data"), data);
        assertTrue(Files.isRegularFile(image.resolve("data/file.txt")));

        FileTrees.emptyDirectory(image);
        assertEquals(List.of(), FileTrees.files(image));
        assertEquals("data", Files.readString(data.resolve("file.txt")));

        // a junction at the root of the tree
        Path root = directory.resolve("root");
        junction(root, data);
        FileTrees.delete(root);
        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        assertEquals("data", Files.readString(data.resolve("file.txt")));
    }

    /**
     * Makes a junction, which needs no privilege.
     */
    private static void junction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows, without the privilege of creating symbolic links
            Assumptions.abort("No symbolic links : " + e);
        }
    }
}
