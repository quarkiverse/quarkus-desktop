package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Associations;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * What the Linux packages share : the desktop entry of the application, its icons in the icon theme, its AppStream
 * metadata, the MIME types of its files, and the tree of the files that the Debian and RPM packages install.
 */
final class LinuxDesktop {

    private LinuxDesktop() {
    }

    /**
     * The desktop entry of the application : what the menus of the desktops show and start. Its window class
     * ({@code StartupWMClass}) is the one of the configuration, else the name that the application gives itself at run
     * time ({@link AppInfo#applicationId}), which its toolkit gives to its windows. With file types or URL schemes, it
     * has their MIME types ({@code MimeType}), and its command takes the files or the URLs (see {@link #fieldCode}).
     *
     * @param exec the command of the application, without arguments
     * @param icon the name of its icon in the icon theme, or the path of the icon file
     */
    static String desktopEntry(AppInfo app, DesktopPackagingConfig.Linux config, String exec, String icon) {
        StringBuilder entry = new StringBuilder();
        entry.append("[Desktop Entry]\n");
        entry.append("Type=Application\n");
        entry.append("Name=").append(value(app.name())).append('\n');
        // a comment that repeats the name is redundant (the description defaults to the name)
        if (!value(app.description()).equalsIgnoreCase(value(app.name()))) {
            entry.append("Comment=").append(value(app.description())).append('\n');
        }
        entry.append("Exec=").append(exec).append(fieldCode(app.associations())).append('\n');
        entry.append("Icon=").append(icon).append('\n');
        entry.append("Terminal=false\n");
        entry.append("Categories=").append(String.join(";", config.categories())).append(";\n");
        List<String> mimeTypes = mimeTypes(app.associations());
        if (!mimeTypes.isEmpty()) {
            entry.append("MimeType=").append(String.join(";", mimeTypes)).append(";\n");
        }
        config.startupWmClass().or(app::applicationId).ifPresent(windowClass -> entry.append("StartupWMClass=")
                .append(value(windowClass)).append('\n'));
        return entry.toString();
    }

    /**
     * The field code of the command of the desktop entry, after a space : {@code %F} (several files) with file types
     * only, {@code %U} (several URLs) with URL schemes, which passes the local files as {@code file://} URIs (a command
     * has one such code) ; nothing without associations.
     */
    static String fieldCode(Associations associations) {
        if (!associations.urlSchemes().isEmpty()) {
            return " %U";
        }
        return associations.fileTypes().isEmpty() ? "" : " %F";
    }

    /**
     * The MIME types of the desktop entry : the ones of the file types, then {@code x-scheme-handler/<scheme>} for each
     * URL scheme.
     */
    static List<String> mimeTypes(Associations associations) {
        Set<String> mimeTypes = new LinkedHashSet<>();
        associations.fileTypes().forEach(type -> mimeTypes.add(type.mimeType()));
        associations.urlSchemes().forEach(scheme -> mimeTypes.add("x-scheme-handler/" + scheme));
        return new ArrayList<>(mimeTypes);
    }

    /**
     * The name of the icon of the files of a type in the icon theme, which their MIME type names ({@code <icon>}) :
     * after the package name, or after the application ID for Flatpak, which only exports the files named after it.
     *
     * @param prefix the package name or the application ID
     */
    static String mimeIconName(String prefix, Associations.FileType type) {
        return prefix + "-" + type.name();
    }

    /**
     * The icon of the files of a type in a size, relative to the {@code share} directory.
     *
     * @param prefix the prefix of the name of the icon (see {@link #mimeIconName})
     */
    static String mimeIconFile(String prefix, Associations.FileType type, int size) {
        return "icons/hicolor/" + size + "x" + size + "/mimetypes/" + mimeIconName(prefix, type) + ".png";
    }

    /**
     * The shared MIME-info file of the file types ({@code share/mime/packages/<name>.xml}) : their MIME types, with
     * their descriptions, their icons and the patterns of their file names, which {@code update-mime-database} merges
     * with the types of the system.
     *
     * @param iconPrefix the prefix of the names of the icons (see {@link #mimeIconName})
     * @return the file, empty without file types
     */
    static String mimeInfo(AppInfo app, String iconPrefix) {
        if (app.associations().fileTypes().isEmpty()) {
            return "";
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">\n");
        for (Associations.FileType type : app.associations().fileTypes()) {
            xml.append("  <mime-type type=\"").append(Xml.escape(type.mimeType())).append("\">\n");
            xml.append("    <comment>").append(Xml.escape(type.description())).append("</comment>\n");
            if (type.icon().isPresent()) {
                xml.append("    <icon name=\"").append(Xml.escape(mimeIconName(iconPrefix, type))).append("\"/>\n");
            }
            for (String extension : type.extensions()) {
                xml.append("    <glob pattern=\"*.").append(Xml.escape(extension)).append("\"/>\n");
            }
            xml.append("  </mime-type>\n");
        }
        xml.append("</mime-info>\n");
        return xml.toString();
    }

    /**
     * Writes the MIME types of the file types of the application in a {@code share} directory : the shared MIME-info
     * file in {@code mime/packages}, and the icons of the types that have one in {@code icons/hicolor} (see
     * {@link #mimeIconFile}). Nothing without file types.
     *
     * @param share the {@code share} directory
     * @param name the base name of the shared MIME-info file, and the prefix of the names of the icons
     * @return the written files, relative to the {@code share} directory, with {@code /} separators
     */
    static List<String> writeMimeTypes(AppInfo app, Path share, String name, int... sizes) throws IOException {
        List<String> files = mimeFiles(app, name, sizes);
        if (files.isEmpty()) {
            return files;
        }
        FileTrees.write(share.resolve(files.get(0)), mimeInfo(app, name));
        for (Associations.FileType type : app.associations().fileTypes()) {
            if (type.icon().isPresent()) {
                IconSet icons = IconSet.of(type.icon().get());
                for (int size : sizes) {
                    icons.writePng(share.resolve(mimeIconFile(name, type, size)), size);
                }
            }
        }
        return files;
    }

    /**
     * The files that {@link #writeMimeTypes} writes, relative to the {@code share} directory : the shared MIME-info
     * file first, then the icons.
     *
     * @param name the base name of the shared MIME-info file, and the prefix of the names of the icons
     */
    static List<String> mimeFiles(AppInfo app, String name, int... sizes) {
        List<String> files = new ArrayList<>();
        if (app.associations().fileTypes().isEmpty()) {
            return files;
        }
        files.add("mime/packages/" + name + ".xml");
        for (Associations.FileType type : app.associations().fileTypes()) {
            if (type.icon().isPresent()) {
                for (int size : sizes) {
                    files.add(mimeIconFile(name, type, size));
                }
            }
        }
        return files;
    }

    /**
     * A text as the value of a key of a desktop entry : on one line.
     */
    private static String value(String text) {
        return text.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Writes the icons of the application in an icon theme directory ({@code share/icons/hicolor}).
     *
     * @param name the name of the icon, without extension
     */
    static void icons(IconSet icons, Path hicolor, String name, int... sizes) throws IOException {
        for (int size : sizes) {
            icons.writePng(hicolor.resolve(size + "x" + size).resolve("apps").resolve(name + ".png"), size);
        }
    }

    /**
     * The directory where the Debian and RPM packages install the application : {@code /opt/acme-editor}.
     */
    static String installPath(PackagingContext context) {
        return installPath(context.config().linux().installDirectory(), context.app().packageName());
    }

    /**
     * The directory where the Debian and RPM packages install the application : the installation directory of the
     * configuration followed by the package name. The desktop entry, the spec file and the tree of the package have it
     * as it is : its names have none of the characters that they would have to quote.
     *
     * @param directory the installation directory ({@code quarkus.desktop.packaging.linux.install-directory})
     * @throws PackagingException when the directory is {@code /}, or when one of its names is {@code .}, {@code ..}
     *         or has other characters than letters, digits, dots, dashes, underscores and plus signs
     */
    static String installPath(String directory, String packageName) {
        String trimmed = directory.strip().replaceAll("/+$", "");
        String path = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        for (String element : path.substring(1).split("/", -1)) {
            if (!element.matches("[A-Za-z0-9._+-]+") || element.equals(".") || element.equals("..")) {
                throw new PackagingException("quarkus.desktop.packaging.linux.install-directory=" + directory + " : the"
                        + " installation directory is an absolute directory other than /, such as /opt, whose names"
                        + " have letters, digits, dots, dashes, underscores and plus signs");
            }
        }
        return path + "/" + packageName;
    }

    /**
     * Builds the tree of the files of a Debian or RPM package : the application image in its installation directory,
     * its command in {@code /usr/bin} (a link to the launcher), its desktop entry, its icons, and the MIME types of its
     * file types. The triggers of the packages that own their directories update the databases of the desktop entries,
     * of the MIME types and of the icons ({@code desktop-file-utils}, {@code shared-mime-info},
     * {@code hicolor-icon-theme}) : the packages have no scripts.
     *
     * @param root the directory of the tree, which stands for {@code /}
     */
    static void stage(PackagingContext context, ApplicationImage image, Path root) throws IOException {
        String name = context.app().packageName();
        String installPath = installPath(context);
        FileTrees.copy(image.root(), root.resolve(installPath.substring(1)));
        Path command = root.resolve("usr/bin").resolve(name);
        Files.createDirectories(command.getParent());
        Files.createSymbolicLink(command, Path.of(installPath + "/" + image.launcher()));
        FileTrees.write(root.resolve("usr/share/applications").resolve(name + ".desktop"),
                desktopEntry(context.app(), context.config().linux(), installPath + "/" + image.launcher(), name));
        icons(context.icons(), root.resolve("usr/share/icons/hicolor"), name, IconSet.LINUX_SIZES);
        writeMimeTypes(context.app(), root.resolve("usr/share"), name, IconSet.LINUX_SIZES);
    }

    /**
     * The AppStream metadata of the application, installed in {@code share/metainfo} for the tools that read the
     * metadata of the installed applications. It has no release : a release needs a date, which the build does not
     * know.
     *
     * @param id the identifier of the application
     * @param desktopEntry the file name of its desktop entry
     */
    static String metainfo(AppInfo app, String id, String desktopEntry) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<component type=\"desktop-application\">\n");
        xml.append("  <id>").append(Xml.escape(id)).append("</id>\n");
        xml.append("  <name>").append(Xml.escape(app.name())).append("</name>\n");
        xml.append("  <summary>").append(Xml.escape(app.description())).append("</summary>\n");
        xml.append("  <metadata_license>CC0-1.0</metadata_license>\n");
        // the developer is identified by the domain of the application ID
        String developer = id.contains(".") ? id.substring(0, id.lastIndexOf('.')) : id;
        xml.append("  <developer id=\"").append(Xml.escape(developer)).append("\">\n    <name>")
                .append(Xml.escape(app.vendor())).append("</name>\n  </developer>\n");
        xml.append("  <description>\n    <p>").append(Xml.escape(app.description())).append("</p>\n  </description>\n");
        app.homepage().ifPresent(homepage -> xml.append("  <url type=\"homepage\">").append(Xml.escape(homepage))
                .append("</url>\n"));
        xml.append("  <launchable type=\"desktop-id\">").append(Xml.escape(desktopEntry)).append("</launchable>\n");
        xml.append("</component>\n");
        return xml.toString();
    }
}
