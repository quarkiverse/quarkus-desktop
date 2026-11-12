package io.quarkiverse.desktop.packaging.deployment.macos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;
import io.quarkiverse.desktop.packaging.deployment.Tools;
import io.quarkiverse.desktop.packaging.deployment.macos.MachOTest.Signature;

/**
 * The signature of the application bundle and its notarization : the macOS libraries inside the jars, the renewed ad
 * hoc signature of {@code jpackage}, the command of {@code notarytool} and the answer of the notary service, the
 * identities that it needs, the warnings of the packages that are not notarized, and the signature of the installer
 * package.
 */
class MacSignatureTest {

    static final byte[] MANIFEST = "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    /**
     * What the stand-in of {@code codesign} adds to the files that it signs.
     */
    static final byte[] SIGNATURE = "signed".getBytes(StandardCharsets.UTF_8);

    /**
     * Writes a jar of the given entries, in their order.
     */
    static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    /**
     * The entries of a signed jar, whose libraries are signed with a certificate.
     */
    static Map<String, byte[]> signedJar() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", MANIFEST);
        entries.put("META-INF/VENDOR.SF", "Signature-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/VENDOR.RSA", new byte[] { 0x30 });
        entries.put("libvendor.jnilib", MachOTest.universal(false, MachOTest.library(Signature.CERTIFICATE),
                MachOTest.machO(false, 6, Signature.CERTIFICATE)));
        return entries;
    }

    static byte[] read(ZipFile zip, String name) throws IOException {
        try (InputStream input = zip.getInputStream(zip.getEntry(name))) {
            return input.readAllBytes();
        }
    }

    /**
     * The libraries of a jar to sign are its Mach-O entries that are code, whatever their name, and that are not
     * signed with a certificate : not its class files, which start as universal files do, nor the debug symbols of a
     * library, nor an executable that its vendor signed (with its entitlements).
     */
    @Test
    void theMacOsLibrariesOfAJar(@TempDir Path directory) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", MANIFEST);
        entries.put("org/acme/", new byte[0]);
        entries.put("org/acme/Natives.class", MachOTest.CLASS_FILE);
        entries.put("org/acme/data.bin", MachOTest.CLASS_FILE);
        entries.put("darwin-aarch64/libacme.dylib", MachOTest.library(Signature.LINKER));
        entries.put("darwin/libacme.jnilib", MachOTest.universal(false, MachOTest.library(Signature.NONE),
                MachOTest.machO(false, 6, Signature.NONE)));
        entries.put("darwin/libacme.dylib.dSYM/Contents/Resources/DWARF/libacme.dylib",
                MachOTest.machO(true, 10, Signature.NONE));
        entries.put("darwin/helper", MachOTest.machO(true, 2, Signature.AD_HOC));
        entries.put("darwin/node", MachOTest.machO(true, 2, Signature.CERTIFICATE));
        entries.put("readme.txt", "Acme".getBytes(StandardCharsets.UTF_8));
        Path natives = jar(directory.resolve("natives.jar"), entries);
        for (boolean notarized : new boolean[] { false, true }) {
            assertEquals(List.of("darwin-aarch64/libacme.dylib", "darwin/libacme.jnilib", "darwin/helper"),
                    MacBundle.jarLibraries(natives, notarized));
        }
        assertEquals(List.of(), MacBundle.jarLibraries(jar(directory.resolve("plain.jar"),
                Map.of("org/acme/Plain.class", MachOTest.CLASS_FILE)), true));

        // a signed jar cannot change : its libraries keep the signatures of their vendor
        assertEquals(List.of(), MacBundle.jarLibraries(jar(directory.resolve("vendor.jar"), signedJar()), true));
        // the notary service rejects a library of a signed jar that is not signed with a certificate : a warning when
        // the packages are not notarized
        Map<String, byte[]> unsigned = signedJar();
        unsigned.put("darwin/libother.dylib", MachOTest.library(Signature.AD_HOC));
        Path other = jar(directory.resolve("other.jar"), unsigned);
        assertEquals(List.of(), MacBundle.jarLibraries(other, false));
        PackagingException e = assertThrows(PackagingException.class, () -> MacBundle.jarLibraries(other, true));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.macos.sign.identity : other.jar is a signed jar"
                + " (META-INF/VENDOR.SF), and its macOS libraries [darwin/libother.dylib] are not signed with a"
                + " certificate."), e.getMessage());
    }

    /**
     * The packages are notarized when the configuration has a keychain profile, and a disk image or an installer
     * package is built.
     */
    @Test
    void thePackagesThatAreNotarized() {
        assertFalse(MacBundle.notarized(TestConfig.defaults()));
        assertFalse(MacBundle.notarized(TestConfig.of(Map.of("formats", "dmg"))));
        assertFalse(MacBundle.notarized(TestConfig.of(Map.of("formats", "dmg", "macos.notarize.keychain-profile", ""))));
        assertFalse(MacBundle.notarized(TestConfig.of(Map.of("macos.notarize.keychain-profile", "acme"))));
        assertFalse(MacBundle.notarized(TestConfig.of(Map.of("formats", "directory,msi,deb",
                "macos.notarize.keychain-profile", "acme"))));
        assertTrue(MacBundle.notarized(TestConfig.of(Map.of("formats", "directory,dmg",
                "macos.notarize.keychain-profile", "acme"))));
        assertTrue(MacBundle.notarized(TestConfig.of(Map.of("formats", "pkg", "macos.notarize.keychain-profile", "acme"))));
    }

    /**
     * The libraries are signed in their jars, which keep their other entries, the order of their entries and their
     * permissions. The other jars do not change, nor a file named as a jar that is not one.
     */
    @Test
    void theMacOsLibrariesOfTheJarsAreSignedInTheirJars(@TempDir Path directory) throws Exception {
        Path bundle = directory.resolve("Acme Editor.app");
        Path lib = bundle.resolve("Contents/app/lib/main");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", MANIFEST);
        entries.put("org/acme/Natives.class", MachOTest.CLASS_FILE);
        entries.put("darwin-aarch64/libacme.dylib", MachOTest.library(Signature.LINKER));
        entries.put("darwin-x86-64/libacme.dylib", MachOTest.library(Signature.NONE));
        entries.put("darwin/node", MachOTest.machO(true, 2, Signature.CERTIFICATE));
        entries.put("readme.txt", "Acme".getBytes(StandardCharsets.UTF_8));
        Path natives = jar(lib.resolve("org.acme.natives-1.0.jar"), entries);
        Path plain = jar(lib.resolve("org.acme.plain-1.0.jar"), Map.of("org/acme/Plain.class", MachOTest.CLASS_FILE));
        Path vendor = jar(lib.resolve("org.acme.vendor-1.0.jar"), signedJar());
        Path runtime = jar(bundle.resolve("Contents/runtime/Contents/Home/lib/jrt-fs.jar"),
                Map.of("jdk/internal/jrtfs/JrtFileSystem.class", MachOTest.CLASS_FILE));
        // a file of the content directory, which is not a jar
        Path notes = Files.writeString(Files.createDirectories(bundle.resolve("Contents/Resources")).resolve("notes.jar"),
                "not a jar");
        Map<Path, byte[]> unchanged = new LinkedHashMap<>();
        for (Path jar : List.of(plain, vendor, runtime, notes)) {
            unchanged.put(jar, Files.readAllBytes(jar));
        }
        boolean posix = Files.getFileAttributeView(natives, PosixFileAttributeView.class) != null;
        if (posix) {
            Files.setPosixFilePermissions(natives, PosixFilePermissions.fromString("rw-r--r--"));
        }

        Path work = directory.resolve("work");
        List<Path> signed = new ArrayList<>();
        MacBundle.signJarLibraries(bundle, work, true, codesign(work, signed));
        assertEquals(List.of(Path.of("0", "libacme.dylib"), Path.of("1", "libacme.dylib")), signed);
        try (ZipFile zip = new ZipFile(natives.toFile())) {
            assertEquals(List.copyOf(entries.keySet()), zip.stream().map(ZipEntry::getName).toList());
            assertArrayEquals(concat(MachOTest.library(Signature.LINKER), SIGNATURE),
                    read(zip, "darwin-aarch64/libacme.dylib"));
            assertArrayEquals(concat(MachOTest.library(Signature.NONE), SIGNATURE),
                    read(zip, "darwin-x86-64/libacme.dylib"));
            assertArrayEquals(MachOTest.machO(true, 2, Signature.CERTIFICATE), read(zip, "darwin/node"));
            assertArrayEquals(MachOTest.CLASS_FILE, read(zip, "org/acme/Natives.class"));
            assertArrayEquals(MANIFEST, read(zip, "META-INF/MANIFEST.MF"));
        }
        // the class loader of Quarkus verifies the jars that it opens
        try (JarFile jar = new JarFile(natives.toFile(), true)) {
            assertEquals("1.0", jar.getManifest().getMainAttributes().getValue("Manifest-Version"));
        }
        for (Map.Entry<Path, byte[]> jar : unchanged.entrySet()) {
            assertArrayEquals(jar.getValue(), Files.readAllBytes(jar.getKey()), jar.getKey().toString());
        }
        if (posix) {
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(natives));
        }

        // a signed jar with a library that is not signed with a certificate fails the build before anything is signed
        // when the packages are notarized, and stays as it is otherwise, while the libraries of the other jars are signed
        Map<String, byte[]> unsigned = signedJar();
        unsigned.put("darwin/libother.dylib", MachOTest.library(Signature.AD_HOC));
        Path other = directory.resolve("Other.app");
        Path otherJar = jar(other.resolve("Contents/app/lib/main/org.acme.other-1.0.jar"), unsigned);
        Path nativesJar = jar(other.resolve("Contents/app/lib/main/org.acme.unsigned-1.0.jar"),
                Map.of("darwin/libunsigned.dylib", MachOTest.library(Signature.NONE)));
        byte[] otherBytes = Files.readAllBytes(otherJar);
        byte[] nativesBytes = Files.readAllBytes(nativesJar);
        Path otherWork = directory.resolve("other-work");
        signed.clear();
        assertThrows(PackagingException.class,
                () -> MacBundle.signJarLibraries(other, otherWork, true, codesign(otherWork, signed)));
        assertEquals(List.of(), signed);
        assertArrayEquals(otherBytes, Files.readAllBytes(otherJar));
        assertArrayEquals(nativesBytes, Files.readAllBytes(nativesJar));

        MacBundle.signJarLibraries(other, otherWork, false, codesign(otherWork, signed));
        assertEquals(List.of(Path.of("0", "libunsigned.dylib")), signed);
        assertArrayEquals(otherBytes, Files.readAllBytes(otherJar));
        try (ZipFile zip = new ZipFile(nativesJar.toFile())) {
            assertArrayEquals(concat(MachOTest.library(Signature.NONE), SIGNATURE), read(zip, "darwin/libunsigned.dylib"));
        }
    }

    /**
     * A stand-in of {@code codesign}, which adds {@link #SIGNATURE} to the files that it signs.
     *
     * @param signed the paths of the signed files, relative to the work directory
     */
    static Consumer<Path> codesign(Path work, List<Path> signed) {
        return file -> {
            signed.add(work.relativize(file));
            try {
                Files.write(file, SIGNATURE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] bytes = new byte[first.length + second.length];
        System.arraycopy(first, 0, bytes, 0, first.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        return bytes;
    }

    /**
     * Unsigned, the bundle that {@code jpackage} signed ad hoc is signed ad hoc again, without the options of a
     * certificate.
     */
    @Test
    void theAdHocSignatureOfJpackageIsRenewed(@TempDir Path bundle) throws Exception {
        Path codesign = Path.of("/usr/bin/codesign");
        assertEquals(List.of(codesign.toString(), "--force", "--sign", "-", bundle.toString()),
                MacBundle.resealCommand(codesign, bundle));
        assertFalse(MacBundle.signedByJpackage(bundle));
        Files.createDirectories(bundle.resolve("Contents/_CodeSignature"));
        Files.writeString(bundle.resolve("Contents/_CodeSignature/CodeResources"), "<plist/>");
        assertTrue(MacBundle.signedByJpackage(bundle));
    }

    @Test
    void theCommandThatSubmitsToTheNotaryService() {
        Path xcrun = Path.of("/usr/bin/xcrun");
        Path dmg = Path.of("target", "packaging", "Acme-Editor-1.2.3.dmg");
        DesktopPackagingConfig defaults = TestConfig.defaults();
        assertEquals(List.of(xcrun.toString(), "notarytool", "submit", dmg.toString(), "--keychain-profile", "acme",
                "--wait"), MacBundle.notarizeCommand(xcrun, dmg, "acme", defaults.macos().notarize().timeout()));
        DesktopPackagingConfig config = TestConfig.of(Map.of("macos.notarize.keychain-profile", "acme",
                "macos.notarize.timeout", "45m"));
        assertEquals(Optional.of("45m"), config.macos().notarize().timeout());
        assertEquals(List.of(xcrun.toString(), "notarytool", "submit", dmg.toString(), "--keychain-profile", "acme",
                "--wait", "--timeout", "45m"),
                MacBundle.notarizeCommand(xcrun, dmg, "acme",
                        config.macos().notarize().timeout()));
    }

    /**
     * The identity of an application signed with a certificate.
     */
    static final String IDENTITY = "Developer ID Application: Acme (ABCDE12345)";

    /**
     * The identity of an installer package signed with a certificate.
     */
    static final String INSTALLER_IDENTITY = "Developer ID Installer: Acme (ABCDE12345)";

    /**
     * The identifier of a submission to the notary service.
     */
    static final String ID = "2efe2717-52ef-43a5-96dc-0797e4ca1041";

    /**
     * The output of {@code notarytool submit} when the upload fails.
     */
    static final String UNAUTHORIZED = """
            Conducting pre-submission checks for Acme.dmg and initiating connection to the Apple notary service...
            Error: HTTP status code: 401. Unable to authenticate. Invalid credentials.
            """;

    /**
     * The log of a submission that the notary service did not accept.
     */
    static final String LOG = """
            {
              "jobId": "2efe2717-52ef-43a5-96dc-0797e4ca1041",
              "status": "Invalid",
              "issues": [
                {
                  "severity": "error",
                  "path": "Acme.dmg/Acme Editor.app/Contents/MacOS/Acme Editor",
                  "message": "The signature does not include a secure timestamp."
                }
              ]
            }
            """;

    /**
     * The output of {@code notarytool submit --wait}, with the given answer of the notary service.
     */
    static String submitted(String status) {
        return """
                Conducting pre-submission checks for Acme.dmg and initiating connection to the Apple notary service...
                Submission ID received
                  id: %1$s
                Upload progress: 100.00%% (12.3 MB of 12.3 MB)
                Successfully uploaded file
                  id: %1$s
                  path: /Users/acme/editor/target/packaging/Acme.dmg
                Waiting for processing to complete.
                Current status: In Progress.....
                Current status: %2$s........Processing complete
                  id: %1$s
                  status: %2$s
                """.formatted(ID, status);
    }

    /**
     * A stand-in of the tools : the results of the given commands, and the commands that it ran.
     */
    static Function<List<String>, Tools.Result> tools(List<List<String>> runs, Map<List<String>, Tools.Result> results) {
        return command -> {
            runs.add(command);
            Tools.Result result = results.get(command);
            if (result == null) {
                throw new AssertionError("Unexpected command : " + command);
            }
            return result;
        };
    }

    /**
     * The answer of the notary service is the last status of the output of {@code notarytool}, not the progress of the
     * wait, and the identifier of the submission is the first one.
     */
    @Test
    void theAnswerOfTheNotaryService() {
        assertEquals(Optional.of("Accepted"), MacBundle.notarizationStatus(submitted("Accepted")));
        assertEquals(Optional.of("Invalid"), MacBundle.notarizationStatus(submitted("Invalid")));
        assertEquals(Optional.of("In Progress"), MacBundle.notarizationStatus(submitted("In Progress")));
        assertEquals(Optional.of(ID), MacBundle.submissionId(submitted("Invalid")));
        assertEquals(Optional.of("Invalid"), MacBundle.notarizationStatus(submitted("Invalid").replace("\n", "\r\n")));
        // an upload that failed
        assertEquals(Optional.empty(), MacBundle.notarizationStatus(UNAUTHORIZED));
        assertEquals(Optional.empty(), MacBundle.submissionId(UNAUTHORIZED));
        assertEquals(Optional.empty(), MacBundle.notarizationStatus(""));
    }

    /**
     * The ticket is stapled to a file that the notary service accepted. The build fails with the log of the submission
     * when the service did not accept it, whatever the exit code of {@code notarytool}, and with the output of
     * {@code notarytool} when the upload or the wait failed.
     */
    @Test
    void onlyAFileThatTheNotaryServiceAcceptedIsStapled() {
        Path xcrun = Path.of("/usr/bin/xcrun");
        Path dmg = Path.of("target", "packaging", "Acme.dmg");
        List<String> submit = MacBundle.notarizeCommand(xcrun, dmg, "acme", Optional.empty());
        List<String> staple = List.of(xcrun.toString(), "stapler", "staple", dmg.toString());
        List<String> log = List.of(xcrun.toString(), "notarytool", "log", ID, "--keychain-profile", "acme");
        Tools.Result stapled = new Tools.Result(0, "The staple and validate action worked!\n");
        List<List<String>> runs = new ArrayList<>();
        MacBundle.notarize(xcrun, dmg, "acme", Optional.empty(), tools(runs, Map.of(submit,
                new Tools.Result(0, submitted("Accepted")), staple, stapled)));
        assertEquals(List.of(submit, staple), runs);
        // an output without a status, without an error : stapler checks that there is a ticket
        runs.clear();
        MacBundle.notarize(xcrun, dmg, "acme", Optional.empty(), tools(runs, Map.of(submit,
                new Tools.Result(0, "Processing complete\n"), staple, stapled)));
        assertEquals(List.of(submit, staple), runs);

        for (int exitCode : new int[] { 0, 1 }) {
            runs.clear();
            PackagingException rejected = assertThrows(PackagingException.class, () -> MacBundle.notarize(xcrun, dmg,
                    "acme", Optional.empty(), tools(runs, Map.of(submit, new Tools.Result(exitCode, submitted("Invalid")),
                            log, new Tools.Result(0, LOG)))));
            assertEquals(List.of(submit, log), runs);
            assertEquals("The notary service did not accept Acme.dmg (status Invalid, submission " + ID + ") : fix the"
                    + " problems of its log, and build again :\n" + LOG.strip(), rejected.getMessage());
        }
        // a log that cannot be fetched : the output of notarytool
        runs.clear();
        PackagingException withoutLog = assertThrows(PackagingException.class, () -> MacBundle.notarize(xcrun, dmg,
                "acme", Optional.empty(), tools(runs, Map.of(submit, new Tools.Result(0, submitted("Rejected")), log,
                        new Tools.Result(69, "Error: Submission log is not yet available.\n")))));
        assertEquals(List.of(submit, log), runs);
        assertTrue(withoutLog.getMessage().startsWith("The notary service did not accept Acme.dmg (status Rejected,"
                + " submission " + ID + ")"), withoutLog.getMessage());
        assertTrue(withoutLog.getMessage().endsWith(":\n" + submitted("Rejected").strip()), withoutLog.getMessage());

        // the upload failed, or the wait ended before the answer (--timeout) : nothing else runs
        for (String output : List.of(UNAUTHORIZED, submitted("In Progress"))) {
            runs.clear();
            Tools.Result failure = new Tools.Result(1, output);
            PackagingException failed = assertThrows(PackagingException.class, () -> MacBundle.notarize(xcrun, dmg,
                    "acme", Optional.empty(), tools(runs, Map.of(submit, failure))));
            assertEquals(List.of(submit), runs);
            assertEquals(Tools.failure(submit, failure).getMessage(), failed.getMessage());
        }
        runs.clear();
        Tools.Result noTicket = new Tools.Result(65, "The staple and validate action failed! Error 65.\n");
        PackagingException notStapled = assertThrows(PackagingException.class, () -> MacBundle.notarize(xcrun, dmg,
                "acme", Optional.empty(), tools(runs, Map.of(submit, new Tools.Result(0, submitted("Accepted")), staple,
                        noTicket))));
        assertEquals(Tools.failure(staple, noTicket).getMessage(), notStapled.getMessage());
    }

    /**
     * The notary service rejects a bundle that is not signed with a certificate, and an installer package that is not
     * signed : the build fails before the packages are built, rather than after the upload and the wait.
     */
    @Test
    void theNotarizationNeedsDeveloperIdIdentities() {
        for (Map<String, String> properties : List.of(Map.<String, String> of(), Map.of("macos.sign.identity", IDENTITY))) {
            DesktopPackagingConfig config = TestConfig.of(properties);
            assertEquals(Optional.empty(), MacBundle.notarizationProblem(config.macos(), false));
            assertEquals(Optional.empty(), MacBundle.notarizationProblem(config.macos(), true));
        }

        DesktopPackagingConfig adHoc = TestConfig.of(Map.of("macos.notarize.keychain-profile", "acme"));
        assertEquals(Optional.of("quarkus.desktop.packaging.macos.notarize.keychain-profile is set, but the application"
                + " bundle is signed ad hoc, which the notary service rejects : set"
                + " quarkus.desktop.packaging.macos.sign.identity to a Developer ID Application identity"),
                MacBundle.notarizationProblem(adHoc.macos(), false));
        assertEquals(MacBundle.notarizationProblem(adHoc.macos(), false),
                MacBundle.notarizationProblem(adHoc.macos(), true));
        DesktopPackagingConfig unsigned = TestConfig.of(Map.of("macos.notarize.keychain-profile", "acme",
                "macos.sign.identity", IDENTITY, "macos.sign.enabled", "false"));
        assertEquals(Optional.of("quarkus.desktop.packaging.macos.notarize.keychain-profile is set, but the application"
                + " bundle is not signed (quarkus.desktop.packaging.macos.sign.enabled=false), which the notary service"
                + " rejects : sign it with a Developer ID Application identity"
                + " (quarkus.desktop.packaging.macos.sign.identity)"),
                MacBundle.notarizationProblem(unsigned.macos(), false));

        DesktopPackagingConfig signed = TestConfig.of(Map.of("macos.notarize.keychain-profile", "acme",
                "macos.sign.identity", IDENTITY));
        assertEquals(Optional.empty(), MacBundle.notarizationProblem(signed.macos(), false));
        assertEquals(Optional.of("quarkus.desktop.packaging.macos.notarize.keychain-profile is set, but the installer"
                + " package is not signed, which the notary service rejects : set"
                + " quarkus.desktop.packaging.macos.sign.installer-identity to a Developer ID Installer identity"),
                MacBundle.notarizationProblem(signed.macos(), true));
        DesktopPackagingConfig released = TestConfig.of(Map.of("macos.notarize.keychain-profile", "acme",
                "macos.sign.identity", IDENTITY, "macos.sign.installer-identity", INSTALLER_IDENTITY));
        assertEquals(Optional.empty(), MacBundle.notarizationProblem(released.macos(), true));
    }

    /**
     * The packages of a bundle signed with a certificate get a warning when they are not notarized, or not signed : a
     * property of a release build that resolves to nothing, such as a secret that is not defined. Not the ones of a
     * bundle signed ad hoc, the default.
     */
    @Test
    void thePackagesOfABundleSignedWithACertificateThatAreNotNotarizedGetAWarning() {
        for (Map<String, String> properties : List.of(Map.<String, String> of(), Map.of("macos.sign.identity", IDENTITY,
                "macos.sign.enabled", "false"))) {
            DesktopPackagingConfig config = TestConfig.of(properties);
            assertEquals(Optional.empty(), MacBundle.notarizationWarning(config.macos(), "Acme.dmg"));
            assertEquals(Optional.empty(), PkgPackager.signatureWarning(config.macos(), "Acme.pkg"));
        }

        DesktopPackagingConfig signed = TestConfig.of(Map.of("macos.sign.identity", IDENTITY,
                "macos.notarize.keychain-profile", "", "macos.sign.installer-identity", ""));
        assertEquals(Optional.of("Acme.dmg is not notarized : Gatekeeper blocks it on the machines that download it. Set"
                + " quarkus.desktop.packaging.macos.notarize.keychain-profile to the keychain profile of the credentials"
                + " of the notary service"), MacBundle.notarizationWarning(signed.macos(), "Acme.dmg"));
        assertEquals(Optional.of("Acme.pkg is not signed : Gatekeeper blocks it on the machines that download it. Set"
                + " quarkus.desktop.packaging.macos.sign.installer-identity to a Developer ID Installer identity"),
                PkgPackager.signatureWarning(signed.macos(), "Acme.pkg"));

        DesktopPackagingConfig released = TestConfig.of(Map.of("macos.sign.identity", IDENTITY,
                "macos.notarize.keychain-profile", "acme", "macos.sign.installer-identity", INSTALLER_IDENTITY));
        assertEquals(Optional.empty(), MacBundle.notarizationWarning(released.macos(), "Acme.dmg"));
        assertEquals(Optional.empty(), PkgPackager.signatureWarning(released.macos(), "Acme.pkg"));
    }

    /**
     * The installer package is signed with the installer identity, with a secure time stamp, unless nothing is signed
     * ({@code macos.sign.enabled=false}).
     */
    @Test
    void theInstallerPackageIsSignedWithTheInstallerIdentity() {
        assertEquals(List.of(), PkgPackager.signArguments(TestConfig.defaults().macos().sign()));
        assertEquals(List.of("--sign", INSTALLER_IDENTITY, "--timestamp"), PkgPackager.signArguments(
                TestConfig.of(Map.of("macos.sign.installer-identity", INSTALLER_IDENTITY)).macos().sign()));
        assertEquals(List.of("--sign", INSTALLER_IDENTITY, "--timestamp", "--keychain", "ci.keychain-db"),
                PkgPackager.signArguments(TestConfig.of(Map.of("macos.sign.installer-identity", INSTALLER_IDENTITY,
                        "macos.sign.keychain", "ci.keychain-db")).macos().sign()));
        assertEquals(List.of(), PkgPackager.signArguments(TestConfig.of(Map.of("macos.sign.installer-identity",
                INSTALLER_IDENTITY, "macos.sign.identity", IDENTITY, "macos.sign.enabled", "false")).macos().sign()));
        assertEquals(List.of(), PkgPackager.signArguments(
                TestConfig.of(Map.of("macos.sign.installer-identity", "")).macos().sign()));
    }
}
