package io.quarkiverse.desktop.packaging.deployment.macos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;
import io.quarkiverse.desktop.packaging.deployment.Xml;

/**
 * The application bundle of macOS ({@code Name.app}) : its information property list, its signature, and its
 * notarization.
 */
public final class MacBundle {

    /**
     * The entitlements of a JVM with the hardened runtime : the just-in-time compiler writes executable memory, and
     * the JVM loads the native libraries of the jars, which other teams signed.
     */
    static final List<String> JVM_ENTITLEMENTS = List.of("com.apple.security.cs.allow-jit",
            "com.apple.security.cs.allow-unsigned-executable-memory", "com.apple.security.cs.disable-library-validation",
            "com.apple.security.cs.allow-dyld-environment-variables");

    private MacBundle() {
    }

    /**
     * The version of a bundle : three numbers.
     */
    public static String version(AppInfo app) {
        return app.numericVersion(3);
    }

    /**
     * The information property list of the bundle of a native executable ({@code Contents/Info.plist}).
     *
     * @param executable the file name of the executable, in {@code Contents/MacOS}
     * @param icon the file name of the icon, in {@code Contents/Resources}
     */
    public static String infoPlist(AppInfo app, DesktopPackagingConfig.Macos config, String executable, String icon) {
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("CFBundleDevelopmentRegion", "en");
        strings.put("CFBundleExecutable", executable);
        strings.put("CFBundleIconFile", icon);
        strings.put("CFBundleIdentifier", app.identifier());
        strings.put("CFBundleInfoDictionaryVersion", "6.0");
        strings.put("CFBundleName", app.name());
        strings.put("CFBundleDisplayName", app.name());
        strings.put("CFBundlePackageType", "APPL");
        strings.put("CFBundleShortVersionString", version(app));
        strings.put("CFBundleVersion", version(app));
        strings.put("LSApplicationCategoryType", config.category());
        config.minimumSystemVersion().ifPresent(version -> strings.put("LSMinimumSystemVersion", version));
        app.copyright().ifPresent(copyright -> strings.put("NSHumanReadableCopyright", copyright));
        strings.putAll(config.infoPlist());

        StringBuilder plist = new StringBuilder(plistHeader());
        for (Map.Entry<String, String> string : strings.entrySet()) {
            plist.append("    <key>").append(Xml.escape(string.getKey())).append("</key>\n");
            plist.append("    <string>").append(Xml.escape(string.getValue())).append("</string>\n");
        }
        // a Retina display draws the application at its resolution, and a laptop need not switch to its faster GPU
        plist.append("    <key>NSHighResolutionCapable</key>\n    <true/>\n");
        plist.append("    <key>NSSupportsAutomaticGraphicsSwitching</key>\n    <true/>\n");
        plist.append("</dict>\n</plist>\n");
        return plist.toString();
    }

    private static String plistHeader() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
                + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n";
    }

    /**
     * An entitlements property list.
     */
    static String entitlements(List<String> entitlements) {
        StringBuilder plist = new StringBuilder(plistHeader());
        for (String entitlement : entitlements) {
            plist.append("    <key>").append(Xml.escape(entitlement)).append("</key>\n    <true/>\n");
        }
        plist.append("</dict>\n</plist>\n");
        return plist.toString();
    }

    /**
     * Whether the configuration signs with a certificate, rather than ad hoc.
     */
    static boolean hasIdentity(PackagingContext context) {
        var sign = context.config().macos().sign();
        return sign.enabled() && !sign.identity().equals("-");
    }

    /**
     * Signs the application bundle : its libraries and executables first, each on its own, then the Java runtime of a
     * JVM application, then the bundle itself, which signs its main executable with the entitlements. With a
     * certificate, the signatures have a secure time stamp and the hardened runtime, which the notary service
     * requires.
     */
    public static void sign(PackagingContext context, ApplicationImage image) throws IOException {
        var sign = context.config().macos().sign();
        if (!sign.enabled()) {
            return;
        }
        Path bundle = image.root();
        Path main = image.launcherPath();
        List<Path> code = new ArrayList<>();
        for (Path file : FileTrees.files(bundle)) {
            Path path = bundle.resolve(file);
            if (!path.equals(main) && !Files.isSymbolicLink(path) && FileTrees.isMachO(path)) {
                code.add(path);
            }
        }
        // the deepest files first
        code.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path file : code) {
            codesign(context, file, null);
        }
        Path runtime = bundle.resolve("Contents/runtime");
        if (Files.isRegularFile(runtime.resolve("Contents/Info.plist"))) {
            codesign(context, runtime, null);
        }
        Path entitlements = null;
        if (sign.entitlements().isPresent()) {
            entitlements = context.projectDirectory().resolve(sign.entitlements().get());
            if (!Files.isRegularFile(entitlements)) {
                throw new PackagingException("quarkus.desktop.packaging.macos.sign.entitlements : " + entitlements
                        + " not found");
            }
        } else if (!image.nativeExecutable()) {
            entitlements = FileTrees.write(context.workDirectory("macos-sign").resolve("entitlements.plist"),
                    entitlements(JVM_ENTITLEMENTS));
        }
        codesign(context, bundle, entitlements);
    }

    /**
     * Signs a file or a bundle with {@code codesign}.
     *
     * @param entitlements the entitlements file, or {@code null}
     */
    static void codesign(PackagingContext context, Path path, Path entitlements) {
        var sign = context.config().macos().sign();
        Path codesign = context.tools().require("codesign", "install the Xcode command line tools : xcode-select --install",
                Path.of("/usr/bin/codesign"));
        List<String> command = new ArrayList<>(List.of(codesign.toString(), "--force", "--sign", sign.identity()));
        sign.keychain().ifPresent(keychain -> {
            command.add("--keychain");
            command.add(keychain);
        });
        if (hasIdentity(context)) {
            command.add("--timestamp");
            command.add("--options");
            command.add("runtime");
        }
        if (entitlements != null) {
            command.add("--entitlements");
            command.add(entitlements.toString());
        }
        command.add(path.toString());
        Tools.exec(command);
    }

    /**
     * Submits a disk image or an installer package to the notary service of Apple, waits for its answer and staples
     * the ticket to the file, when the configuration has the keychain profile of the credentials.
     */
    static void notarize(PackagingContext context, Path file) {
        var profile = context.config().macos().notarize().keychainProfile();
        if (profile.isEmpty()) {
            return;
        }
        Path xcrun = context.tools().require("xcrun", "install the Xcode command line tools : xcode-select --install",
                Path.of("/usr/bin/xcrun"));
        Tools.exec(List.of(xcrun.toString(), "notarytool", "submit", file.toString(), "--keychain-profile", profile.get(),
                "--wait"));
        Tools.exec(List.of(xcrun.toString(), "stapler", "staple", file.toString()));
    }

    /**
     * Copies an application bundle with {@code ditto}, which keeps what a signed bundle is made of : the symbolic
     * links, the permissions and the extended attributes.
     */
    static void copy(PackagingContext context, Path bundle, Path target) throws IOException {
        Path ditto = context.tools().require("ditto", "ditto is part of macOS", Path.of("/usr/bin/ditto"));
        Files.createDirectories(target.getParent());
        Tools.exec(List.of(ditto.toString(), bundle.toString(), target.toString()));
    }
}
