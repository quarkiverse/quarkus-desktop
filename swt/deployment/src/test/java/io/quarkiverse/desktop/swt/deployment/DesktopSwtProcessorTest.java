package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.swt.deployment.SwtPlatformBuildItem.WindowingSystem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathTree;
import io.smallrye.common.os.OS;

/**
 * The static helpers of {@link DesktopSwtProcessor} : the SWT jar and its platform, its native libraries, and the
 * files generated for native executables.
 */
class DesktopSwtProcessorTest {

    // ------------------------------------------------------------------------------------------------------- SWT jar

    @Test
    void platformFromTheArtifactId() {
        SwtPlatformBuildItem platform = DesktopSwtProcessor.platform(swt("org.eclipse.swt.gtk.linux.aarch64"),
                new Manifest());
        assertEquals(WindowingSystem.GTK, platform.getWindowingSystem());
        assertEquals("linux", platform.getOs());
        assertEquals("aarch64", platform.getArch());
        assertTrue(platform.isLinux() && !platform.isWindows() && !platform.isMac());
        assertEquals("linux", platform.select("windows", "linux", "mac"));
        assertArrayEquals(new String[] { "c1", "c2", "l1", "l2" }, platform.withPlatform(new String[] { "c1", "c2" },
                new String[] { "w" }, new String[] { "l1", "l2" }, new String[] { "m" }));
        assertTrue(platform.toString().endsWith("(gtk, linux, aarch64)"), platform.toString());

        platform = DesktopSwtProcessor.platform(swt("org.eclipse.swt.win32.win32.x86_64"), new Manifest());
        assertEquals(WindowingSystem.WIN32, platform.getWindowingSystem());
        assertEquals("win32", platform.getOs());
        assertEquals("x86_64", platform.getArch());
        assertTrue(platform.isWindows() && !platform.isLinux() && !platform.isMac());
        assertEquals("windows", platform.select("windows", "linux", "mac"));
        assertArrayEquals(new String[] { "c", "w" }, platform.withPlatform(new String[] { "c" }, new String[] { "w" },
                new String[] { "l" }, new String[] { "m" }));
    }

    @Test
    void platformFromTheManifest() {
        // the attributes of the manifest win over the artifact id
        SwtPlatformBuildItem platform = DesktopSwtProcessor.platform(swt("org.eclipse.swt.gtk.linux.aarch64"),
                manifest("cocoa", "macosx", "aarch64"));
        assertEquals(WindowingSystem.COCOA, platform.getWindowingSystem());
        assertEquals("macosx", platform.getOs());
        assertEquals("aarch64", platform.getArch());
        assertTrue(platform.isMac() && !platform.isWindows() && !platform.isLinux());
        assertEquals("mac", platform.select("windows", "linux", "mac"));
        assertArrayEquals(new String[] { "m" }, platform.withPlatform(new String[0], new String[] { "w" },
                new String[] { "l" }, new String[] { "m" }));

        // the host bundle, with the attributes of a platform
        platform = DesktopSwtProcessor.platform(swt(DesktopSwtProcessor.SWT_HOST_ARTIFACT_ID),
                manifest("WIN32", "win32", "x86_64"));
        assertEquals(WindowingSystem.WIN32, platform.getWindowingSystem());
        assertEquals("win32", platform.getOs());
        assertEquals("x86_64", platform.getArch());
    }

    @Test
    void platformOfARepackagedJar() {
        // neither the attributes nor the artifact id of a platform : the platform of the build host
        SwtPlatformBuildItem platform = DesktopSwtProcessor.platform(dependency("com.example", "swt-bundle"),
                new Manifest());
        assertEquals(DesktopSwtProcessor.targetWindowingSystem(OS.current(), false), platform.getWindowingSystem());
        assertEquals("", platform.getOs());
        assertEquals("", platform.getArch());
    }

    /**
     * The SWT jar of the test class path is the one of the build host, which Maven adds to
     * {@code org.eclipse.platform:org.eclipse.swt}.
     */
    @Test
    void platformOfTheSwtJar() throws IOException {
        Path jar = SwtNativeAccessTest.swtJar();
        Manifest manifest;
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            manifest = file.getManifest();
        }
        SwtPlatformBuildItem platform = DesktopSwtProcessor.platform(swt(DesktopSwtProcessor.SWT_HOST_ARTIFACT_ID),
                manifest);
        assertEquals(DesktopSwtProcessor.targetWindowingSystem(OS.current(), false), platform.getWindowingSystem());
        assertEquals(DesktopSwtProcessor.hostArch(), platform.getArch());
        String fileName = jar.getFileName().toString();
        String hostJar = DesktopSwtProcessor.SWT_HOST_ARTIFACT_ID + "." + DesktopSwtProcessor.hostFragment() + "-";
        assertTrue(fileName.startsWith(hostJar), fileName + " is not the SWT jar of the build host");
        assertTrue(PathTree.ofArchive(jar).contains(DesktopSwtProcessor.LIBRARY_CLASS));
    }

    @Test
    void swtDependencies() {
        assertTrue(DesktopSwtProcessor.isSwt(swt(DesktopSwtProcessor.SWT_HOST_ARTIFACT_ID)));
        assertTrue(DesktopSwtProcessor.isSwt(swt("org.eclipse.swt.win32.win32.x86_64")));
        assertTrue(DesktopSwtProcessor.isSwt(swt("org.eclipse.swt.svg")));
        assertFalse(DesktopSwtProcessor.isSwt(swt("org.eclipse.swtbot")));
        assertFalse(DesktopSwtProcessor.isSwt(swt("org.eclipse.jface")));
        assertFalse(DesktopSwtProcessor.isSwt(dependency("com.example", DesktopSwtProcessor.SWT_HOST_ARTIFACT_ID)));
    }

    @Test
    void localBuildTargetsTheHost() {
        assertEquals(WindowingSystem.WIN32, DesktopSwtProcessor.targetWindowingSystem(OS.WINDOWS, false));
        assertEquals(WindowingSystem.COCOA, DesktopSwtProcessor.targetWindowingSystem(OS.MAC, false));
        assertEquals(WindowingSystem.GTK, DesktopSwtProcessor.targetWindowingSystem(OS.LINUX, false));
        assertEquals(WindowingSystem.GTK, DesktopSwtProcessor.targetWindowingSystem(OS.OTHER, false));
    }

    @Test
    void containerBuildTargetsLinux() {
        for (OS host : OS.values()) {
            assertEquals(WindowingSystem.GTK, DesktopSwtProcessor.targetWindowingSystem(host, true), host.name());
        }
    }

    @Test
    void nativeBuildWithTheSwtJarOfThePlatform() {
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                platform("org.eclipse.swt.cocoa.macosx.aarch64"), OS.MAC, "aarch64", false, false));
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                platform("org.eclipse.swt.win32.win32.x86_64"), OS.WINDOWS, "x86_64", false, false));
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                platform("org.eclipse.swt.gtk.linux.aarch64"), OS.LINUX, "aarch64", false, false));
        // a container build : the Linux jar
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                platform("org.eclipse.swt.gtk.linux.aarch64"), OS.MAC, "aarch64", true, false));
        // a jar without architecture (repackaged) : the one of the build host
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                Optional.of(DesktopSwtProcessor.platform(dependency("com.example", "swt-bundle"), new Manifest())),
                OS.current(), "aarch64", false, false));
    }

    @Test
    void nativeBuildWithTheSwtJarOfAnotherOperatingSystem() {
        // the jar of the build host in a container build
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DesktopSwtProcessor
                .checkTargetPlatform(platform("org.eclipse.swt.cocoa.macosx.aarch64"), OS.MAC, "aarch64", true, false));
        assertTrue(e.getMessage().contains("built for linux (a container build)"), e.getMessage());
        assertTrue(e.getMessage().contains("org.eclipse.platform:org.eclipse.swt.gtk.linux.aarch64"), e.getMessage());
        // no SWT jar
        e = assertThrows(IllegalStateException.class,
                () -> DesktopSwtProcessor.checkTargetPlatform(Optional.empty(), OS.LINUX, "x86_64", false, false));
        assertTrue(e.getMessage().contains("org.eclipse.platform:org.eclipse.swt.gtk.linux.x86_64"), e.getMessage());
        // a native sources build fails too : the sources are compiled for the operating system of the build host
        e = assertThrows(IllegalStateException.class, () -> DesktopSwtProcessor
                .checkTargetPlatform(platform("org.eclipse.swt.cocoa.macosx.x86_64"), OS.LINUX, "x86_64", false, true));
        assertTrue(e.getMessage().contains("org.eclipse.platform:org.eclipse.swt.gtk.linux.x86_64"), e.getMessage());
    }

    /**
     * The native executable could never load the native libraries of an SWT jar of another architecture, and running it
     * would extract them where every SWT application of that version loads them : the native build fails, unless it
     * runs in a container, whose builder image may run another architecture, or only generates the sources, which may
     * be compiled later on a machine of the architecture of the jar.
     */
    @Test
    void nativeBuildWithTheSwtJarOfAnotherArchitecture() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DesktopSwtProcessor
                .checkTargetPlatform(platform("org.eclipse.swt.cocoa.macosx.x86_64"), OS.MAC, "aarch64", false, false));
        assertTrue(e.getMessage().contains("org.eclipse.platform:org.eclipse.swt.cocoa.macosx.x86_64:3.132.0"),
                e.getMessage());
        assertTrue(e.getMessage().contains("Use the dependency org.eclipse.platform:org.eclipse.swt.cocoa.macosx"
                + ".aarch64"), e.getMessage());
        assertTrue(e.getMessage().contains("~/.swt/lib/macosx/aarch64"), e.getMessage());
        assertTrue(e.getMessage().contains("or build with a GraalVM of the architecture of the SWT jar"),
                e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> DesktopSwtProcessor
                .checkTargetPlatform(platform("org.eclipse.swt.win32.win32.aarch64"), OS.WINDOWS, "x86_64", false, false));
        assertTrue(e.getMessage().contains("Use the dependency org.eclipse.platform:org.eclipse.swt.win32.win32"
                + ".x86_64"), e.getMessage());
        // a container build only warns
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                platform("org.eclipse.swt.gtk.linux.x86_64"), OS.MAC, "aarch64", true, false));
        // a native sources build only warns
        assertDoesNotThrow(() -> DesktopSwtProcessor.checkTargetPlatform(
                platform("org.eclipse.swt.gtk.linux.aarch64"), OS.LINUX, "x86_64", false, true));
    }

    /**
     * The architecture of the native image builder : the {@code OS_ARCH} of the {@code release} file of the JDK of the
     * native build, as SWT names it, the one of the build host when it is unknown.
     */
    @Test
    void builderArch(@TempDir Path directory) throws IOException {
        Path jdkHome = Files.createDirectories(directory.resolve("jdk"));
        Path release = jdkHome.resolve("release");
        // as a GraalVM writes it
        Files.writeString(release, "IMPLEMENTOR=\"GraalVM Community\"\nJAVA_VERSION=\"25.0.1\"\nOS_ARCH=\"aarch64\"\n"
                + "OS_NAME=\"Darwin\"\nCOMMIT_INFO={\"vm\": {\"commit.rev\": \"95ce149\"}}\n", StandardCharsets.ISO_8859_1);
        assertEquals("aarch64", DesktopSwtProcessor.builderArch(jdkHome));
        Files.writeString(release, "OS_NAME=\"Linux\"\nOS_ARCH=\"x86_64\"\n", StandardCharsets.ISO_8859_1);
        assertEquals("x86_64", DesktopSwtProcessor.builderArch(jdkHome));
        // the names of os.arch
        Files.writeString(release, "OS_ARCH=\"amd64\"\r\n", StandardCharsets.ISO_8859_1);
        assertEquals("x86_64", DesktopSwtProcessor.builderArch(jdkHome));
        Files.writeString(release, "OS_ARCH=arm64\n", StandardCharsets.ISO_8859_1);
        assertEquals("aarch64", DesktopSwtProcessor.builderArch(jdkHome));

        // unknown : the architecture of the build host
        Files.writeString(release, "JAVA_VERSION=\"25.0.1\"\nOS_ARCH=\"\"\n", StandardCharsets.ISO_8859_1);
        assertEquals(DesktopSwtProcessor.hostArch(), DesktopSwtProcessor.builderArch(jdkHome));
        Files.delete(release);
        assertEquals(DesktopSwtProcessor.hostArch(), DesktopSwtProcessor.builderArch(jdkHome));
        assertEquals(DesktopSwtProcessor.hostArch(), DesktopSwtProcessor.builderArch(directory.resolve("none")));

        // the JDK running the tests
        assertEquals(DesktopSwtProcessor.hostArch(),
                DesktopSwtProcessor.builderArch(Path.of(System.getProperty("java.home"))));
    }

    /**
     * The JDK of the native build, chosen as Quarkus chooses its {@code native-image} : the GraalVM home, then the Java
     * home, when it has {@code bin/native-image}, else the {@code native-image} of the {@code PATH}, else the JDK of the
     * build.
     */
    @Test
    void builderJdkHome(@TempDir Path directory) throws IOException {
        Path graalvm = nativeImageHome(directory.resolve("graalvm"));
        Path javaHome = nativeImageHome(directory.resolve("java"));
        Path jdkWithout = Files.createDirectories(directory.resolve("jdk").resolve("bin"));
        Path fallback = directory.resolve("fallback");

        assertEquals(graalvm, DesktopSwtProcessor.builderJdkHome(Optional.of(graalvm.toString()), javaHome.toFile(),
                null, false, fallback));
        // a home without native-image is not the one Quarkus runs
        assertEquals(javaHome, DesktopSwtProcessor.builderJdkHome(Optional.of(jdkWithout.getParent().toString()),
                javaHome.toFile(), null, false, fallback));
        assertEquals(javaHome, DesktopSwtProcessor.builderJdkHome(Optional.of(" "), javaHome.toFile(), null, false,
                fallback));

        // the native-image of the PATH
        String plainPath = directory.resolve("empty") + File.pathSeparator + graalvm.resolve("bin");
        assertEquals(graalvm.toRealPath(), DesktopSwtProcessor.builderJdkHome(Optional.empty(),
                jdkWithout.getParent().toFile(), plainPath, false, fallback));
        // native-image.cmd on Windows
        assertEquals(fallback, DesktopSwtProcessor.builderJdkHome(Optional.of(graalvm.toString()), null, plainPath,
                true, fallback));
        assertEquals(fallback, DesktopSwtProcessor.builderJdkHome(Optional.empty(), null, null, false, fallback));

        // the native-image of the PATH, through links, as GraalVM installs it on Linux and macOS :
        // <home>/bin/native-image -> ../lib/svm/bin/native-image, the release file of the JDK in <home>
        Path svm = directory.resolve("svm-graalvm");
        Files.createFile(Files.createDirectories(svm.resolve("lib").resolve("svm").resolve("bin")).resolve("native-image"));
        Files.writeString(svm.resolve("release"), "OS_ARCH=\"aarch64\"\n");
        Files.createDirectories(svm.resolve("bin"));
        Path links = Files.createDirectories(directory.resolve("links"));
        try {
            Files.createSymbolicLink(svm.resolve("bin").resolve("native-image"),
                    Path.of("..", "lib", "svm", "bin", "native-image"));
            Files.createSymbolicLink(links.resolve("native-image"), svm.resolve("bin").resolve("native-image"));
        } catch (FileSystemException | UnsupportedOperationException e) {
            // Windows needs the Developer Mode, or an elevated process, to create links
            Assumptions.abort("Symbolic links cannot be created here : " + e);
        }
        for (Path bin : List.of(svm.resolve("bin"), links)) {
            String path = directory.resolve("empty") + File.pathSeparator + bin;
            assertEquals(svm.toRealPath(), DesktopSwtProcessor.builderJdkHome(Optional.empty(),
                    jdkWithout.getParent().toFile(), path, false, fallback), bin.toString());
        }
    }

    private static Path nativeImageHome(Path home) throws IOException {
        Files.createFile(Files.createDirectories(home.resolve("bin")).resolve("native-image"));
        return home;
    }

    @Test
    void swtArch() {
        assertEquals("x86_64", DesktopSwtProcessor.swtArch("amd64"));
        assertEquals("x86_64", DesktopSwtProcessor.swtArch("x86_64"));
        assertEquals("aarch64", DesktopSwtProcessor.swtArch("arm64"));
        assertEquals("aarch64", DesktopSwtProcessor.swtArch("aarch64"));
        assertEquals("ppc64le", DesktopSwtProcessor.swtArch("ppc64le"));
    }

    // ----------------------------------------------------------------------------------------------- native libraries

    @Test
    void nativeLibraryNames() {
        for (String library : List.of("swt-win32-4971r6.dll", "WebView2Loader.dll", "libswt-gtk-4971r6.so",
                "libswt-cocoa-4971r6.jnilib", "libswt-pi3-gtk-4971r6.so", "libswt.dylib")) {
            assertTrue(DesktopSwtProcessor.isNativeLibrary(library), library);
        }
        // the SWT_AWT bridge, which needs the AWT library of a JDK, and the other files
        for (String other : List.of("swt-awt-win32-4971r6.dll", "libswt-awt-gtk-4971r6.so",
                "libswt-awt-cocoa-4971r6.jnilib", "org/x/y.dll", "about.html", "META-INF/MANIFEST.MF")) {
            assertFalse(DesktopSwtProcessor.isNativeLibrary(other), other);
        }
    }

    @Test
    void mainNativeLibrary() {
        assertEquals(Optional.of("swt-win32-4971r6.dll"), DesktopSwtProcessor.mainNativeLibrary(
                List.of("WebView2Loader.dll", "swt-gdip-win32-4971r6.dll", "swt-win32-4971r6.dll",
                        "swt-wgl-win32-4971r6.dll"),
                WindowingSystem.WIN32));
        assertEquals(Optional.of("libswt-gtk-4971r6.so"), DesktopSwtProcessor.mainNativeLibrary(
                List.of("libswt-atk-gtk-4971r6.so", "libswt-cairo-gtk-4971r6.so", "libswt-gtk-4971r6.so",
                        "libswt-pi3-gtk-4971r6.so"),
                WindowingSystem.GTK));
        assertEquals(Optional.of("libswt-cocoa-4971r6.jnilib"), DesktopSwtProcessor.mainNativeLibrary(
                List.of("libswt-cocoa-4971r6.jnilib", "libswt-pi-cocoa-4971r6.jnilib"), WindowingSystem.COCOA));
        // the libraries of another platform
        assertEquals(Optional.empty(), DesktopSwtProcessor.mainNativeLibrary(List.of("libswt-gtk-4971r6.so"),
                WindowingSystem.WIN32));
        assertEquals(Optional.empty(), DesktopSwtProcessor.mainNativeLibrary(List.of(), WindowingSystem.COCOA));
    }

    @Test
    void nativeLibrariesOfTheSwtJar() {
        List<String> libraries = DesktopSwtProcessor.nativeLibraries(PathTree.ofArchive(SwtNativeAccessTest.swtJar()));
        assertFalse(libraries.isEmpty());
        assertEquals(libraries.stream().sorted().toList(), libraries);
        assertTrue(libraries.stream().allMatch(DesktopSwtProcessor::isNativeLibrary), libraries.toString());
        assertTrue(DesktopSwtProcessor.mainNativeLibrary(libraries,
                DesktopSwtProcessor.targetWindowingSystem(OS.current(), false)).isPresent(), libraries.toString());
    }

    // ------------------------------------------------------------------------------------------ native image options

    @Test
    void nativeImageProperties() {
        assertEquals("Args = -H:NativeLinkerOption=/SUBSYSTEM:WINDOWS -H:NativeLinkerOption=/ENTRY:mainCRTStartup\n",
                DesktopSwtProcessor.nativeImageProperties(List.of("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS",
                        "-H:NativeLinkerOption=/ENTRY:mainCRTStartup")));
        // backslashes escaped, characters outside ASCII as \\uXXXX
        assertEquals("Args = -H:Path=C:\\\\Users\\\\Jos\\u00E9\n",
                DesktopSwtProcessor.nativeImageProperties(List.of("-H:Path=C:\\Users\\Jos\u00e9")));
    }

    @Test
    void nativeImagePropertiesAreAscii() throws IOException {
        // a user name and directories with accents, another script, a character outside the BMP
        String path = "C:\\Users\\Jos\u00e9\\J\u00fcrgen\\\u30d7\u30ed\u30b8\u30a7\u30af\u30c8\\\ud83d\ude00\\target";
        List<String> args = List.of("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS",
                "-H:NativeLinkerOption=/LIBPATH:" + path);

        String content = DesktopSwtProcessor.nativeImageProperties(args);

        assertTrue(content.chars().allMatch(c -> c == '\n' || c >= ' ' && c <= '~'), content);
        assertTrue(content.endsWith("\n") && content.indexOf('\n') == content.length() - 1, content);
        // as native-image reads it (Properties.load(InputStream), ISO-8859-1)
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(String.join(" ", args), properties.getProperty("Args"));
        // and with a reader
        Properties read = new Properties();
        read.load(new StringReader(content));
        assertEquals(String.join(" ", args), read.getProperty("Args"));
    }

    @Test
    void exactReachabilityMetadataOptions() {
        assertTrue(DesktopSwtProcessor.isExactReachabilityMetadataOption("--exact-reachability-metadata"));
        assertTrue(DesktopSwtProcessor.isExactReachabilityMetadataOption(" --exact-reachability-metadata=com.example"));
        assertTrue(DesktopSwtProcessor.isExactReachabilityMetadataOption("--exact-reachability-metadata-path=a.jar"));
        assertTrue(DesktopSwtProcessor.isExactReachabilityMetadataOption("-H:ThrowMissingRegistrationErrors="));
        assertFalse(DesktopSwtProcessor.isExactReachabilityMetadataOption("-H:+UnlockExperimentalVMOptions"));
        assertFalse(DesktopSwtProcessor.isExactReachabilityMetadataOption("--enable-native-access=ALL-UNNAMED"));
    }

    @Test
    void runMainInNewThreadOptions() {
        assertTrue(DesktopSwtProcessor.isRunMainInNewThreadOption("-H:+RunMainInNewThread"));
        assertTrue(DesktopSwtProcessor.isRunMainInNewThreadOption(" -H:+RunMainInNewThread "));
        // the option that keeps main on the first thread
        assertFalse(DesktopSwtProcessor.isRunMainInNewThreadOption("-H:-RunMainInNewThread"));
        assertFalse(DesktopSwtProcessor.isRunMainInNewThreadOption("--exact-reachability-metadata"));
    }

    @Test
    void macExecutableArgs(@TempDir Path directory) throws IOException {
        // a JDK whose java launcher declares minos 11.0 and sdk 14.5
        Path jdkHome = SwtMacExecutableTest.jdkHome(directory.resolve("jdk"),
                SwtMacExecutableTest.machO(SwtMacExecutableTest.command(0x32, 24, 1, 0x000b0000, 0x000e0500, 0)));
        WindowingSystem macos = DesktopSwtProcessor.targetWindowingSystem(OS.MAC, false);
        List<String> args = DesktopSwtProcessor.macExecutableArgs(macos, true, false, jdkHome);
        assertEquals(List.of("-H:NativeLinkerOption=-Wl,-platform_version,macos,11.0,14.5"), args);
        assertEquals("Args = -H:NativeLinkerOption=-Wl,-platform_version,macos,11.0,14.5\n",
                DesktopSwtProcessor.nativeImageProperties(args));

        // quarkus.desktop.swt.macos.jdk-build-version=false
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(macos, false, false, jdkHome));
        // the executables of the other platforms, and a container build on macOS (a Linux executable)
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(WindowingSystem.GTK, true, false, jdkHome));
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(WindowingSystem.WIN32, true, false, jdkHome));
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(
                DesktopSwtProcessor.targetWindowingSystem(OS.MAC, true), true, false, jdkHome));
        // the Desktop AWT extension writes the versions itself
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(macos, true, true, jdkHome));
        // unknown versions : no java launcher, or the launcher of another platform
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(macos, true, false, directory.resolve("none")));
        Path linuxJdk = SwtMacExecutableTest.jdkHome(directory.resolve("linux"),
                "\u007fELF".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(List.of(), DesktopSwtProcessor.macExecutableArgs(macos, true, false, linuxJdk));
    }

    @Test
    void reachabilityMetadata() {
        String compact = DesktopSwtProcessor.reachabilityMetadata(List.of("a.B")).replaceAll("\\s", "");
        assertEquals("{\"reflection\":[{\"type\":\"a.B\"}]}", compact);
        compact = DesktopSwtProcessor.reachabilityMetadata(List.of("a.B", "c.D$E")).replaceAll("\\s", "");
        assertTrue(compact.startsWith("{\"reflection\":["), compact);
        assertTrue(compact.contains("{\"type\":\"a.B\"}"), compact);
        assertTrue(compact.contains("{\"type\":\"c.D$E\"}"), compact);
        assertEquals("{\"reflection\":[]}",
                DesktopSwtProcessor.reachabilityMetadata(List.of()).replaceAll("\\s", ""));
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private static ResolvedDependency swt(String artifactId) {
        return dependency(DesktopSwtProcessor.SWT_GROUP_ID, artifactId);
    }

    private static Optional<SwtPlatformBuildItem> platform(String artifactId) {
        return Optional.of(DesktopSwtProcessor.platform(swt(artifactId), new Manifest()));
    }

    private static ResolvedDependency dependency(String groupId, String artifactId) {
        return ResolvedDependencyBuilder.newInstance().setGroupId(groupId).setArtifactId(artifactId)
                .setVersion("3.132.0").build();
    }

    private static Manifest manifest(String ws, String os, String arch) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("SWT-WS", ws);
        attributes.putValue("SWT-OS", os);
        attributes.putValue("SWT-Arch", arch);
        return manifest;
    }
}
