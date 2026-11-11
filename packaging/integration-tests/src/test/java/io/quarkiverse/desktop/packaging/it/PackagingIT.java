package io.quarkiverse.desktop.packaging.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packages that the build of this module wrote ({@code target/packaging}) : the application image, whose launcher
 * starts the application, and a package of each format of {@code quarkus.desktop.packaging.formats} that the operating
 * system of the build has. In JVM mode and, with {@code -Dnative}, for the native executable.
 */
class PackagingIT {

    static final String NAME = "Packaging IT";

    static final String PACKAGE_NAME = "packaging-it";

    static final Path PACKAGING = Path.of(System.getProperty("packaging.directory", "target/packaging"));

    static final boolean NATIVE = Boolean.parseBoolean(System.getProperty("packaging.native", "false"));

    static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

    static final boolean WINDOWS = OS.contains("windows");

    static final boolean MAC = OS.contains("mac");

    static final boolean LINUX = !WINDOWS && !MAC;

    static List<String> formats() {
        List<String> formats = new ArrayList<>();
        for (String format : System.getProperty("packaging.formats", "directory").split(",")) {
            if (!format.isBlank()) {
                formats.add(format.trim().toLowerCase(Locale.ROOT));
            }
        }
        return formats;
    }

    /**
     * The application image : a directory with the launcher on Windows and Linux, an application bundle on macOS.
     */
    static Path image() {
        Path images = PACKAGING.resolve("image");
        return images.resolve(WINDOWS ? NAME : MAC ? NAME + ".app" : PACKAGE_NAME);
    }

    static Path launcher() {
        if (WINDOWS) {
            return image().resolve(NAME + ".exe");
        }
        if (MAC) {
            return image().resolve("Contents/MacOS").resolve(NAME);
        }
        // the native executable is at the root of the image, the launcher of jpackage in its bin directory
        return NATIVE ? image().resolve(PACKAGE_NAME) : image().resolve("bin").resolve(PACKAGE_NAME);
    }

    @Test
    void theLauncherOfTheApplicationImageStartsTheApplication(@TempDir Path directory) throws Exception {
        Path launcher = launcher();
        assertTrue(Files.isRegularFile(launcher), launcher + " : the launcher of the application image");
        Path marker = directory.resolve("marker.txt");
        Process process = new ProcessBuilder(launcher.toString(), "--marker=" + marker, "first", "second argument")
                .redirectErrorStream(true).redirectOutput(directory.resolve("output.txt").toFile()).start();
        try {
            assertTrue(process.waitFor(2, TimeUnit.MINUTES), "The application did not exit");
            String output = Files.readString(directory.resolve("output.txt"));
            assertEquals(0, process.exitValue(), output);
            assertTrue(Files.isRegularFile(marker), "The application did not write " + marker + " : " + output);
            String version = System.getProperty("packaging.version", "");
            assertEquals("started " + version + " [first, second argument]", Files.readString(marker));
        } finally {
            // an application that does not exit would keep its image and output.txt open : on Windows, the next build
            // could not delete them. Its child processes first : they are only found while it runs
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theApplicationImageHasTheContentDirectory() {
        Path readme = MAC ? image().resolve("Contents/Resources/README.txt") : image().resolve("README.txt");
        assertTrue(Files.isRegularFile(readme), readme + " : the file of src/main/packaging/content");
    }

    /**
     * The version information of the Windows native executable (the Details tab of its properties) : a resource that
     * the extension links in the executable, with its icon.
     */
    @Test
    void theWindowsNativeExecutableHasTheVersionInformation() throws IOException {
        assumeTrue(WINDOWS && NATIVE);
        byte[] executable = Files.readAllBytes(launcher());
        for (String string : List.of("ProductName", NAME, "CompanyName", "Quarkiverse", "OriginalFilename", NAME + ".exe")) {
            assertTrue(indexOf(executable, string.getBytes(StandardCharsets.UTF_16LE)) >= 0,
                    "The version information of " + launcher() + " : " + string);
        }
    }

    @Test
    void theMacBundleHasItsPropertyListAndIcon() throws IOException {
        assumeTrue(MAC);
        String plist = Files.readString(image().resolve("Contents/Info.plist"));
        assertTrue(plist.contains("io.quarkiverse.desktop.PackagingIt"), plist);
        try (Stream<Path> resources = Files.list(image().resolve("Contents/Resources"))) {
            assertTrue(resources.anyMatch(file -> file.getFileName().toString().endsWith(".icns")),
                    "The icon of the bundle, in Contents/Resources");
        }
    }

    @Test
    void msi() throws IOException {
        // a compound file
        assertMagic(packageOf("msi", WINDOWS), 0xd0, 0xcf, 0x11, 0xe0);
    }

    @Test
    void exe() throws IOException {
        assertMagic(packageOf("exe", WINDOWS), 'M', 'Z');
    }

    @Test
    void msix() throws IOException {
        Path msix = packageOf("msix", WINDOWS);
        try (ZipFile zip = new ZipFile(msix.toFile())) {
            assertNotNull(zip.getEntry("AppxManifest.xml"), "The manifest of " + msix);
            assertNotNull(zip.getEntry("Assets/Square150x150Logo.png"), "The logo of " + msix);
            // the names of the entries are percent-encoded
            assertNotNull(zip.getEntry("Packaging%20IT.exe"), "The launcher of " + msix);
            try (InputStream manifest = zip.getInputStream(zip.getEntry("AppxManifest.xml"))) {
                String xml = new String(manifest.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(xml.contains("Name=\"io.quarkiverse.desktop.PackagingIt\""), xml);
                assertTrue(xml.contains("Executable=\"Packaging IT.exe\""), xml);
            }
        }
    }

    @Test
    void dmg() throws IOException {
        assertTrue(Files.size(packageOf("dmg", MAC)) > 100_000);
    }

    @Test
    void pkg() throws IOException {
        // a xar archive
        assertMagic(packageOf("pkg", MAC), 'x', 'a', 'r', '!');
    }

    @Test
    void deb() throws Exception {
        Path deb = packageOf("deb", LINUX);
        assertMagic(deb, '!', '<', 'a', 'r', 'c', 'h', '>');
        String info = run("dpkg-deb", "--info", deb.toString());
        assertTrue(info.contains("Package: " + PACKAGE_NAME), info);
        assertTrue(info.contains("Maintainer: Quarkiverse"), info);
        String content = run("dpkg-deb", "--contents", deb.toString());
        assertTrue(content.contains("./opt/" + PACKAGE_NAME + "/"), content);
        assertTrue(content.contains("./usr/bin/" + PACKAGE_NAME + " -> /opt/" + PACKAGE_NAME + "/"), content);
        assertTrue(content.contains("./usr/share/applications/" + PACKAGE_NAME + ".desktop"), content);
        assertTrue(content.contains("./usr/share/icons/hicolor/256x256/apps/" + PACKAGE_NAME + ".png"), content);
        assertTrue(content.contains("root/root"), content);
    }

    @Test
    void rpm() throws Exception {
        Path rpm = packageOf("rpm", LINUX);
        assertMagic(rpm, 0xed, 0xab, 0xee, 0xdb);
        String info = run("rpm", "-qip", rpm.toString());
        assertTrue(info.contains(PACKAGE_NAME), info);
        String content = run("rpm", "-qlp", rpm.toString());
        assertTrue(content.contains("/opt/" + PACKAGE_NAME + "/"), content);
        assertTrue(content.contains("/usr/bin/" + PACKAGE_NAME), content);
        assertTrue(content.contains("/usr/share/applications/" + PACKAGE_NAME + ".desktop"), content);
    }

    @Test
    void appimage() throws Exception {
        Path appImage = packageOf("appimage", LINUX);
        assertMagic(appImage, 0x7f, 'E', 'L', 'F');
        assertTrue(Files.isExecutable(appImage), appImage + " is executable");
    }

    @Test
    void flatpak() throws IOException {
        assertMagic(packageOf("flatpak", LINUX), 'f', 'l', 'a', 't', 'p', 'a', 'k');
    }

    @Test
    void snap() throws IOException {
        // a squashfs file system
        assertMagic(packageOf("snap", LINUX), 'h', 's', 'q', 's');
    }

    /**
     * The package of a format, when the build was asked for it on its operating system (the test is skipped
     * otherwise).
     */
    static Path packageOf(String format, boolean operatingSystem) throws IOException {
        assumeTrue(operatingSystem && formats().contains(format), "No " + format + " package in this build");
        try (Stream<Path> files = Files.list(PACKAGING)) {
            List<Path> packages = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + format)).toList();
            assertEquals(1, packages.size(), "The " + format + " package of " + PACKAGING + " : " + packages);
            assertTrue(Files.size(packages.get(0)) > 0, packages.get(0) + " is empty");
            return packages.get(0);
        }
    }

    static void assertMagic(Path file, int... magic) throws IOException {
        byte[] expected = new byte[magic.length];
        for (int i = 0; i < magic.length; i++) {
            expected[i] = (byte) magic[i];
        }
        try (InputStream in = Files.newInputStream(file)) {
            assertArrayEquals(expected, in.readNBytes(magic.length), "The first bytes of " + file);
        }
    }

    static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + " : " + output);
        return output;
    }

    static int indexOf(byte[] bytes, byte[] pattern) {
        outer: for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
