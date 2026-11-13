package io.quarkiverse.desktop.packaging.deployment;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the packages register the application for : the types of the files that it opens, and its URL schemes.
 *
 * @param fileTypes the types of files, in the order of their names
 * @param urlSchemes the URL schemes, in lower case
 */
public record Associations(List<FileType> fileTypes, List<String> urlSchemes) {

    /**
     * No file type and no URL scheme.
     */
    public static final Associations NONE = new Associations(List.of(), List.of());

    /**
     * The schemes that the operating systems keep for themselves or for the browsers.
     */
    private static final Set<String> RESERVED_SCHEMES = Set.of("http", "https", "file", "ftp", "mailto", "data",
            "javascript", "about", "blob", "ms-settings", "shell");

    /**
     * A type of files that the application opens.
     *
     * @param name the name of the type in the configuration ({@code document})
     * @param extensions the extensions of the files, in lower case and without their dot ({@code acme})
     * @param mimeType the MIME type of the files ({@code application/x-acme-editor-document})
     * @param description the description of the type, which the file managers show ({@code Acme Editor document})
     * @param icon the icon of the files, a PNG file : the icon of the application when empty
     */
    public record FileType(String name, List<String> extensions, String mimeType, String description,
            Optional<Path> icon) {

        /**
         * The identifier of the type for the operating systems, from the identifier of the application : the
         * programmatic identifier of Windows ({@code org.acme.editor.document}), the uniform type identifier of macOS.
         */
        public String identifier(AppInfo app) {
            return app.identifier() + "." + name;
        }
    }

    /**
     * Whether the packages register nothing.
     */
    public boolean isEmpty() {
        return fileTypes.isEmpty() && urlSchemes.isEmpty();
    }

    /**
     * The extensions of every type, in lower case and without their dot.
     */
    public Set<String> extensions() {
        Set<String> extensions = new LinkedHashSet<>();
        fileTypes.forEach(type -> extensions.addAll(type.extensions()));
        return extensions;
    }

    /**
     * The file types and URL schemes of the configuration, checked.
     *
     * @param packageName the name of the Linux packages, which the default MIME types derive from
     * @param name the name of the application, which the default descriptions derive from
     * @param projectDirectory the directory of the relative paths of the icons
     * @throws PackagingException for a value that a format rejects : a name, an extension, a MIME type or a URL scheme
     *         with other characters than the formats allow, an extension in two types, a reserved scheme, an icon that
     *         does not exist
     */
    static Associations of(DesktopPackagingConfig config, String packageName, String name, Path projectDirectory) {
        List<FileType> types = new ArrayList<>();
        Set<String> allExtensions = new HashSet<>();
        for (Map.Entry<String, DesktopPackagingConfig.FileType> entry : new TreeMap<>(config.fileTypes()).entrySet()) {
            String type = entry.getKey();
            String property = "quarkus.desktop.packaging.file-types." + type;
            if (!type.matches("[a-z0-9][a-z0-9-]*")) {
                throw new PackagingException(property + " : the name of a file type has lowercase letters, digits and"
                        + " dashes, and starts with a letter or a digit");
            }
            DesktopPackagingConfig.FileType fileType = entry.getValue();
            List<String> extensions = new ArrayList<>();
            for (String extension : fileType.extensions()) {
                String value = extension.strip();
                value = (value.startsWith(".") ? value.substring(1) : value).toLowerCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                if (!value.matches("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9_-]+)*")) {
                    throw new PackagingException(property + ".extensions : " + extension + " is not an extension of file"
                            + " names (letters, digits, dashes and underscores, such as acme)");
                }
                if (!allExtensions.add(value)) {
                    throw new PackagingException(property + ".extensions : the extension " + value + " is in another"
                            + " file type too : give it to one type only");
                }
                extensions.add(value);
            }
            if (extensions.isEmpty()) {
                throw new PackagingException(property + ".extensions : a file type has at least one extension");
            }
            String mimeType = fileType.mimeType().map(String::strip).filter(value -> !value.isEmpty())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .orElse("application/x-" + packageName.replace('.', '-').replace('+', '-') + "-" + type);
            if (!mimeType.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
                throw new PackagingException(property + ".mime-type=" + mimeType + " : a MIME type is a type and a subtype"
                        + " such as application/x-acme-document");
            }
            String description = fileType.description().map(String::strip).filter(value -> !value.isEmpty())
                    .orElse(name + " " + type.replace('-', ' '));
            Optional<Path> icon = fileType.icon().map(String::strip).filter(value -> !value.isEmpty()).map(value -> {
                try {
                    return projectDirectory.resolve(value);
                } catch (InvalidPathException e) {
                    throw new PackagingException(property + ".icon=\"" + value + "\" : not a valid path ("
                            + e.getReason() + ")");
                }
            });
            if (icon.isPresent() && !Files.isRegularFile(icon.get())) {
                throw new PackagingException(property + ".icon : " + icon.get() + " not found");
            }
            types.add(new FileType(type, List.copyOf(extensions), mimeType, description, icon));
        }
        Set<String> schemes = new LinkedHashSet<>();
        for (String scheme : config.urlSchemes().orElse(List.of())) {
            String value = scheme.strip().toLowerCase(Locale.ROOT);
            if (value.endsWith("://")) {
                value = value.substring(0, value.length() - 3);
            } else if (value.endsWith(":")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("[a-z][a-z0-9+.-]*")) {
                throw new PackagingException("quarkus.desktop.packaging.url-schemes : " + scheme + " is not a URL scheme"
                        + " (a letter, then letters, digits, plus signs, dots and dashes, such as acme)");
            }
            if (RESERVED_SCHEMES.contains(value)) {
                throw new PackagingException("quarkus.desktop.packaging.url-schemes : the scheme " + value + " belongs to"
                        + " the operating system or to the browsers : choose a scheme of the application, such as acme");
            }
            schemes.add(value);
        }
        return new Associations(List.copyOf(types), List.copyOf(schemes));
    }
}
