package io.quarkiverse.desktop.packaging.deployment.macos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * A macOS installer package ({@code .pkg}), built with {@code pkgbuild} and {@code productbuild} : a component package
 * that installs the application bundle in {@code /Applications}, in a product archive with the title and the license of
 * the application. Signed with the installer identity of the configuration, and notarized when the configuration has
 * the credentials of the notary service.
 */
public final class PkgPackager implements Packager {

    private static final Logger LOGGER = Logger.getLogger(PkgPackager.class);

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        MacBundle.checkNotarization(context);
        Path pkgbuild = context.tools().require("pkgbuild", "pkgbuild is part of macOS", Path.of("/usr/bin/pkgbuild"));
        Path productbuild = context.tools().require("productbuild", "productbuild is part of macOS",
                Path.of("/usr/bin/productbuild"));
        AppInfo app = context.app();
        var macos = context.config().macos();
        Path work = context.workDirectory("pkg");
        Path root = work.resolve("root");
        MacBundle.copy(context, image.root(), root.resolve(image.root().getFileName().toString()));

        // not relocatable : the installer would otherwise update a copy of the application found anywhere on the
        // disk, rather than install it in the installation directory
        Path components = work.resolve("components.plist");
        Tools.exec(List.of(pkgbuild.toString(), "--analyze", "--root", root.toString(), components.toString()));
        Path plistBuddy = context.tools().require("PlistBuddy", "PlistBuddy is part of macOS",
                Path.of("/usr/libexec/PlistBuddy"));
        Tools.exec(List.of(plistBuddy.toString(), "-c", "Set :0:BundleIsRelocatable false", components.toString()));

        Path packages = Files.createDirectories(work.resolve("packages"));
        Path component = packages.resolve(app.packageName() + ".pkg");
        Tools.exec(List.of(pkgbuild.toString(), "--root", root.toString(), "--component-plist", components.toString(),
                "--identifier", app.identifier(), "--version", MacBundle.version(app), "--install-location",
                macos.installLocation(), component.toString()));

        // the product archive : the component package, and what the installer shows
        Path distribution = work.resolve("distribution.xml");
        Tools.exec(List.of(productbuild.toString(), "--synthesize", "--package", component.toString(),
                distribution.toString()));
        Path resources = Files.createDirectories(work.resolve("resources"));
        String license = null;
        if (app.licenseFile().isPresent()) {
            license = "license.txt";
            FileTrees.copy(app.licenseFile().get(), resources.resolve(license));
        }
        Files.writeString(distribution, distribution(Files.readString(distribution), app.name(), license));

        Path pkg = context.output(app.fileName() + "-" + app.version() + "-" + context.arch().macos() + ".pkg");
        List<String> command = new ArrayList<>(List.of(productbuild.toString(), "--distribution", distribution.toString(),
                "--resources", resources.toString(), "--package-path", packages.toString()));
        if (macos.sign().installerIdentity().isPresent()) {
            command.add("--sign");
            command.add(macos.sign().installerIdentity().get());
            command.add("--timestamp");
            macos.sign().keychain().ifPresent(keychain -> {
                command.add("--keychain");
                command.add(keychain);
            });
        }
        command.add(pkg.toString());
        Tools.exec(command);
        signatureWarning(macos, pkg.getFileName().toString()).ifPresent(LOGGER::warn);
        MacBundle.notarize(context, pkg);
        return pkg;
    }

    /**
     * The warning of an installer package that is not signed while the application bundle is signed with a
     * certificate : the installer identity is not set, or it resolves to nothing (a variable or a secret that is not
     * defined). Empty for a bundle signed ad hoc, which only runs on the machine of the build anyway, and for a signed
     * package.
     */
    static Optional<String> signatureWarning(DesktopPackagingConfig.Macos macos, String fileName) {
        if (!MacBundle.hasIdentity(macos.sign()) || macos.sign().installerIdentity().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(fileName + " is not signed : Gatekeeper blocks it on the machines that download it. Set"
                + " quarkus.desktop.packaging.macos.sign.installer-identity to a Developer ID Installer identity");
    }

    /**
     * The distribution file of the product archive : the one that {@code productbuild --synthesize} wrote, with the
     * title of the installer and the license that it shows.
     *
     * @param license the file name of the license in the resources of the installer, or {@code null}
     */
    static String distribution(String synthesized, String title, String license) {
        int start = synthesized.indexOf("<installer-gui-script");
        int end = start < 0 ? -1 : synthesized.indexOf('>', start);
        if (end < 0) {
            throw new PackagingException("productbuild --synthesize did not write a distribution file : " + synthesized);
        }
        StringBuilder elements = new StringBuilder("\n    <title>").append(Xml.escape(title)).append("</title>");
        if (license != null) {
            elements.append("\n    <license file=\"").append(Xml.escape(license)).append("\"/>");
        }
        return synthesized.substring(0, end + 1) + elements + synthesized.substring(end + 1);
    }
}
