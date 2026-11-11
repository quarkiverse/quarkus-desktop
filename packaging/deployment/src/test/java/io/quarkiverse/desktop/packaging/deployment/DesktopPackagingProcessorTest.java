package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.spi.PackagedApplicationNameBuildItem;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.PackageConfig.JarConfig.JarType;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageRunnerBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.pkg.steps.NativeImageBuildLocalRunner;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * The build steps of the packages.
 */
class DesktopPackagingProcessorTest {

    /**
     * A native executable compressed with UPX hides its shared libraries from the automatic dependencies of the Debian
     * and RPM packages.
     */
    @Test
    void theWarningOfACompressedNativeExecutable() {
        DesktopPackagingConfig.Linux config = TestConfig.defaults().linux();
        String deb = DesktopPackagingProcessor.compressionWarning(config, List.of(PackageFormat.DEB), true).orElseThrow();
        assertTrue(deb.contains("quarkus.native.compression.level"), deb);
        assertTrue(deb.contains("quarkus.desktop.packaging.linux.deb.depends"), deb);
        assertFalse(deb.contains("rpm.requires"), deb);
        String rpm = DesktopPackagingProcessor.compressionWarning(config, List.of(PackageFormat.RPM), true).orElseThrow();
        assertTrue(rpm.contains("quarkus.desktop.packaging.linux.rpm.requires"), rpm);
        assertFalse(rpm.contains("deb.depends"), rpm);
        String both = DesktopPackagingProcessor.compressionWarning(config,
                List.of(PackageFormat.DIRECTORY, PackageFormat.DEB, PackageFormat.RPM), true).orElseThrow();
        assertTrue(both.contains("quarkus.desktop.packaging.linux.deb.depends and"
                + " quarkus.desktop.packaging.linux.rpm.requires"), both);

        // not compressed, no format with automatic dependencies, or no automatic dependencies
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.compressionWarning(config, List.of(PackageFormat.DEB, PackageFormat.RPM), false));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.compressionWarning(config,
                List.of(PackageFormat.DIRECTORY, PackageFormat.APPIMAGE, PackageFormat.FLATPAK, PackageFormat.SNAP), true));
        DesktopPackagingConfig.Linux manual = TestConfig.of(Map.of("linux.deb.auto-depends", "false",
                "linux.rpm.auto-requires", "false")).linux();
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.compressionWarning(manual, List.of(PackageFormat.DEB, PackageFormat.RPM), true));
    }

    /**
     * Quarkus does not compress the executable of a former build, which the former build compressed in place : its
     * header shows it, not the configuration.
     */
    @Test
    void theCompressionOfAReusedNativeExecutable(@TempDir Path directory) throws IOException {
        // the ELF header, then the magic number of UPX after the headers of its loader
        byte[] compressed = new byte[4096];
        compressed[0] = 0x7f;
        compressed[1] = 'E';
        compressed[2] = 'L';
        compressed[3] = 'F';
        System.arraycopy("UPX!".getBytes(StandardCharsets.US_ASCII), 0, compressed, 448, 4);
        assertTrue(DesktopPackagingProcessor.hasUpxMagic(compressed));
        byte[] atTheEnd = new byte[4096];
        System.arraycopy("UPX!".getBytes(StandardCharsets.US_ASCII), 0, atTheEnd, 4092, 4);
        assertTrue(DesktopPackagingProcessor.hasUpxMagic(atTheEnd));
        byte[] cut = new byte[4096];
        System.arraycopy("UPX".getBytes(StandardCharsets.US_ASCII), 0, cut, 4093, 3);
        assertFalse(DesktopPackagingProcessor.hasUpxMagic(cut));
        assertFalse(DesktopPackagingProcessor.hasUpxMagic(new byte[4096]));
        assertFalse(DesktopPackagingProcessor.hasUpxMagic(new byte[0]));

        // compressed by this build : the file is not read
        Path missing = directory.resolve("missing-runner");
        assertTrue(DesktopPackagingProcessor.isUpxCompressed(true, false, missing));
        // an executable of this build that Quarkus did not compress, and the executables of a former build
        Path upx = Files.write(directory.resolve("upx-runner"), compressed);
        Path plain = Files.write(directory.resolve("plain-runner"), cut);
        assertFalse(DesktopPackagingProcessor.isUpxCompressed(false, false, upx));
        assertTrue(DesktopPackagingProcessor.isUpxCompressed(false, true, upx));
        assertFalse(DesktopPackagingProcessor.isUpxCompressed(false, true, plain));
        assertFalse(DesktopPackagingProcessor.isUpxCompressed(false, true, missing));
    }

    /**
     * A resource file that the application links itself has the identifiers of the icon and of the version
     * information of the one of the extension : the extension does not link its own then.
     */
    @Test
    void theResourceFileThatTheApplicationLinks() {
        assertEquals(Optional.of("C:\\app\\app.res"), DesktopPackagingProcessor
                .linkedResourceFile(List.of("--verbose", "-H:NativeLinkerOption=C:\\app\\app.res")));
        assertEquals(Optional.of("C:\\my app\\APP.RES"), DesktopPackagingProcessor
                .linkedResourceFile(List.of(" -H:NativeLinkerOption=\"C:\\my app\\APP.RES\" ")));
        // other linker options and files
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.linkedResourceFile(List.of("-H:NativeLinkerOption=/STACK:8388608")));
        assertEquals(Optional.empty(),
                DesktopPackagingProcessor.linkedResourceFile(List.of("-H:NativeLinkerOption=user32.lib")));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.linkedResourceFile(List.of("-H:Name=app.res")));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.linkedResourceFile(List.of()));
    }

    /**
     * The resources of the Windows executable are linked from a file of the build : not for native sources nor for a
     * native-image bundle, which are compiled or applied later, maybe on another machine.
     */
    @Test
    void theResourcesOfAnExecutableThatIsLinkedLater() {
        assertEquals(Optional.empty(), DesktopPackagingProcessor.executableResourcesUnavailable(false, false));
        String sources = DesktopPackagingProcessor.executableResourcesUnavailable(true, false).orElseThrow();
        assertTrue(sources.startsWith("The native sources do not give the native executable"), sources);
        String bundle = DesktopPackagingProcessor.executableResourcesUnavailable(false, true).orElseThrow();
        assertTrue(bundle.startsWith("The native-image bundle does not give the native executable"), bundle);
        assertTrue(DesktopPackagingProcessor.executableResourcesUnavailable(true, true).isPresent());
    }

    /**
     * The directory of its own that the resource file has in the temporary directory is deleted at the end of the
     * build : not with profile-guided optimization, whose optimized executable is linked from the same file after the
     * integration tests, and never the build directory.
     */
    @Test
    void theResourcesOfTheTemporaryDirectoryAreDeleted() {
        Path build = Path.of("my project", "target").toAbsolutePath();
        Path temporary = Path.of("tmp", "quarkus-desktop-packaging-1", "application.res").toAbsolutePath();
        assertTrue(DesktopPackagingProcessor.deletesResources(temporary, build, false));
        assertFalse(DesktopPackagingProcessor.deletesResources(temporary, build, true));
        Path inBuild = build.resolve("quarkus-desktop-packaging").resolve("application.res");
        assertFalse(DesktopPackagingProcessor.deletesResources(inBuild, build, false));
        assertFalse(DesktopPackagingProcessor.deletesResources(inBuild, build, true));
    }

    /**
     * A native executable built in a container is a Linux executable : on Windows and macOS, it is not packaged.
     */
    @Test
    void theFormatsOfANativeExecutable() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("formats", "msi,dmg,deb,directory"));
        assertEquals(List.of(), DesktopPackagingProcessor.nativeFormats(config, TargetOs.LINUX, TargetOs.WINDOWS));
        assertEquals(List.of(), DesktopPackagingProcessor.nativeFormats(config, TargetOs.LINUX, TargetOs.MACOS));
        // a container build on Linux, and a build without a container
        assertEquals(List.of(PackageFormat.DEB, PackageFormat.DIRECTORY),
                DesktopPackagingProcessor.nativeFormats(config, TargetOs.LINUX, TargetOs.LINUX));
        assertEquals(List.of(PackageFormat.MSI, PackageFormat.DIRECTORY),
                DesktopPackagingProcessor.nativeFormats(config, TargetOs.WINDOWS, TargetOs.WINDOWS));
        assertEquals(List.of(), DesktopPackagingProcessor.nativeFormats(TestConfig.defaults(), TargetOs.LINUX,
                TargetOs.WINDOWS));
    }

    /**
     * The packages of a native executable are checked before it is built, when the build packages it.
     */
    @Test
    void theFormatsCheckedBeforeTheNativeBuild() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("formats", "msi,deb,directory"));
        assertEquals(List.of(PackageFormat.DEB, PackageFormat.DIRECTORY),
                DesktopPackagingProcessor.checkedFormats(config, TargetOs.LINUX, TargetOs.LINUX, false, false));
        assertEquals(List.of(PackageFormat.MSI, PackageFormat.DIRECTORY),
                DesktopPackagingProcessor.checkedFormats(config, TargetOs.WINDOWS, TargetOs.WINDOWS, false, false));
        // a container build on Windows, a native-image bundle, an instrumented executable
        assertEquals(List.of(),
                DesktopPackagingProcessor.checkedFormats(config, TargetOs.LINUX, TargetOs.WINDOWS, false, false));
        assertEquals(List.of(),
                DesktopPackagingProcessor.checkedFormats(config, TargetOs.LINUX, TargetOs.LINUX, true, false));
        assertEquals(List.of(),
                DesktopPackagingProcessor.checkedFormats(config, TargetOs.LINUX, TargetOs.LINUX, false, true));
        // no packages
        assertEquals(List.of(), DesktopPackagingProcessor.checkedFormats(
                TestConfig.of(Map.of("enabled", "false", "formats", "deb")), TargetOs.LINUX, TargetOs.LINUX, false, false));
        assertEquals(List.of(), DesktopPackagingProcessor.checkedFormats(TestConfig.defaults(), TargetOs.LINUX,
                TargetOs.LINUX, false, false));
    }

    /**
     * A native-image bundle is built instead of the executable, and the executable of a build with profile-guided
     * optimization is instrumented : the optimized one is packaged by a second build, which reuses it.
     */
    @Test
    void aNativeExecutableThatIsNotPackaged() {
        assertEquals(Optional.empty(), DesktopPackagingProcessor.nativeExecutableUnavailable(false, false, false));
        String bundle = DesktopPackagingProcessor.nativeExecutableUnavailable(true, false, false).orElseThrow();
        assertTrue(bundle.contains("quarkus.native.bundle"), bundle);
        bundle = DesktopPackagingProcessor.nativeExecutableUnavailable(true, true, false).orElseThrow();
        assertTrue(bundle.contains("quarkus.native.bundle"), bundle);
        String pgo = DesktopPackagingProcessor.nativeExecutableUnavailable(false, true, false).orElseThrow();
        assertTrue(pgo.contains("quarkus.native.pgo.enabled"), pgo);
        assertTrue(pgo.contains("-Dquarkus.native.reuse-existing=true"), pgo);
        // the executable of a former build, which the native build returns without building a bundle or an executable
        assertEquals(Optional.empty(), DesktopPackagingProcessor.nativeExecutableUnavailable(false, true, true));
        assertEquals(Optional.empty(), DesktopPackagingProcessor.nativeExecutableUnavailable(true, false, true));
    }

    /**
     * A JVM application is packaged from a fast-jar, from an aot-jar, which has its layout (the default type with
     * {@code quarkus.package.jar.aot.enabled}), or from an uber-jar.
     */
    @Test
    @SuppressWarnings("deprecation")
    void theJarsThatArePackaged() {
        DesktopPackagingProcessor.checkJarType(JarType.FAST_JAR, false);
        DesktopPackagingProcessor.checkJarType(JarType.AOT_JAR, false);
        DesktopPackagingProcessor.checkJarType(JarType.UBER_JAR, true);
        for (JarType type : List.of(JarType.MUTABLE_JAR, JarType.LEGACY_JAR)) {
            PackagingException e = assertThrows(PackagingException.class,
                    () -> DesktopPackagingProcessor.checkJarType(type, false), type.toString());
            assertTrue(e.getMessage().contains("not from a " + type + " : set quarkus.package.jar.type to"),
                    e.getMessage());
        }
    }

    /**
     * A build that packages the executable of a former build ({@code quarkus.native.reuse-existing}), the optimized one
     * of a build with profile-guided optimization, checks the packages before the application image : the step that
     * checks them before native-image checks nothing then, and a missing tool fails the step that packages the
     * executable, before anything is written in the output directory.
     */
    @Test
    void theReusedExecutableIsCheckedBeforeItsImage(@TempDir Path project) throws IOException {
        Path target = ApplicationImageTest.nativeBuild(project);
        TargetOs os = TargetOs.host();
        DesktopPackagingConfig config = TestConfig.of(Map.of("formats", "msi,dmg,deb", "tools.wix", "missing/wix",
                "tools.hdiutil", "missing/hdiutil", "tools.dpkg-deb", "missing/dpkg-deb"));
        NativeConfig nativeConfig = new SmallRyeConfigBuilder().addDefaultInterceptors().withMapping(NativeConfig.class)
                .withSources(new PropertiesConfigSource(Map.of("quarkus.native.pgo.enabled", "true",
                        "quarkus.native.java-home", System.getProperty("java.home"),
                        "platform.quarkus.native.builder-image", "mandrel"), "test", 100))
                .build().getConfigMapping(NativeConfig.class);
        assertEquals(List.of(), DesktopPackagingProcessor.checkedFormats(config, os, os,
                nativeConfig.isCreateNativeBundle(), nativeConfig.pgo().enabled()));

        NativeImageBuildItem executable = new NativeImageBuildItem(
                target.resolve(os == TargetOs.WINDOWS ? "acme-runner.exe" : "acme-runner"),
                NativeImageBuildItem.GraalVMVersion.unknown(), true);
        ApplicationModel model = new ApplicationModelBuilder().setAppArtifact(ResolvedDependencyBuilder.newInstance()
                .setGroupId("org.acme").setArtifactId("acme").setVersion("1.2.3")).build();
        PackagingException e = assertThrows(PackagingException.class,
                () -> new DesktopPackagingProcessor().packageNativeExecutable(config, nativeConfig, executable,
                        new NativeImageRunnerBuildItem(new NativeImageBuildLocalRunner("native-image")), Optional.empty(),
                        List.of(), Optional.empty(),
                        new OutputTargetBuildItem(target, target, "acme", "acme", false, new Properties(),
                                Optional.empty()),
                        new ApplicationInfoBuildItem(Optional.of("Acme Editor"), Optional.of("1.2.3")),
                        new CurateOutcomeBuildItem(model), result -> {
                        }));
        String tool = switch (os) {
            case WINDOWS -> "wix";
            case MACOS -> "hdiutil";
            case LINUX -> "dpkg-deb";
        };
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.tools." + tool + " : "), e.getMessage());
        assertFalse(Files.exists(target.resolve("packaging")), "the application image was built");
    }

    /**
     * The Visual C++ runtime of a Windows native executable comes from the GraalVM of the native build first.
     */
    @Test
    void theJdksOfTheVisualCppRuntime() {
        Path graalvm = Path.of("graalvm");
        Path jdk = Path.of("jdk");
        Path build = Path.of("build");
        assertEquals(List.of(graalvm, jdk, build),
                DesktopPackagingProcessor.jdkHomes(Optional.of(" graalvm "), jdk.toFile(), build));
        assertEquals(List.of(jdk, build), DesktopPackagingProcessor.jdkHomes(Optional.of(" "), jdk.toFile(), build));
        // the Java home of the native build is the JDK of the build by default
        assertEquals(List.of(build), DesktopPackagingProcessor.jdkHomes(Optional.empty(), build.toFile(), build));
        assertEquals(List.of(build), DesktopPackagingProcessor.jdkHomes(Optional.empty(), null, build));
    }

    /**
     * The name of the configuration, which the extensions of the user interface toolkits give the application : not
     * when it is not set, nor when the extension is disabled.
     */
    @Test
    void theNameOfThePackagesForTheToolkits() {
        List<PackagedApplicationNameBuildItem> names = new ArrayList<>();
        DesktopPackagingProcessor processor = new DesktopPackagingProcessor();
        processor.packagedApplicationName(TestConfig.of(Map.of("name", " Acme Editor ")), names::add);
        assertEquals(List.of("Acme Editor"), names.stream().map(PackagedApplicationNameBuildItem::getName).toList());
        names.clear();
        processor.packagedApplicationName(TestConfig.defaults(), names::add);
        processor.packagedApplicationName(TestConfig.of(Map.of("name", "Acme Editor", "enabled", "false")), names::add);
        assertEquals(List.of(), names);
    }
}
