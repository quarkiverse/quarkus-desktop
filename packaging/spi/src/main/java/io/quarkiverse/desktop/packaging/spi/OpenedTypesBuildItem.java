package io.quarkiverse.desktop.packaging.spi;

import java.util.Objects;
import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * What the packages of the application register it for : the extensions of the files that it opens
 * ({@code quarkus.desktop.packaging.file-types}) and its URL schemes ({@code quarkus.desktop.packaging.url-schemes}),
 * and the identifier of the application. The installers start the application with the file or the URL as an argument
 * on Windows and Linux : the extensions of the user interface toolkits recognize these arguments, and the identifier
 * names the running instance of the application. The Desktop Packaging extension produces it when it is enabled, with
 * or without file types and URL schemes.
 */
public final class OpenedTypesBuildItem extends SimpleBuildItem {

    private final Set<String> extensions;

    private final Set<String> schemes;

    private final String identifier;

    /**
     * @param extensions the extensions of the files, in lower case and without their dot
     * @param schemes the URL schemes, in lower case
     * @param identifier the identifier of the application ({@code org.acme.editor})
     */
    public OpenedTypesBuildItem(Set<String> extensions, Set<String> schemes, String identifier) {
        this.extensions = Set.copyOf(extensions);
        this.schemes = Set.copyOf(schemes);
        this.identifier = Objects.requireNonNull(identifier, "identifier");
    }

    /**
     * The extensions of the files that the application opens, in lower case and without their dot.
     */
    public Set<String> getExtensions() {
        return extensions;
    }

    /**
     * The URL schemes that the application opens, in lower case.
     */
    public Set<String> getSchemes() {
        return schemes;
    }

    /**
     * The identifier of the application ({@code org.acme.editor}).
     */
    public String getIdentifier() {
        return identifier;
    }
}
