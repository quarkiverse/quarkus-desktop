package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.deployment.windows.WindowsResources;
import io.quarkiverse.desktop.packaging.spi.NativeExecutableFileBuildItem;

/**
 * The application images : the one of a native executable, built here, and what the one of JVM mode gives to
 * {@code jpackage}.
 */
class ApplicationImageTest {

    static PackagingContext context(Path project, TargetOs os, Map<String, String> properties) throws IOException {
        DesktopPackagingConfig config = TestConfig.of(properties);
        return Packaging.context(config, "Acme Editor", "1.2.3", "org.acme", project, project.resolve("target"), os);
    }

    /**
     * A build directory with a native executable, its libraries, and files that are not part of the application.
     */
    static Path nativeBuild(Path project) throws IOException {
        Path target = Files.createDirectories(project.resolve("target"));
        for (String file : List.of("acme-runner", "acme-runner.exe", "awt.dll", "libawt.so", "libfreetype.so.6",
                "libawt.dylib", "libswt.jnilib", "acme.jar", "acme-runner-build-output-stats.json")) {
            Files.writeString(target.resolve(file), file);
        }
        return target;
    }

    @Test
    void theImageOfAWindowsNativeExecutable(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Path extra = Files.writeString(project.resolve("extra.dat"), "extra");
        Files.createDirectories(project.resolve("src/main/packaging/content/docs"));
        Files.writeString(project.resolve("src/main/packaging/content/docs/manual.txt"), "manual");

        PackagingContext context = context(project, TargetOs.WINDOWS, Map.of());
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner.exe"),
                List.of(new NativeExecutableFileBuildItem(extra, "data")));
        assertEquals(project.resolve("target/packaging/image/Acme Editor"), image.root());
        assertEquals("Acme Editor.exe", image.launcher());
        assertTrue(image.nativeExecutable());
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("awt.dll"), Path.of("data", "extra.dat"),
                Path.of("docs", "manual.txt")), FileTrees.files(image.root()));
        assertEquals("acme-runner.exe", Files.readString(image.launcherPath()));
    }

    @Test
    void theImageOfALinuxNativeExecutable(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        PackagingContext context = context(project, TargetOs.LINUX, Map.of("linux.package-name", "editor"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"), List.of());
        assertEquals(project.resolve("target/packaging/image/editor"), image.root());
        assertEquals("editor", image.launcher());
        assertEquals(List.of(Path.of("editor"), Path.of("libawt.so"), Path.of("libfreetype.so.6")),
                FileTrees.files(image.root()));
        // a second build replaces the image
        Files.delete(target.resolve("libawt.so"));
        assertEquals(List.of(Path.of("editor"), Path.of("libfreetype.so.6")),
                FileTrees.files(NativeApplicationImage.build(context, target.resolve("acme-runner"), List.of()).root()));
    }

    @Test
    void theBundleOfAMacNativeExecutable(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Files.createDirectories(project.resolve("content"));
        Files.writeString(project.resolve("content/manual.txt"), "manual");
        // not signed : codesign is a tool of macOS
        PackagingContext context = context(project, TargetOs.MACOS, Map.of("macos.sign.enabled", "false",
                "content-directory", "content"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"), List.of());
        assertEquals(project.resolve("target/packaging/image/Acme Editor.app"), image.root());
        assertEquals("Contents/MacOS/Acme Editor", image.launcher());
        assertEquals(List.of(Path.of("Contents", "Info.plist"), Path.of("Contents", "MacOS", "Acme Editor"),
                Path.of("Contents", "MacOS", "libawt.dylib"), Path.of("Contents", "MacOS", "libswt.jnilib"),
                Path.of("Contents", "PkgInfo"), Path.of("Contents", "Resources", "Acme-Editor.icns"),
                Path.of("Contents", "Resources", "manual.txt")), FileTrees.files(image.root()));
        String plist = Files.readString(image.root().resolve("Contents/Info.plist"));
        assertTrue(plist.contains("<string>org.acme.AcmeEditor</string>"), plist);
        assertTrue(plist.contains("<string>Acme-Editor.icns</string>"), plist);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Creating a symbolic link needs a privilege on Windows")
    void aFileOfTheExecutableThatIsNotCodeIsAResourceOfTheMacBundle(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Path extra = Files.writeString(project.resolve("extra.dat"), "extra");
        Path icu = Files.writeString(project.resolve("icudtl.dat"), "icu");
        // a Mach-O file : the magic number of the 64-bit ones
        Path library = Files.write(project.resolve("libnative.dylib"), new byte[] { (byte) 0xcf, (byte) 0xfa, (byte) 0xed,
                (byte) 0xfe, 0, 0, 0, 0 });
        // a file put in the resources by the producer itself
        Path notes = Files.writeString(project.resolve("notes.txt"), "notes");
        PackagingContext context = context(project, TargetOs.MACOS, Map.of("macos.sign.enabled", "false"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"),
                List.of(new NativeExecutableFileBuildItem(extra, "data"), new NativeExecutableFileBuildItem(icu),
                        new NativeExecutableFileBuildItem(library, "lib"),
                        new NativeExecutableFileBuildItem(notes, "../Resources")));
        Path contents = image.root().resolve("Contents");
        assertEquals(List.of(Path.of("Contents", "Info.plist"), Path.of("Contents", "MacOS", "Acme Editor"),
                Path.of("Contents", "MacOS", "data", "extra.dat"), Path.of("Contents", "MacOS", "icudtl.dat"),
                Path.of("Contents", "MacOS", "lib", "libnative.dylib"), Path.of("Contents", "MacOS", "libawt.dylib"),
                Path.of("Contents", "MacOS", "libswt.jnilib"), Path.of("Contents", "PkgInfo"),
                Path.of("Contents", "Resources", "Acme-Editor.icns"), Path.of("Contents", "Resources", "data", "extra.dat"),
                Path.of("Contents", "Resources", "icudtl.dat"), Path.of("Contents", "Resources", "notes.txt")),
                FileTrees.files(image.root()));
        assertEquals("extra", Files.readString(contents.resolve("Resources/data/extra.dat")));
        assertEquals(Path.of("../../Resources/data/extra.dat"),
                Files.readSymbolicLink(contents.resolve("MacOS/data/extra.dat")));
        assertEquals("extra", Files.readString(contents.resolve("MacOS/data/extra.dat")));
        assertEquals(Path.of("../Resources/icudtl.dat"), Files.readSymbolicLink(contents.resolve("MacOS/icudtl.dat")));
        assertEquals("icu", Files.readString(contents.resolve("MacOS/icudtl.dat")));
        // code stays next to the executable, and a file that is already a resource has no link
        assertTrue(Files.isRegularFile(contents.resolve("MacOS/lib/libnative.dylib"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(contents.resolve("Resources/notes.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void theLinkOfAFileThatIsNotCodeInAMacBundle() {
        assertEquals("../Resources/extra.dat", NativeApplicationImage.macosResourceLink("", "extra.dat"));
        assertEquals("../../Resources/data/extra.dat", NativeApplicationImage.macosResourceLink("data", "extra.dat"));
        assertEquals("../../../Resources/a/b/extra.dat", NativeApplicationImage.macosResourceLink("a/b", "extra.dat"));
    }

    @Test
    void aMissingFileOfTheExecutableFailsTheBuild(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        PackagingContext context = context(project, TargetOs.LINUX, Map.of());
        assertThrows(PackagingException.class, () -> NativeApplicationImage.build(context, target.resolve("acme-runner"),
                List.of(new NativeExecutableFileBuildItem(project.resolve("missing.so")))));
        PackagingContext missingContent = context(project, TargetOs.LINUX, Map.of("content-directory", "missing"));
        assertThrows(PackagingException.class,
                () -> NativeApplicationImage.build(missingContent, target.resolve("acme-runner"), List.of()));
    }

    @Test
    void theSharedLibrariesOfEachOperatingSystem() {
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.WINDOWS, "AWT.DLL"));
        assertFalse(NativeApplicationImage.isSharedLibrary(TargetOs.WINDOWS, "libawt.so"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.LINUX, "libawt.so"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.LINUX, "libz.so.1.2.13"));
        assertFalse(NativeApplicationImage.isSharedLibrary(TargetOs.LINUX, "notes.solution"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.MACOS, "libawt.dylib"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.MACOS, "libswt-cocoa-4970r1.jnilib"));
    }

    @Test
    void theModulesOfTheJavaRuntimeIncludeTheOnesOfQuarkus() {
        assertEquals(List.of("java.desktop", "java.logging", "java.naming", "jdk.unsupported", "jdk.zipfs"),
                JvmApplicationImage.modules(List.of("java.desktop", " java.logging")));
    }

    @Test
    void theLauncherOfAFastJarHasTheRunnerJarAsItsClassPath() {
        String configuration = String.join("\n", "[Application]", "app.mainjar=$APPDIR\\quarkus-run.jar",
                "app.classpath=$APPDIR\\app\\acme.jar", "app.classpath=$APPDIR\\lib\\main\\io.quarkus.quarkus-core.jar", "",
                "[JavaOptions]", "java-options=-Djpackage.app-version=1.2.3", "");
        assertEquals(String.join(System.lineSeparator(), "[Application]", "app.mainjar=$APPDIR\\quarkus-run.jar", "",
                "[JavaOptions]", "java-options=-Djpackage.app-version=1.2.3", ""),
                JvmApplicationImage.runnerJarOnly(configuration));
        // the jpackage of JDK 17 puts the main jar on the class path : without it, the launcher would have no class path
        assertThrows(PackagingException.class, () -> JvmApplicationImage.runnerJarOnly(String.join("\n", "[Application]",
                "app.classpath=$APPDIR\\quarkus-run.jar", "app.mainclass=io.quarkus.bootstrap.runner.QuarkusEntryPoint",
                "app.classpath=$APPDIR\\app\\acme.jar", "")));
    }

    /**
     * A JDK with a {@code jpackage} file, without the {@code .exe} extension on Windows too : an antivirus may still be
     * reading a new executable when the test deletes its directory.
     *
     * @return the path of {@code jpackage}
     */
    static Path jdk(Path home) throws IOException {
        return Files.writeString(Files.createDirectories(home.resolve("bin")).resolve("jpackage"), "");
    }

    @Test
    void theJpackageOfTheJdkComesBeforeTheOneOfThePath(@TempDir Path directory) throws IOException {
        Path configured = jdk(directory.resolve("jdk-21"));
        Path build = jdk(directory.resolve("jdk-25"));
        Path other = jdk(directory.resolve("jdk-17"));
        Path jre = Files.createDirectories(directory.resolve("jre/bin")).getParent();
        Supplier<Optional<Path>> path = () -> Optional.of(other);

        // the configured JDK, the JDK of the build, then the PATH when the JDK of the build has no jpackage
        assertEquals(configured, JvmApplicationImage.jpackage(Optional.empty(), Optional.of(directory.resolve("jdk-21")),
                directory.resolve("jdk-25"), "jpackage", path));
        assertEquals(build, JvmApplicationImage.jpackage(Optional.empty(), Optional.empty(), directory.resolve("jdk-25"),
                "jpackage", path));
        assertEquals(other, JvmApplicationImage.jpackage(Optional.empty(), Optional.empty(), jre, "jpackage", path));
        assertThrows(PackagingException.class,
                () -> JvmApplicationImage.jpackage(Optional.empty(), Optional.empty(), jre, "jpackage", Optional::empty));
        // a configured JDK without jpackage fails the build, rather than leaving it to the PATH
        PackagingException e = assertThrows(PackagingException.class, () -> JvmApplicationImage.jpackage(Optional.empty(),
                Optional.of(jre), directory.resolve("jdk-25"), "jpackage", path));
        assertTrue(e.getMessage().contains("quarkus.desktop.packaging.jvm.jdk"), e.getMessage());

        // quarkus.desktop.packaging.tools.jpackage comes first
        Optional<Path> tool = new Tools(Map.of("jpackage", other.toString())).configured("jpackage");
        assertEquals(Optional.of(other), tool);
        assertEquals(other, JvmApplicationImage.jpackage(tool, Optional.of(directory.resolve("jdk-21")),
                directory.resolve("jdk-25"), "jpackage", path));
        assertEquals(Optional.empty(), new Tools(Map.of()).configured("jpackage"));
        assertEquals(Optional.empty(), new Tools(Map.of("jpackage", " ")).configured("jpackage"));
        assertThrows(PackagingException.class,
                () -> new Tools(Map.of("jpackage", jre.resolve("bin/jpackage").toString())).configured("jpackage"));
    }

    @Test
    void theJpackageOfJdk21OrLater() {
        assertEquals(17, JvmApplicationImage.featureVersion("17.0.10\r\n"));
        assertEquals(21, JvmApplicationImage.featureVersion("21.0.2\n"));
        assertEquals(25, JvmApplicationImage.featureVersion("25"));
        assertEquals(26, JvmApplicationImage.featureVersion("26-ea"));
        assertEquals(25, JvmApplicationImage.featureVersion("Picked up JAVA_TOOL_OPTIONS: -Xmx1g\n25.0.4.1\n"));
        assertEquals(-1, JvmApplicationImage.featureVersion("jpackage"));
        assertEquals(-1, JvmApplicationImage.featureVersion(""));

        Path jpackage = Path.of("jdk-17", "bin", "jpackage");
        PackagingException e = assertThrows(PackagingException.class,
                () -> JvmApplicationImage.checkVersion(jpackage, "17.0.10"));
        assertTrue(e.getMessage().contains("quarkus.desktop.packaging.jvm.jdk"), e.getMessage());
        JvmApplicationImage.checkVersion(jpackage, "21.0.2");
        JvmApplicationImage.checkVersion(jpackage, "25");
        // a version that cannot be read is left to jpackage
        JvmApplicationImage.checkVersion(jpackage, "");
    }

    /**
     * A version that starts with zero, which {@code jpackage} refuses on macOS : the bundle and the application get it
     * all the same.
     */
    @Test
    void theVersionOfAMacApplicationThatStartsWithZero() {
        assertEquals("1.9.1", JvmApplicationImage.jpackageVersion(TargetOs.MACOS, "0.9.1"));
        assertEquals("1.2.3", JvmApplicationImage.jpackageVersion(TargetOs.MACOS, "1.2.3"));
        assertEquals("0.9.1", JvmApplicationImage.jpackageVersion(TargetOs.LINUX, "0.9.1"));
        assertEquals(Optional.of("-Djpackage.app-version=0.9.1"),
                JvmApplicationImage.versionOption(TargetOs.MACOS, "0.9.1"));
        assertEquals(Optional.empty(), JvmApplicationImage.versionOption(TargetOs.MACOS, "1.2.3"));
        assertEquals(Optional.empty(), JvmApplicationImage.versionOption(TargetOs.WINDOWS, "0.9.1"));
    }

    @Test
    void aJavaRuntimeIsBuiltWithItsModulesOrGiven() {
        Path project = Path.of("project");
        assertEquals(List.of("--add-modules", "java.desktop,java.logging,java.naming,jdk.unsupported,jdk.zipfs",
                "--jlink-options", "--strip-debug --no-man-pages"),
                JvmApplicationImage.runtimeArguments(Optional.of(List.of("java.desktop")),
                        Optional.of(List.of("--strip-debug", "--no-man-pages")), Optional.empty(), project));
        assertEquals(List.of(), JvmApplicationImage.runtimeArguments(Optional.empty(), Optional.empty(), Optional.empty(),
                project));
        assertEquals(List.of("--runtime-image", project.resolve("runtime").toString()), JvmApplicationImage
                .runtimeArguments(Optional.of(List.of()), Optional.of(List.of()), Optional.of("runtime"), project));

        // jpackage takes the given runtime as it is
        PackagingException modules = assertThrows(PackagingException.class, () -> JvmApplicationImage
                .runtimeArguments(Optional.of(List.of("java.desktop")), Optional.empty(), Optional.of("runtime"), project));
        assertTrue(modules.getMessage().contains("quarkus.desktop.packaging.jvm.runtime-image"), modules.getMessage());
        assertTrue(modules.getMessage().contains("quarkus.desktop.packaging.jvm.modules"), modules.getMessage());
        assertFalse(modules.getMessage().contains("jlink-options"), modules.getMessage());
        PackagingException options = assertThrows(PackagingException.class, () -> JvmApplicationImage
                .runtimeArguments(Optional.empty(), Optional.of(List.of("--strip-debug")), Optional.of("runtime"), project));
        assertTrue(options.getMessage().contains("quarkus.desktop.packaging.jvm.jlink-options"), options.getMessage());
        assertFalse(options.getMessage().contains("jvm.modules"), options.getMessage());
    }

    /**
     * The options of {@code jlink} that strip the native libraries with the {@code objcopy} of the {@code PATH}, on
     * Linux.
     */
    @Test
    void theOptionsOfJlinkThatNeedObjcopy() {
        // the default options of jpackage have --strip-debug
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.empty(), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.of(List.of()), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.of(List.of("--strip-debug", "--no-man-pages")), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.of(List.of("--no-man-pages -G")), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-native-debug-symbols=exclude-debuginfo-files")), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-native-debug-symbols", "keep-debuginfo-files")), false));
        assertFalse(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-java-debug-attributes", "--no-header-files")), false));
        // the objcopy of the options
        assertFalse(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-native-debug-symbols", "objcopy=/opt/binutils/bin/objcopy")), false));
        // a given runtime : no jlink
        assertFalse(JvmApplicationImage.stripsNativeSymbols(Optional.empty(), true));
    }

    /**
     * The name of the menu bar that {@code jpackage} writes in the bundle of a JVM application, at most 16 characters,
     * is replaced by the whole name of the application, as in the bundle of a native executable.
     */
    @Test
    void theBundleOfAJvmApplicationHasTheWholeName() {
        String plist = String.join("\n", "<plist version=\"1.0\">", " <dict>", "  <key>CFBundleExecutable</key>",
                "  <string>Acme Document Editor</string>", "  <key>CFBundleName</key>", "  <string>Acme Document Ed</string>",
                "  <key>CFBundleDisplayName</key>", "  <string>Acme Document Ed</string>", " </dict>", "</plist>", "");
        assertEquals(String.join("\n", "<plist version=\"1.0\">", " <dict>", "  <key>CFBundleExecutable</key>",
                "  <string>Acme Document Editor</string>", "  <key>CFBundleName</key>",
                "  <string>Acme Document Editor</string>", "  <key>CFBundleDisplayName</key>",
                "  <string>Acme Document Ed</string>", " </dict>", "</plist>", ""),
                JvmApplicationImage.withBundleName(plist, "Acme Document Editor"));
        assertTrue(JvmApplicationImage.withBundleName(plist, "Acme & Sons <Document Editor>")
                .contains("<key>CFBundleName</key>\n  <string>Acme &amp; Sons &lt;Document Editor&gt;</string>"));
        String withoutName = plist.replace("CFBundleName", "CFBundleSignature");
        assertEquals(withoutName, JvmApplicationImage.withBundleName(withoutName, "Acme Document Editor"));
    }

    @Test
    void theInputOfJpackageHasNoStartupArchive(@TempDir Path directory) throws IOException {
        Path uberJar = Files.writeString(directory.resolve("acme-runner.jar"), "uber-jar");
        Path uberJarInput = JvmApplicationImage.input(Files.createDirectories(directory.resolve("uber-jar")), uberJar, true,
                Optional.empty());
        assertEquals(List.of(Path.of("acme-runner.jar")), FileTrees.files(uberJarInput));

        Path application = directory.resolve("quarkus-app");
        Path jar = FileTrees.write(application.resolve("quarkus-run.jar"), "fast-jar");
        FileTrees.write(application.resolve("lib/boot/acme.jar"), "acme");
        Path work = Files.createDirectories(directory.resolve("work"));
        // without archive : the directory of the fast-jar
        assertEquals(application.toAbsolutePath(), JvmApplicationImage.input(work, jar, false, Optional.empty()));
        // with an archive : a copy of the directory without it, which the build keeps
        Path archive = Files.writeString(application.resolve("app-cds.jsa"), "archive");
        Path input = JvmApplicationImage.input(work, jar, false, Optional.of(archive));
        assertEquals(work.resolve("input"), input);
        assertEquals(List.of(Path.of("lib", "boot", "acme.jar"), Path.of("quarkus-run.jar")), FileTrees.files(input));
        assertTrue(Files.isRegularFile(archive));
        // the shared classes cache of OpenJ9 : a directory
        Files.delete(archive);
        Path cache = Files.createDirectories(application.resolve("app-scc"));
        Files.writeString(cache.resolve("cache"), "cache");
        assertEquals(List.of(Path.of("lib", "boot", "acme.jar"), Path.of("quarkus-run.jar")),
                FileTrees.files(JvmApplicationImage.input(work, jar, false, Optional.of(cache))));
    }

    @Test
    void theOptionsOfTheWindowsExecutable() {
        assertEquals("Args = -H:NativeLinkerOption=C:\\\\caf\\u00E9\\\\application.res\n", DesktopPackagingProcessor
                .nativeImageProperties(List.of("-H:NativeLinkerOption=C:\\caf\u00e9\\application.res")));
    }

    @Test
    void theResourcesOfTheWindowsExecutable(@TempDir Path directory) throws IOException {
        // the only white space of the paths is the one that the test gives them
        assumeFalse(hasWhiteSpace(directory), directory.toString());
        byte[] resources = WindowsResources.res(IconSet.generic(), TestConfig.app(), "Acme Editor.exe");
        Path temporary = directory.resolve("tmp");

        // in the build directory
        Path file = DesktopPackagingProcessor.writeResources(directory.resolve("build"), temporary, resources)
                .orElseThrow();
        assertEquals(directory.resolve("build").toAbsolutePath().resolve("quarkus-desktop-packaging")
                .resolve("application.res"), file);
        assertArrayEquals(resources, Files.readAllBytes(file));

        // native-image splits its options on white space : a new directory of the temporary directory at each build
        Path first = DesktopPackagingProcessor.writeResources(directory.resolve("build dir"), temporary, resources)
                .orElseThrow();
        Path second = DesktopPackagingProcessor.writeResources(directory.resolve("build dir"), temporary, resources)
                .orElseThrow();
        for (Path temporaryFile : List.of(first, second)) {
            assertEquals("application.res", temporaryFile.getFileName().toString());
            assertEquals(temporary.toAbsolutePath(), temporaryFile.getParent().getParent());
            assertTrue(temporaryFile.getParent().getFileName().toString().startsWith("quarkus-desktop-packaging-"),
                    temporaryFile.toString());
            assertFalse(hasWhiteSpace(temporaryFile), temporaryFile.toString());
            assertArrayEquals(resources, Files.readAllBytes(temporaryFile));
        }
        assertNotEquals(first.getParent(), second.getParent());
        assertFalse(Files.exists(directory.resolve("build dir")));

        // no directory without white space
        assertEquals(Optional.empty(), DesktopPackagingProcessor.writeResources(directory.resolve("build dir"),
                directory.resolve("tmp dir"), resources));
        assertFalse(Files.exists(directory.resolve("tmp dir")));
        assertFalse(Files.exists(directory.resolve("build dir")));
    }

    /**
     * Whether a path has white space, which native-image splits its options on.
     */
    private static boolean hasWhiteSpace(Path path) {
        return path.toString().chars().anyMatch(Character::isWhitespace);
    }
}
