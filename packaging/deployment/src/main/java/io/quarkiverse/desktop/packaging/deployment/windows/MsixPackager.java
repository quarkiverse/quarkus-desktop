package io.quarkiverse.desktop.packaging.deployment.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
     * The general capabilities : the names of the {@code Capability} element of the foundation namespace.
     */
    private static final Set<String> GENERAL_CAPABILITIES = Set.of("internetClient", "internetClientServer",
            "privateNetworkClientServer", "allJoyn", "codeGeneration");

    /**
     * The capabilities of the {@code rescap} namespace that desktop applications declare, which need no kind in the
     * configuration ; the others are written {@code rescap:<name>}.
     */
    private static final Set<String> RESTRICTED_CAPABILITIES = Set.of("runFullTrust", "allowElevation",
            "broadFileSystemAccess", "packageManagement", "unvirtualizedResources", "localSystemServices");

    /**
     * The names of the {@code Capability} element of the {@code uap} namespace.
     */
    private static final Set<String> UAP_CAPABILITIES = Set.of("documentsLibrary", "picturesLibrary", "videosLibrary",
            "musicLibrary", "enterpriseAuthentication", "sharedUserCertificates", "userAccountInformation",
            "removableStorage", "appointments", "contacts", "phoneCall", "blockedChatMessages", "objects3D", "voipCall",
            "chat");

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        Path makeappx = context.tools().require("makeappx", "install the Windows SDK", WindowsSdk.locations("makeappx.exe"));
        Path work = context.workDirectory("msix");
        Path manifest = FileTrees.write(work.resolve("AppxManifest.xml"), manifest(context.app(),
                context.config().windows().msix(), context.arch(), image.launcher().replace('/', '\\')));
        // the files of the package and their paths in it : the application image is not copied
        Map<String, Path> generated = new LinkedHashMap<>();
        generated.put("AppxManifest.xml", manifest);
        for (Map.Entry<String, Integer> logo : LOGOS.entrySet()) {
            generated.put(ASSETS + "\\" + logo.getKey(),
                    context.icons().writePng(work.resolve(ASSETS).resolve(logo.getKey()), logo.getValue()));
        }
        Path mappingFile = FileTrees.write(work.resolve("mapping.txt"),
                mapping(generated, image.root(), FileTrees.files(image.root())));
        Path msix = context.output(context.app().fileName() + "-" + context.app().version() + ".msix");
        WindowsSdk.warnEmptyDirectories(image, msix);
        Tools.exec(work, Map.of(), List.of(makeappx.toString(), "pack", "/o", "/f", mappingFile.toString(), "/p",
                msix.toString()));
        if (WindowsSdk.signs(context)) {
            try {
                WindowsSdk.sign(context, msix);
            } catch (PackagingException e) {
                throw signatureFailure(e, publisher(context.app(), context.config().windows().msix()));
            }
        } else {
            LOGGER.warnf("%s is not signed : Windows only installs a signed MSIX package"
                    + " (quarkus.desktop.packaging.windows.sign.certificate-file, whose subject is the publisher %s)",
                    msix.getFileName(), publisher(context.app(), context.config().windows().msix()));
        }
        return msix;
    }

    /**
     * The mapping file of {@code makeappx} : the files of the package and their paths in it.
     *
     * @param generated the files that the packager generates (the manifest, the logos), by path in the package
     * @param root the directory of the application image
     * @param files the files of the application image, relative to its directory
     * @throws PackagingException when a file of the image has the path of a generated file
     */
    static String mapping(Map<String, Path> generated, Path root, List<Path> files) {
        StringBuilder mapping = new StringBuilder("[Files]\n");
        for (Map.Entry<String, Path> file : generated.entrySet()) {
            mapping.append(entry(file.getValue(), file.getKey()));
        }
        for (Path file : files) {
            String name = file.toString().replace('/', '\\');
            for (String reserved : generated.keySet()) {
                // makeappx compares the paths of a package without case
                if (name.equalsIgnoreCase(reserved)) {
                    throw new PackagingException("The application image has a file named " + name + ", which is the"
                            + " path of a file that the MSIX package generates (" + reserved + ") : rename it");
                }
            }
            mapping.append(entry(root.resolve(file), name));
        }
        return mapping.toString();
    }

    private static String entry(Path file, String name) {
        return "\"" + file + "\" \"" + name + "\"\n";
    }

    /**
     * The failure of the signature of the package, with its cause when {@code signtool} only reports the error
     * {@code 0x8007000b} : the publisher of the manifest is not the subject of the signing certificate.
     */
    static PackagingException signatureFailure(PackagingException failure, String publisher) {
        if (!failure.getMessage().toLowerCase(Locale.ROOT).contains("0x8007000b")) {
            return failure;
        }
        return new PackagingException(failure.getMessage() + "\nThe publisher of the package (" + publisher
                + ") is not the subject of the signing certificate : set quarkus.desktop.packaging.windows.msix.publisher"
                + " to that subject, as Windows writes it ((Get-PfxCertificate acme.pfx).Subject in PowerShell, such as"
                + " CN=Acme, O=Acme, L=Seattle, S=Washington, C=US)", failure);
    }

    /**
     * The version of an MSIX package : four numbers of at most 65535, the last one being zero (the Store reserves
     * it). A larger number is refused rather than cut down : Windows compares the versions to update the application.
     *
     * @throws PackagingException when a number is larger
     */
    static String version(AppInfo app) {
        String version = app.numericVersion(3);
        for (String part : version.split("\\.")) {
            if (Long.parseLong(part) > 65535) {
                throw new PackagingException("quarkus.desktop.packaging.version=" + app.version() + " : the version of an"
                        + " MSIX package has numbers of at most 65535, which Windows compares to update the application :"
                        + " set a version within this limit");
            }
        }
        return version + ".0";
    }

    /**
     * The capabilities of the manifest, as elements. The items of the configuration are trimmed and taken once ;
     * {@code runFullTrust} is always declared (the application is a full trust application). The device capabilities
     * come last, as the schema wants.
     *
     * @throws PackagingException for a capability of an unknown kind, or without kind and unknown
     */
    static List<String> capabilities(DesktopPackagingConfig.Msix config) {
        Set<String> general = new LinkedHashSet<>();
        Set<String> uap = new LinkedHashSet<>();
        Set<String> restricted = new LinkedHashSet<>();
        Set<String> device = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String item : config.capabilities()) {
            String capability = item.strip();
            if (capability.isEmpty()) {
                continue;
            }
            int colon = capability.indexOf(':');
            String kind = colon < 0 ? "" : capability.substring(0, colon).strip();
            String name = colon < 0 ? capability : capability.substring(colon + 1).strip();
            Set<String> set = switch (kind) {
                case "" -> GENERAL_CAPABILITIES.contains(name) ? general
                        : RESTRICTED_CAPABILITIES.contains(name) ? restricted
                                : UAP_CAPABILITIES.contains(name) ? uap : null;
                case "rescap" -> restricted;
                case "uap" -> uap;
                case "device" -> device;
                default -> null;
            };
            if (set == null || !name.matches("[-_.A-Za-z0-9]+")) {
                throw new PackagingException("quarkus.desktop.packaging.windows.msix.capabilities : " + capability
                        + " is not a known capability : write a general capability (" + String.join(", ",
                                new TreeSet<>(GENERAL_CAPABILITIES))
                        + "), a restricted one (" + String.join(", ", new TreeSet<>(RESTRICTED_CAPABILITIES))
                        + "), or another one with its kind (rescap:packageQuery, uap:documentsLibrary, device:microphone)");
            }
            // makeappx refuses a name declared twice, even in two namespaces
            if (set == device || seen.add(name)) {
                set.add(name);
            }
        }
        if (seen.add("runFullTrust")) {
            restricted.add("runFullTrust");
        }
        List<String> elements = new ArrayList<>();
        general.forEach(name -> elements.add("<Capability Name=\"" + Xml.escape(name) + "\" />"));
        uap.forEach(name -> elements.add("<uap:Capability Name=\"" + Xml.escape(name) + "\" />"));
        restricted.forEach(name -> elements.add("<rescap:Capability Name=\"" + Xml.escape(name) + "\" />"));
        device.forEach(name -> elements.add("<DeviceCapability Name=\"" + Xml.escape(name) + "\" />"));
        return elements;
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
        for (String capability : capabilities(config)) {
            xml.append("    ").append(capability).append('\n');
        }
        xml.append("  </Capabilities>\n");
        xml.append("</Package>\n");
        return xml.toString();
    }
}
