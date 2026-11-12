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
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * What the Linux packages share : the desktop entry of the application, its icons in the icon theme, its AppStream
 * metadata, and the tree of the files that the Debian and RPM packages install.
 */
final class LinuxDesktop {

    private LinuxDesktop() {
    }

    /**
     * The desktop entry of the application : what the menus of the desktops show and start. Its window class
     * ({@code StartupWMClass}) is the one of the configuration, else the name that the application gives itself at run
     * time ({@link AppInfo#applicationId}), which its toolkit gives to its windows.
     *
     * @param exec the command of the application
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
        entry.append("Exec=").append(exec).append('\n');
        entry.append("Icon=").append(icon).append('\n');
        entry.append("Terminal=false\n");
        entry.append("Categories=").append(String.join(";", config.categories())).append(";\n");
        config.startupWmClass().or(app::applicationId).ifPresent(windowClass -> entry.append("StartupWMClass=")
                .append(value(windowClass)).append('\n'));
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
