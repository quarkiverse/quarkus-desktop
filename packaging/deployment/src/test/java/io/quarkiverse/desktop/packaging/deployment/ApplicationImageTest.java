package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
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
    }

    @Test
    void theOptionsOfTheWindowsExecutable(@TempDir Path directory) throws IOException {
        byte[] resources = WindowsResources.res(IconSet.generic(), TestConfig.app(), "Acme Editor.exe");
        Path file = DesktopPackagingProcessor.writeResources(directory, resources).orElseThrow();
        assertTrue(Files.isRegularFile(file));
        assertFalse(file.toString().chars().anyMatch(Character::isWhitespace), file.toString());
        assertEquals("Args = -H:NativeLinkerOption=C:\\\\caf\\u00E9\\\\application.res\n", DesktopPackagingProcessor
                .nativeImageProperties(List.of("-H:NativeLinkerOption=C:\\caf\u00e9\\application.res")));
    }
}
