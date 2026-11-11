package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * An MSIX package, built with {@code makeappx} of the Windows SDK : the application image, a generated manifest
 * (a full trust desktop application) and the logos of the tiles. Windows only installs a signed package, whose
 * publisher is the subject of the signing certificate.
 */
public final class MsixPackager implements Packager {

    private static final Logger LOGGER = Logger.getLogger(MsixPackager.class);

    private static final String ASSETS = "Assets";

    /**
     * The logos of the manifest and their sizes in pixels.
     */
    private static final Map<String, Integer> LOGOS = Map.of("StoreLogo.png", 50, "Square44x44Logo.png", 44,
            "Square150x150Logo.png", 150);

    /**
     * The capabilities of the {@code rescap} namespace that desktop applications declare ; the others are general
     * capabilities ({@code internetClient}...).
     */
    private static final Set<String> RESTRICTED_CAPABILITIES = Set.of("runFullTrust", "allowElevation",
            "broadFileSystemAccess", "packageManagement", "unvirtualizedResources", "localSystemServices");

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path makeappx = context.tools().require("makeappx", "install the Windows SDK", WindowsSdk.locations("makeappx.exe"));
        Path work = context.workDirectory("msix");
        Path manifest = FileTrees.write(work.resolve("AppxManifest.xml"), manifest(context.app(),
                context.config().windows().msix(), context.arch(), image.launcher().replace('/', '\\')));
        // the files of the package and their paths in it : the application image is not copied
        StringBuilder mapping = new StringBuilder("[Files]\n");
        mapping.append(entry(manifest, "AppxManifest.xml"));
        for (Map.Entry<String, Integer> logo : LOGOS.entrySet()) {
            Path file = context.icons().writePng(work.resolve(ASSETS).resolve(logo.getKey()), logo.getValue());
            mapping.append(entry(file, ASSETS + "\\" + logo.getKey()));
        }
        for (Path file : FileTrees.files(image.root())) {
            String name = file.toString().replace('/', '\\');
            if (name.equalsIgnoreCase("AppxManifest.xml") || name.toLowerCase().startsWith(ASSETS.toLowerCase() + "\\")) {
                throw new PackagingException("The application image has a file named " + name
                        + ", which is the name of a file of the MSIX package");
            }
            mapping.append(entry(image.root().resolve(file), name));
        }
        Path mappingFile = FileTrees.write(work.resolve("mapping.txt"), mapping.toString());
        Path msix = context.output(context.app().fileName() + "-" + context.app().version() + ".msix");
        Tools.exec(work, Map.of(), List.of(makeappx.toString(), "pack", "/o", "/f", mappingFile.toString(), "/p",
                msix.toString()));
        if (WindowsSdk.signs(context)) {
            WindowsSdk.sign(context, msix);
        } else {
            LOGGER.warnf("%s is not signed : Windows only installs a signed MSIX package"
                    + " (quarkus.desktop.packaging.windows.sign.certificate-file, whose subject is the publisher %s)",
                    msix.getFileName(), publisher(context.app(), context.config().windows().msix()));
        }
        return msix;
    }

    private static String entry(Path file, String name) {
        return "\"" + file + "\" \"" + name + "\"\n";
    }

    /**
     * The version of an MSIX package : four numbers of at most 65535, the last one being zero (the Store reserves
     * it).
     */
    static String version(AppInfo app) {
        String[] parts = app.numericVersion(3).split("\\.");
        StringBuilder version = new StringBuilder();
        for (String part : parts) {
            version.append(Math.min(65535, Long.parseLong(part))).append('.');
        }
        return version.append('0').toString();
    }

    /**
     * The name of the identity of the package : 3 to 50 letters, digits, dots and dashes.
     */
    static String identityName(AppInfo app, DesktopPackagingConfig.Msix config) {
        String name = config.identityName().orElseGet(() -> app.identifier().replace('_', '-'));
        if (!name.matches("[A-Za-z0-9.-]{3,50}")) {
            throw new PackagingException("quarkus.desktop.packaging.windows.msix.identity-name=" + name + " : the name of"
                    + " the identity of an MSIX package has 3 to 50 letters, digits, dots and dashes");
        }
        return name;
    }

    static String publisher(AppInfo app, DesktopPackagingConfig.Msix config) {
        // the special characters of a distinguished name
        return config.publisher().orElseGet(() -> "CN=" + app.vendor().replaceAll("[,+\"\\\\<>;=#]", " ").trim());
    }

    /**
     * The manifest of the package.
     *
     * @param launcher the path of the executable in the package, with {@code \} separators
     */
    static String manifest(AppInfo app, DesktopPackagingConfig.Msix config, Arch arch, String launcher) {
        String name = Xml.escape(app.name());
        String description = Xml.escape(app.description());
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<Package xmlns=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10\"\n");
        xml.append("         xmlns:uap=\"http://schemas.microsoft.com/appx/manifest/uap/windows10\"\n");
        xml.append("         xmlns:rescap=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10/")
                .append("restrictedcapabilities\"\n");
        xml.append("         IgnorableNamespaces=\"uap rescap\">\n");
        xml.append("  <Identity Name=\"").append(Xml.escape(identityName(app, config))).append("\" Publisher=\"")
                .append(Xml.escape(publisher(app, config))).append("\" Version=\"").append(version(app))
                .append("\" ProcessorArchitecture=\"").append(arch.windows()).append("\" />\n");
        xml.append("  <Properties>\n");
        xml.append("    <DisplayName>").append(name).append("</DisplayName>\n");
        xml.append("    <PublisherDisplayName>").append(Xml.escape(config.publisherDisplayName().orElse(app.vendor())))
                .append("</PublisherDisplayName>\n");
        xml.append("    <Description>").append(description).append("</Description>\n");
        xml.append("    <Logo>").append(ASSETS).append("\\StoreLogo.png</Logo>\n");
        xml.append("  </Properties>\n");
        xml.append("  <Dependencies>\n");
        xml.append("    <TargetDeviceFamily Name=\"Windows.Desktop\" MinVersion=\"").append(Xml.escape(config.minVersion()))
                .append("\" MaxVersionTested=\"10.0.26100.0\" />\n");
        xml.append("  </Dependencies>\n");
        xml.append("  <Resources>\n");
        xml.append("    <Resource Language=\"en-us\" />\n");
        xml.append("  </Resources>\n");
        xml.append("  <Applications>\n");
        xml.append("    <Application Id=\"App\" Executable=\"").append(Xml.escape(launcher))
                .append("\" EntryPoint=\"Windows.FullTrustApplication\">\n");
        xml.append("      <uap:VisualElements DisplayName=\"").append(name).append("\" Description=\"").append(description)
                .append("\" BackgroundColor=\"transparent\" Square150x150Logo=\"").append(ASSETS)
                .append("\\Square150x150Logo.png\" Square44x44Logo=\"").append(ASSETS)
                .append("\\Square44x44Logo.png\" />\n");
        xml.append("    </Application>\n");
        xml.append("  </Applications>\n");
        xml.append("  <Capabilities>\n");
        // the general capabilities, then the restricted ones (the order of the schema)
        for (String capability : config.capabilities()) {
            if (!RESTRICTED_CAPABILITIES.contains(capability)) {
                xml.append("    <Capability Name=\"").append(Xml.escape(capability)).append("\" />\n");
            }
        }
        for (String capability : config.capabilities()) {
            if (RESTRICTED_CAPABILITIES.contains(capability)) {
                xml.append("    <rescap:Capability Name=\"").append(Xml.escape(capability)).append("\" />\n");
            }
        }
        xml.append("  </Capabilities>\n");
        xml.append("</Package>\n");
        return xml.toString();
    }
}
