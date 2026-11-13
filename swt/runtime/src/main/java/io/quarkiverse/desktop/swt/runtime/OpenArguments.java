package io.quarkiverse.desktop.swt.runtime;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.quarkiverse.desktop.swt.OpenRequest;
import io.quarkiverse.desktop.swt.OpenRequest.Source;

/**
 * The program arguments that the application is asked to open : the files of its file types and the URIs of its URL
 * schemes, which the packages of the Desktop Packaging extension give it on Windows ({@code "%1"}) and Linux
 * ({@code %F}, {@code %U} : a local file may be a {@code file:} URI).
 */
final class OpenArguments {

    private final Set<String> extensions;

    private final Set<String> schemes;

    /**
     * @param extensions the extensions of the files, in lower case and without their dot : none without the Desktop
     *        Packaging extension
     * @param schemes the URL schemes, in lower case
     */
    OpenArguments(Collection<String> extensions, Collection<String> schemes) {
        this.extensions = Set.copyOf(extensions);
        this.schemes = Set.copyOf(schemes);
    }

    /**
     * The files and the URIs of the arguments, in their order : an existing regular file whose extension is one of the
     * file types (or its {@code file:} URI), absolute, or a URI of one of the schemes. The other arguments are skipped.
     *
     * @param arguments the program arguments
     * @param source where they come from
     */
    OpenRequest request(List<String> arguments, Source source) {
        List<Path> files = new ArrayList<>();
        List<URI> uris = new ArrayList<>();
        if (extensions.isEmpty() && schemes.isEmpty()) {
            return new OpenRequest(files, uris, source);
        }
        for (String argument : arguments) {
            if (argument == null || argument.isBlank()) {
                continue;
            }
            URI uri = uri(argument);
            if (uri != null) {
                String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                if (scheme.equals("file")) {
                    Path file = file(uri);
                    if (file != null) {
                        files.add(file);
                        continue;
                    }
                } else if (schemes.contains(scheme)) {
                    uris.add(uri);
                    continue;
                }
            }
            Path file = file(argument);
            if (file != null) {
                files.add(file);
            }
        }
        return new OpenRequest(files, uris, source);
    }

    /**
     * The argument as an absolute URI, {@code null} when it is not one. A scheme of one letter is a drive of Windows
     * ({@code C:/notes.txt}).
     */
    private static URI uri(String argument) {
        int colon = argument.indexOf(':');
        if (colon < 2) {
            return null;
        }
        try {
            URI uri = new URI(argument);
            return uri.getScheme() != null ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private Path file(URI uri) {
        try {
            return opened(Path.of(uri));
        } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException e) {
            return null;
        }
    }

    private Path file(String argument) {
        try {
            return opened(Path.of(argument));
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    /**
     * The file, absolute, when it is an existing regular file of one of the file types, else {@code null}.
     */
    private Path opened(Path path) {
        Path name = path.getFileName();
        if (name == null || !hasExtension(name.toString())) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        return Files.isRegularFile(absolute) ? absolute : null;
    }

    /**
     * Whether the file name ends with one of the extensions (also those with a dot, {@code tar.gz}), ignoring the case.
     */
    boolean hasExtension(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (name.length() > extension.length() + 1 && name.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }
}
