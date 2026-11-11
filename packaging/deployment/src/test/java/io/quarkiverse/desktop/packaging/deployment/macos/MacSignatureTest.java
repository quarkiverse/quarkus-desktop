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
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;
import io.quarkiverse.desktop.packaging.deployment.macos.MachOTest.Signature;

/**
 * The signature of the application bundle and its notarization : the macOS libraries inside the jars, the renewed ad
 * hoc signature of {@code jpackage}, the command of {@code notarytool}.
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
        assertEquals(List.of("darwin-aarch64/libacme.dylib", "darwin/libacme.jnilib", "darwin/helper"),
                MacBundle.jarLibraries(jar(directory.resolve("natives.jar"), entries)));
        assertEquals(List.of(), MacBundle.jarLibraries(jar(directory.resolve("plain.jar"),
                Map.of("org/acme/Plain.class", MachOTest.CLASS_FILE))));

        // a signed jar cannot change : its libraries keep the signatures of their vendor
        assertEquals(List.of(), MacBundle.jarLibraries(jar(directory.resolve("vendor.jar"), signedJar())));
        Map<String, byte[]> unsigned = signedJar();
        unsigned.put("darwin/libother.dylib", MachOTest.library(Signature.AD_HOC));
        Path other = jar(directory.resolve("other.jar"), unsigned);
        PackagingException e = assertThrows(PackagingException.class, () -> MacBundle.jarLibraries(other));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.macos.sign.identity : other.jar is a signed jar"
                + " (META-INF/VENDOR.SF), and its macOS libraries [darwin/libother.dylib] are not signed with a"
                + " certificate."), e.getMessage());
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
        MacBundle.signJarLibraries(bundle, work, codesign(work, signed));
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
        Map<String, byte[]> unsigned = signedJar();
        unsigned.put("darwin/libother.dylib", MachOTest.library(Signature.AD_HOC));
        Path other = directory.resolve("Other.app");
        jar(other.resolve("Contents/app/lib/main/org.acme.other-1.0.jar"), unsigned);
        signed.clear();
        assertThrows(PackagingException.class, () -> MacBundle.signJarLibraries(other, work, codesign(work, signed)));
        assertEquals(List.of(), signed);
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
}
