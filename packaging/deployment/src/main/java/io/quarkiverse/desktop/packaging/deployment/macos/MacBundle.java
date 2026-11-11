package io.quarkiverse.desktop.packaging.deployment.macos;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.jboss.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(MacBundle.class);

    /**
     * The prefix of the identifiers of the categories of applications.
     */
    private static final String CATEGORY_PREFIX = "public.app-category.";

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
        // the identifier of the category, or its last part, which jpackage takes
        String category = config.category();
        strings.put("LSApplicationCategoryType", category.startsWith(CATEGORY_PREFIX) ? category : CATEGORY_PREFIX + category);
        config.minimumSystemVersion().ifPresent(version -> strings.put("LSMinimumSystemVersion", version));
        app.copyright().ifPresent(copyright -> strings.put("NSHumanReadableCopyright", copyright));
        // a Retina display draws the application at its resolution, and a laptop need not switch to its faster GPU
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        booleans.put("NSHighResolutionCapable", true);
        booleans.put("NSSupportsAutomaticGraphicsSwitching", true);
        // an entry of the configuration replaces the one of the same key, a string or a Boolean
        for (Map.Entry<String, String> entry : config.infoPlist().entrySet()) {
            if (entry.getValue().equals("true") || entry.getValue().equals("false")) {
                strings.remove(entry.getKey());
                booleans.put(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
            } else {
                booleans.remove(entry.getKey());
                strings.put(entry.getKey(), entry.getValue());
            }
        }

        StringBuilder plist = new StringBuilder(plistHeader());
        for (Map.Entry<String, String> string : strings.entrySet()) {
            plist.append("    <key>").append(Xml.escape(string.getKey())).append("</key>\n");
            plist.append("    <string>").append(Xml.escape(string.getValue())).append("</string>\n");
        }
        for (Map.Entry<String, Boolean> bool : booleans.entrySet()) {
            plist.append("    <key>").append(Xml.escape(bool.getKey())).append("</key>\n");
            plist.append("    <").append(bool.getValue()).append("/>\n");
        }
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
     * requires, and the macOS libraries inside the jars that are not signed with a certificate are signed too. When
     * the bundle is not signed, the ad hoc signature that {@code jpackage} gave it (on Apple silicon) is renewed, as
     * the extension changed the bundle.
     */
    public static void sign(PackagingContext context, ApplicationImage image) throws IOException {
        var sign = context.config().macos().sign();
        Path bundle = image.root();
        if (!sign.enabled()) {
            if (signedByJpackage(bundle)) {
                Tools.exec(resealCommand(codesignTool(context), bundle));
            }
            return;
        }
        if (hasIdentity(context)) {
            signJarLibraries(bundle, context.workDirectory("macos-jars"), library -> codesign(context, library, null));
        }
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
        List<String> command = new ArrayList<>(List.of(codesignTool(context).toString(), "--force", "--sign",
                sign.identity()));
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
     * The {@code codesign} of the machine.
     */
    private static Path codesignTool(PackagingContext context) {
        return context.tools().require("codesign", "install the Xcode command line tools : xcode-select --install",
                Path.of("/usr/bin/codesign"));
    }

    /**
     * Whether {@code jpackage} signed the bundle : it signs its bundle ad hoc on Apple silicon, a native bundle has no
     * signature until it is signed.
     */
    static boolean signedByJpackage(Path bundle) {
        return Files.isDirectory(bundle.resolve("Contents/_CodeSignature"));
    }

    /**
     * The command that renews the ad hoc signature of a bundle that is not signed otherwise, after its files changed :
     * without the options of a signature with a certificate, which an ad hoc signature does not need.
     */
    static List<String> resealCommand(Path codesign, Path bundle) {
        return List.of(codesign.toString(), "--force", "--sign", "-", bundle.toString());
    }

    /**
     * Signs the macOS libraries inside the jars of the bundle that are not signed with a certificate, which the notary
     * service checks as it checks the other files : each one is extracted, signed, and put back in its jar.
     *
     * @param work the directory of the extracted libraries
     * @param codesign signs a file
     * @throws PackagingException when a signed jar has a library that is not signed with a certificate
     */
    static void signJarLibraries(Path bundle, Path work, Consumer<Path> codesign) throws IOException {
        int count = 0;
        for (Path file : FileTrees.files(bundle)) {
            Path jar = bundle.resolve(file);
            if (!file.getFileName().toString().endsWith(".jar") || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            List<String> libraries;
            try {
                libraries = jarLibraries(jar);
            } catch (ZipException e) {
                LOGGER.warnf("%s is not a jar that can be read (%s) : the macOS libraries that it may have are not"
                        + " signed", jar, e.getMessage());
                continue;
            }
            if (libraries.isEmpty()) {
                continue;
            }
            LOGGER.debugf("Signing the macOS libraries of %s : %s", jar, libraries);
            jar.toFile().setWritable(true);
            // the zip file system writes the jar anew when it is closed, with its permissions
            try (FileSystem zip = FileSystems.newFileSystem(jar)) {
                for (String library : libraries) {
                    Path entry = zip.getPath(library);
                    Path extracted = Files.createDirectories(work.resolve(String.valueOf(count++)))
                            .resolve(entry.getFileName().toString());
                    Files.copy(entry, extracted);
                    codesign.accept(extracted);
                    // in place, rather than as a new entry at the end of the jar
                    Files.write(entry, Files.readAllBytes(extracted));
                }
            }
        }
    }

    /**
     * The macOS libraries of a jar to sign : its entries that are Mach-O executables, libraries or bundles (not its
     * class files, which start with the magic number of a universal file too), and that are not signed with a
     * certificate. The ones that are keep the signature of their vendor, with its entitlements.
     *
     * @throws PackagingException when the jar is signed and has such a library : it cannot change without breaking
     *         the signature of the jar, and the notary service rejects it
     */
    static List<String> jarLibraries(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> libraries = new ArrayList<>();
            Optional<String> signature = Optional.empty();
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && upper.endsWith(".SF") && upper.indexOf('/', 9) < 0) {
                    signature = Optional.of(name);
                } else if (!entry.isDirectory() && !name.endsWith(".class")) {
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                        input.mark(8);
                        if (!MachO.isMachO(input.readNBytes(8))) {
                            continue;
                        }
                        input.reset();
                        byte[] file = input.readAllBytes();
                        if (MachO.isCode(file) && !MachO.signedWithCertificate(file)) {
                            libraries.add(name);
                        }
                    }
                }
            }
            if (signature.isPresent() && !libraries.isEmpty()) {
                throw new PackagingException("quarkus.desktop.packaging.macos.sign.identity : " + jar.getFileName()
                        + " is a signed jar (" + signature.get() + "), and its macOS libraries " + libraries
                        + " are not signed with a certificate. The notary service rejects them, and signing them would"
                        + " break the signature of the jar : depend on a version of the library whose jar is not"
                        + " signed, or build a native executable");
            }
            return libraries;
        }
    }

    /**
     * Submits a disk image or an installer package to the notary service of Apple, waits for its answer and staples
     * the ticket to the file, when the configuration has the keychain profile of the credentials. The answer, with
     * the identifier of the submission that {@code xcrun notarytool log} takes, is logged.
     */
    static void notarize(PackagingContext context, Path file) {
        var notarize = context.config().macos().notarize();
        if (notarize.keychainProfile().isEmpty()) {
            return;
        }
        Path xcrun = context.tools().require("xcrun", "install the Xcode command line tools : xcode-select --install",
                Path.of("/usr/bin/xcrun"));
        LOGGER.infof("Submitting %s to the notary service of Apple : the build waits for its answer", file.getFileName());
        Tools.Result result = Tools.exec(notarizeCommand(xcrun, file, notarize.keychainProfile().get(),
                notarize.timeout()));
        LOGGER.infof("notarytool : %s", result.output().strip());
        Tools.exec(List.of(xcrun.toString(), "stapler", "staple", file.toString()));
    }

    /**
     * The command that submits a file to the notary service and waits for its answer.
     *
     * @param timeout how long it waits, as {@code notarytool} takes it ({@code 30m}), or empty to wait for the answer
     */
    static List<String> notarizeCommand(Path xcrun, Path file, String keychainProfile, Optional<String> timeout) {
        List<String> command = new ArrayList<>(List.of(xcrun.toString(), "notarytool", "submit", file.toString(),
                "--keychain-profile", keychainProfile, "--wait"));
        timeout.ifPresent(duration -> {
            command.add("--timeout");
            command.add(duration);
        });
        return command;
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
