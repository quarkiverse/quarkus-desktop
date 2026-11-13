package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.awt.OpenRequest;
import io.quarkiverse.desktop.awt.OpenRequest.Source;

/**
 * The program arguments to open : the existing files of the file types (paths or {@code file:} URIs), the URIs of the
 * schemes. Without a display.
 */
class OpenArgumentsTest {

    @TempDir
    Path directory;

    @Test
    void filesAndUris() throws IOException {
        Path notes = Files.createFile(directory.resolve("Notes.ACME"));
        Path archive = Files.createFile(directory.resolve("backup.tar.gz"));
        Path text = Files.createFile(directory.resolve("readme.txt"));
        Files.createDirectory(directory.resolve("folder.acme"));
        OpenArguments arguments = new OpenArguments(Set.of("acme", "tar.gz"), Set.of("acme"));
        OpenRequest request = arguments.request(List.of("--verbose", notes.toString(), "", text.toString(),
                directory.resolve("folder.acme").toString(), directory.resolve("missing.acme").toString(),
                archive.toUri().toString(), "ACME://open/42?x=1", "https://example.com", "acme:bad uri", "-x:y"),
                Source.LAUNCH);
        assertEquals(Source.LAUNCH, request.source());
        assertEquals(List.of(notes.toAbsolutePath(), archive.toAbsolutePath()), request.files());
        assertEquals(List.of(URI.create("ACME://open/42?x=1")), request.uris());
    }

    @Test
    void relativePathsAreAbsolute() throws IOException {
        Path file = Files.createTempFile(Path.of("").toAbsolutePath().resolve("target"), "relative", ".acme");
        try {
            Path relative = Path.of("").toAbsolutePath().relativize(file);
            OpenRequest request = new OpenArguments(Set.of("acme"), Set.of()).request(List.of(relative.toString()),
                    Source.LAUNCH);
            assertEquals(List.of(file.toAbsolutePath().normalize()), request.files());
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void nothingWithoutTypes() throws IOException {
        Path notes = Files.createFile(directory.resolve("notes.acme"));
        OpenRequest request = new OpenArguments(Set.of(), Set.of()).request(List.of(notes.toString(), "acme://x"),
                Source.LAUNCH);
        assertTrue(request.isEmpty());
    }

    @Test
    void extensions() {
        OpenArguments arguments = new OpenArguments(Set.of("acme"), Set.of());
        assertTrue(arguments.hasExtension("a.ACME"));
        assertFalse(arguments.hasExtension(".acme"));
        assertFalse(arguments.hasExtension("acme"));
        assertFalse(arguments.hasExtension("a.acme2"));
    }
}
