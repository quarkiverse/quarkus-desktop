package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * What the Linux packages share : the desktop entry of the application, its icons in the icon theme, its AppStream
 * metadata, and the tree of the files that the Debian and RPM packages install.
 */
final class LinuxDesktop {

    private LinuxDesktop() {
    }

    /**
     * The desktop entry of the application : what the menus of the desktops show and start.
     *
     * @param exec the command of the application
     * @param icon the name of its icon in the icon theme, or the path of the icon file
     */
    static String desktopEntry(AppInfo app, DesktopPackagingConfig.Linux config, String exec, String icon) {
        StringBuilder entry = new StringBuilder();
        entry.append("[Desktop Entry]\n");
        entry.append("Type=Application\n");
        entry.append("Name=").append(value(app.name())).append('\n');
        entry.append("Comment=").append(value(app.description())).append('\n');
        entry.append("Exec=").append(exec).append('\n');
        entry.append("Icon=").append(icon).append('\n');
        entry.append("Terminal=false\n");
        entry.append("Categories=").append(String.join(";", config.categories())).append(";\n");
        config.startupWmClass().ifPresent(windowClass -> entry.append("StartupWMClass=").append(value(windowClass))
                .append('\n'));
        return entry.toString();
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
        String directory = context.config().linux().installDirectory().replaceAll("/+$", "");
        return (directory.startsWith("/") ? directory : "/" + directory) + "/" + context.app().packageName();
    }

    /**
     * Builds the tree of the files of a Debian or RPM package : the application image in its installation directory,
     * its command in {@code /usr/bin} (a link to the launcher), its desktop entry and its icons.
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
    }

    /**
     * The AppStream metadata of the application, which the software centers show.
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
        xml.append("  <developer_name>").append(Xml.escape(app.vendor())).append("</developer_name>\n");
        xml.append("  <description>\n    <p>").append(Xml.escape(app.description())).append("</p>\n  </description>\n");
        app.homepage().ifPresent(homepage -> xml.append("  <url type=\"homepage\">").append(Xml.escape(homepage))
                .append("</url>\n"));
        xml.append("  <launchable type=\"desktop-id\">").append(Xml.escape(desktopEntry)).append("</launchable>\n");
        xml.append("  <releases>\n    <release version=\"").append(Xml.escape(app.version())).append("\" />\n")
                .append("  </releases>\n");
        xml.append("</component>\n");
        return xml.toString();
    }
}
