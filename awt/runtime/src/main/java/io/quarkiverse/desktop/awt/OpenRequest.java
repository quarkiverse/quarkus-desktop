package io.quarkiverse.desktop.awt;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Fired on the event dispatch thread when the application is asked to open files or URIs : the files of the types and
 * the URLs of the schemes that its packages register ({@code quarkus.desktop.packaging.file-types} and
 * {@code url-schemes}), given as program arguments on Windows and Linux, sent by macOS, or forwarded by a second launch
 * of the application ({@code quarkus.desktop.awt.single-instance}).
 * <p>
 * Fired after {@link DesktopStartupEvent} (the main window exists), only to an application that observes it and has a
 * user interface. The program arguments are recognized with the Desktop Packaging extension only : an existing regular
 * file whose extension is one of the file types (or its {@code file:} URI), or a URI of one of the schemes. The other
 * arguments are left to the application.
 *
 * <pre>
 * &#64;Singleton
 * public class Documents {
 *
 *     &#64;Inject
 *     MainWindow window;
 *
 *     void open(&#64;Observes OpenRequest request) {
 *         request.files().forEach(window::openDocument);
 *         if (request.source() == OpenRequest.Source.ANOTHER_LAUNCH) {
 *             window.setExtendedState(window.getExtendedState() &amp; ~Frame.ICONIFIED);
 *             window.toFront();
 *             window.requestFocus();
 *         }
 *     }
 * }
 * </pre>
 */
public final class OpenRequest {

    /**
     * Where a request comes from.
     */
    public enum Source {
        /**
         * The program arguments of the application : fired once, when they name files or URIs to open.
         */
        LAUNCH,
        /**
         * The system, while the application runs or when it starts : the Finder and the Dock of macOS (Apple Events).
         */
        SYSTEM,
        /**
         * A second launch of the application, which forwarded its arguments and exited
         * ({@code quarkus.desktop.awt.single-instance}) : maybe no files nor URIs, the user only launched it again.
         */
        ANOTHER_LAUNCH
    }

    private final List<Path> files;

    private final List<URI> uris;

    private final Source source;

    /**
     * @param files the files to open
     * @param uris the URIs to open
     * @param source where the request comes from
     */
    public OpenRequest(List<Path> files, List<URI> uris, Source source) {
        this.files = List.copyOf(files);
        this.uris = List.copyOf(uris);
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * The files to open, absolute.
     */
    public List<Path> files() {
        return files;
    }

    /**
     * The URIs to open : the URLs of the schemes of the application.
     */
    public List<URI> uris() {
        return uris;
    }

    /**
     * Where the request comes from.
     */
    public Source source() {
        return source;
    }

    /**
     * Whether the request has neither files nor URIs.
     */
    public boolean isEmpty() {
        return files.isEmpty() && uris.isEmpty();
    }

    @Override
    public String toString() {
        return "OpenRequest[source=" + source + ", files=" + files + ", uris=" + uris + "]";
    }
}
