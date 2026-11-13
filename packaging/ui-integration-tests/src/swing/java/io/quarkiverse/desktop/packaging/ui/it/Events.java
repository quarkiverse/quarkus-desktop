package io.quarkiverse.desktop.packaging.ui.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The events of the packaged application, one per line in {@code ~/packaging-ui-it/events.txt}, which the build
 * workflow reads : {@code shown <toolkit> <mode>} once the window is shown, and {@code open <source> <file names>
 * <URIs>} for each open request. The application closes its window, and stops, when the file
 * {@code ~/packaging-ui-it/quit} exists, or after three minutes.
 */
final class Events {

    static final Path DIRECTORY = Path.of(System.getProperty("user.home"), "packaging-ui-it");

    static final Path QUIT = DIRECTORY.resolve("quit");

    static final long LIFETIME_MILLIS = 180_000;

    private Events() {
    }

    static synchronized void log(String line) {
        try {
            Files.createDirectories(DIRECTORY);
            Files.writeString(DIRECTORY.resolve("events.txt"), line + System.lineSeparator(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String open(String source, List<Path> files, List<URI> uris) {
        return "open " + source + " " + files.stream().map(file -> file.getFileName().toString()).collect(Collectors.toList())
                + " " + uris;
    }
}
