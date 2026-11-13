package io.quarkiverse.desktop.packaging.deployment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.windows.WindowsResources;
import io.quarkiverse.desktop.packaging.spi.ApplicationIdBuildItem;
import io.quarkiverse.desktop.packaging.spi.NativeExecutableFileBuildItem;
import io.quarkiverse.desktop.packaging.spi.OpenedTypesBuildItem;
import io.quarkiverse.desktop.packaging.spi.PackagedApplicationNameBuildItem;
import io.quarkiverse.desktop.packaging.spi.PackagedJvmOptionBuildItem;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.QuarkusBuildCloseablesBuildItem;
import io.quarkus.deployment.configuration.NativeConfigUtils;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarBuildItem;
import io.quarkus.deployment.pkg.builditem.JvmStartupOptimizerArchiveResultBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageRunnerBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.pkg.builditem.UpxCompressedBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;

/**
 * The packages of a desktop application : built at the end of the build, from its native executable or from its jar,
 * whatever its user interface toolkit.
 */
// NativeOrNativeSourcesBuild, NativeBuild and IsNormal are deprecated, but Quarkus core has no replacement yet :
// IsProduction holds for quarkus:run too, which builds no native executable and no package
@SuppressWarnings("deprecation")
class DesktopPackagingProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopPackagingProcessor.class);

    static final String FEATURE = "desktop-packaging";

    /**
     * The options of the Windows executable, read by native-image from the application jar.
     */
    static final String WINDOWS_NATIVE_IMAGE_PROPERTIES = "META-INF/native-image/io.quarkiverse.desktop/"
            + "quarkus-desktop-packaging-windows/native-image.properties";

    /**
     * The first bytes of a native executable, which have the magic number of UPX when it is compressed : UPX writes it
     * after the headers of its loader, within the first kilobyte.
     */
    private static final int UPX_HEADERS = 4096;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * A JVM build that writes a jar : the jar is packaged with a Java runtime.
     */
    static final class JvmBuild implements BooleanSupplier {

        private final NativeConfig nativeConfig;

        private final PackageConfig packageConfig;

        JvmBuild(NativeConfig nativeConfig, PackageConfig packageConfig) {
            this.nativeConfig = nativeConfig;
            this.packageConfig = packageConfig;
        }

        @Override
        public boolean getAsBoolean() {
            return !nativeConfig.enabled() && packageConfig.jar().enabled();
        }
    }

    // ------------------------------------------------------------------------------------- the name of the application

    /**
     * The name of the application in its packages, for the extensions of the user interface toolkits, which name the
     * application after it (the application menu of macOS) : when {@code quarkus.desktop.packaging.name} is set, with
     * or without formats. Otherwise they keep their default name, {@code quarkus.application.name}, which is the
     * default name of the packages too.
     */
    @BuildStep
    void packagedApplicationName(DesktopPackagingConfig config, BuildProducer<PackagedApplicationNameBuildItem> name) {
        if (config.enabled()) {
            AppInfo.configuredName(config).ifPresent(value -> name.produce(new PackagedApplicationNameBuildItem(value)));
        }
    }

    /**
     * The file types and URL schemes that the packages register the application for, and its identifier, for the
     * extensions of the user interface toolkits : they recognize the files and the URLs that the application is
     * started with. Produced when the extension is enabled, with or without packages, and not checked here : an invalid
     * configuration fails the packages, not dev mode.
     */
    @BuildStep
    void openedTypes(DesktopPackagingConfig config, ApplicationInfoBuildItem applicationInfo,
            CurateOutcomeBuildItem curateOutcome, BuildProducer<OpenedTypesBuildItem> openedTypes) {
        if (!config.enabled()) {
            return;
        }
        openedTypes.produce(openedTypes(config, applicationInfo.getName(),
                curateOutcome.getApplicationModel().getAppArtifact().getGroupId()));
    }

    /**
     * The file types and URL schemes of the configuration, in lower case and without their dot or their colon, and
     * the identifier of the application (the configured one, or the one derived from the group ID and the name).
     */
    static OpenedTypesBuildItem openedTypes(DesktopPackagingConfig config, String applicationName, String groupId) {
        Set<String> extensions = new LinkedHashSet<>();
        config.fileTypes().values().forEach(type -> type.extensions().stream().map(String::strip)
                .map(extension -> (extension.startsWith(".") ? extension.substring(1) : extension).toLowerCase(Locale.ROOT))
                .filter(extension -> !extension.isEmpty()).forEach(extensions::add));
        Set<String> schemes = new LinkedHashSet<>();
        config.urlSchemes().orElse(List.of()).stream().map(scheme -> scheme.strip().toLowerCase(Locale.ROOT))
                .map(scheme -> scheme.replaceFirst(":(//)?$", "")).filter(scheme -> !scheme.isEmpty())
                .forEach(schemes::add);
        String name = AppInfo.configuredName(config)
                .orElse(applicationName == null || applicationName.equals(AppInfo.UNSET) ? "application" : applicationName);
        String identifier = config.identifier().map(String::strip).filter(value -> !value.isEmpty())
                .orElseGet(() -> AppInfo.identifier(groupId == null ? "" : groupId, name));
        return new OpenedTypesBuildItem(extensions, schemes, identifier);
    }

    // ---------------------------------------------------------------------------------------------- Windows executable

    /**
     * The icon and the version information of the Windows native executable
     * ({@code quarkus.desktop.packaging.windows.executable-resources}) : a compiled resource file that the linker adds
     * to the executable, with a {@code -H:NativeLinkerOption} option in a {@code native-image.properties} file
     * generated in the application jar. Not for native sources ({@code quarkus.native.sources-only}) nor for a
     * native-image bundle ({@code quarkus.native.bundle.*}) : the option would name a file of this build, which the
     * machine that compiles the sources or applies the bundle may not have ({@link #executableResourcesUnavailable}).
     * Nor when the application links a resource file itself ({@link #linkedResourceFile}) : the icons and the version
     * information of two files have the same identifiers, and the linker fails on them. Only in a production build :
     * dev mode, the tests and {@code quarkus:run} ask for the generated resources too, and build no native executable.
     */
    @BuildStep(onlyIf = { NativeOrNativeSourcesBuild.class, IsNormal.class })
    void windowsExecutableResources(DesktopPackagingConfig config, NativeConfig nativeConfig,
            NativeImageRunnerBuildItem nativeImageRunner, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            QuarkusBuildCloseablesBuildItem closeables, BuildProducer<GeneratedResourceBuildItem> generatedResources)
            throws IOException {
        if (!config.enabled() || !config.windows().executableResources()
                || targetOs(nativeImageRunner.isContainerBuild()) != TargetOs.WINDOWS) {
            return;
        }
        Optional<String> unavailable = executableResourcesUnavailable(nativeConfig.sourcesOnly(),
                nativeConfig.isCreateNativeBundle());
        if (unavailable.isPresent()) {
            LOGGER.info(unavailable.get());
            return;
        }
        Optional<String> linked = linkedResourceFile(NativeConfigUtils.getNativeAdditionalBuildArgs(nativeConfig));
        if (linked.isPresent()) {
            LOGGER.infof("The native executable has the resources of %s, which the application links itself : the"
                    + " extension does not link the icon and the version of the application, which would conflict"
                    + " with them (quarkus.desktop.packaging.windows.executable-resources=false hides this message)",
                    linked.get());
            return;
        }
        PackagingContext context = context(config, outputTarget, applicationInfo, curateOutcome, TargetOs.WINDOWS,
                Arch::host, Optional.empty());
        byte[] resources = WindowsResources.res(context.icons(), context.app(), context.app().name() + ".exe");
        Path buildDirectory = outputTarget.getOutputDirectory().toAbsolutePath();
        Optional<Path> file = writeResources(buildDirectory, Path.of(System.getProperty("java.io.tmpdir")), resources);
        if (file.isEmpty()) {
            return;
        }
        if (deletesResources(file.get(), buildDirectory, nativeConfig.pgo().enabled())) {
            Path directory = file.get().getParent();
            closeables.add(() -> FileTrees.delete(directory));
        }
        generatedResources.produce(new GeneratedResourceBuildItem(WINDOWS_NATIVE_IMAGE_PROPERTIES,
                nativeImageProperties(List.of("-H:NativeLinkerOption=" + file.get())).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Why the native executable does not get the icon and the version of the application : the native build writes
     * native sources ({@code quarkus.native.sources-only}) or a native-image bundle ({@code quarkus.native.bundle.*}),
     * which are compiled or applied later, maybe on another machine, and the option that links them would name a file
     * of this build.
     *
     * @param sourcesOnly whether the native build writes native sources
     * @param bundle whether the native build writes a native-image bundle
     * @return the message, or empty when the native executable gets them
     */
    static Optional<String> executableResourcesUnavailable(boolean sourcesOnly, boolean bundle) {
        if (sourcesOnly) {
            return Optional.of("The native sources do not give the native executable the icon and the version of the"
                    + " application : they are linked from a file of this build, which the machine that compiles the"
                    + " sources may not have");
        }
        if (bundle) {
            return Optional.of("The native-image bundle does not give the native executable the icon and the version of"
                    + " the application : they are linked from a file of this build, which the machine that applies the"
                    + " bundle may not have");
        }
        return Optional.empty();
    }

    /**
     * Whether the directory of the compiled resource file is deleted at the end of the build, once the native
     * executable is linked : the directory of its own that {@link #writeResources} creates in the temporary
     * directory, never the build directory. Not with profile-guided optimization ({@code quarkus.native.pgo.enabled}) :
     * the optimized executable is linked after the integration tests ({@code quarkus:build-enhanced-artifact}), from
     * the same application jar, and so from the same file.
     *
     * @param file the compiled resource file
     * @param buildDirectory the absolute path of the build directory
     * @param pgo whether the native build is instrumented for profile-guided optimization
     */
    static boolean deletesResources(Path file, Path buildDirectory, boolean pgo) {
        return !pgo && !file.startsWith(buildDirectory);
    }

    /**
     * The compiled resource file that the application links itself, with the icon or the version information of its
     * executable : the value of the first {@code -H:NativeLinkerOption} option that is a {@code .res} file. One that
     * is linked in another way (the {@code native-image.properties} file of the application, a response file) is not
     * seen.
     *
     * @param args the additional options of the native build ({@code quarkus.native.additional-build-args})
     */
    static Optional<String> linkedResourceFile(List<String> args) {
        String option = "-H:NativeLinkerOption=";
        for (String arg : args) {
            String value = arg.trim();
            if (!value.startsWith(option)) {
                continue;
            }
            value = value.substring(option.length()).trim();
            if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.toLowerCase(Locale.ROOT).endsWith(".res")) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Writes the compiled resource file in the build directory, or in a new directory of the temporary directory when
     * the path of the build directory has white space (native-image splits the options of a
     * {@code native-image.properties} file on white space). The new directory has a random name, so that no other
     * account can create it first ; the build step deletes it at the end of the build, after the native executable is
     * linked, but not when the optimized executable of profile-guided optimization is linked from it later
     * ({@link #deletesResources}). There is no such file for native sources and native-image bundles, which are linked
     * later, maybe on another machine.
     *
     * @param buildDirectory the build directory
     * @param temporaryDirectory the temporary directory ({@code java.io.tmpdir})
     * @return the absolute path of the file, or empty when no directory without white space is available
     */
    static Optional<Path> writeResources(Path buildDirectory, Path temporaryDirectory, byte[] resources)
            throws IOException {
        Path directory = buildDirectory.toAbsolutePath().resolve("quarkus-desktop-packaging");
        if (hasWhiteSpace(directory)) {
            Path temporary = temporaryDirectory.toAbsolutePath();
            if (hasWhiteSpace(temporary)) {
                LOGGER.warn("The native executable does not get the icon and the version of the application : the paths"
                        + " of the build directory and of the temporary directory contain white space. Build from a"
                        + " directory without white space, or set"
                        + " quarkus.desktop.packaging.windows.executable-resources=false");
                return Optional.empty();
            }
            // a random name of digits : without white space either
            directory = Files.createTempDirectory(Files.createDirectories(temporary), "quarkus-desktop-packaging-");
        }
        return Optional.of(FileTrees.write(directory.resolve("application.res"), resources));
    }

    private static boolean hasWhiteSpace(Path path) {
        return path.toString().chars().anyMatch(Character::isWhitespace);
    }

    /**
     * The content of a {@code native-image.properties} file with the given native build options, in ASCII :
     * native-image reads these files as ISO-8859-1, so the characters above {@code ~} (in a path with accents) are
     * Unicode escapes.
     */
    static String nativeImageProperties(List<String> args) {
        // native-image splits Args on white space ; properties files use \ as escape character
        String value = String.join(" ", args).replace("\\", "\\\\");
        StringBuilder properties = new StringBuilder("Args = ");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > '~') {
                properties.append(String.format("\\u%04X", (int) c));
            } else {
                properties.append(c);
            }
        }
        return properties.append('\n').toString();
    }

    // -------------------------------------------------------------------------------------------------------- packages

    /**
     * Checks the packages of the native executable before it is built, which takes minutes : the name of the
     * application, the icon, the content directory, what signs the application image, and the tools of each format and
     * what it derives from the configuration (see {@link Packaging#check}). The architecture of the build stands for
     * the one of the executable, which is not built yet. The {@code GeneratedResourceBuildItem} is never produced : it
     * makes this step run before the application jar, which native-image builds the executable from. Only in a
     * production build : dev mode, the tests and {@code quarkus:run} ask for the generated resources too, and package
     * nothing. Each step that reads the configuration logs its warnings (a version without a leading number...) : this
     * one, the packaging of the executable, and its resources on Windows.
     */
    @BuildStep(onlyIf = { NativeBuild.class, IsNormal.class })
    void checkPackages(DesktopPackagingConfig config, NativeConfig nativeConfig,
            NativeImageRunnerBuildItem nativeImageRunner, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) throws IOException {
        TargetOs os = targetOs(nativeImageRunner.isContainerBuild());
        List<PackageFormat> formats = checkedFormats(config, os, TargetOs.host(), nativeConfig.isCreateNativeBundle(),
                nativeConfig.pgo().enabled());
        if (!formats.isEmpty()) {
            Packaging.check(context(config, outputTarget, applicationInfo, curateOutcome, os, Arch::host,
                    Optional.empty()), formats);
        }
    }

    /**
     * The formats that are checked before the native executable is built : the ones that package it. None when it is
     * built for another operating system than the one of the build, nor when a native-image bundle or an instrumented
     * executable is built instead of it : the packages of an executable of a former build, which such a build may
     * reuse, are checked when it is packaged.
     *
     * @param target the operating system of the native executable ({@link #targetOs})
     * @param host the operating system of the build
     * @param bundle whether the native build writes a native-image bundle
     * @param pgo whether the native build is instrumented for profile-guided optimization
     */
    static List<PackageFormat> checkedFormats(DesktopPackagingConfig config, TargetOs target, TargetOs host,
            boolean bundle, boolean pgo) {
        if (!config.enabled() || target != host || nativeExecutableUnavailable(bundle, pgo, false).isPresent()) {
            return List.of();
        }
        return Packaging.formats(config, target);
    }

    /**
     * Packages the native executable. After the steps that change it or write files next to it : the UPX compression
     * of Quarkus, and the steps of the extensions that produce {@code NativeExecutableFileBuildItem}. The
     * {@code ArtifactResultBuildItem} is never produced (the packages are not the artifact of the build, which the
     * integration tests run) : it makes this step part of the build.
     *
     * @param applicationId the name that the application gives itself at run time, for its shortcuts and desktop entry
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void packageNativeExecutable(DesktopPackagingConfig config, NativeConfig nativeConfig,
            NativeImageBuildItem nativeImage, NativeImageRunnerBuildItem nativeImageRunner,
            Optional<UpxCompressedBuildItem> upxCompressed, List<NativeExecutableFileBuildItem> files,
            Optional<ApplicationIdBuildItem> applicationId, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<ArtifactResultBuildItem> artifactResults) {
        TargetOs os = targetOs(nativeImageRunner.isContainerBuild());
        List<PackageFormat> formats = config.enabled() ? nativeFormats(config, os, TargetOs.host()) : List.of();
        if (formats.isEmpty()) {
            return;
        }
        Optional<String> unavailable = nativeExecutableUnavailable(nativeConfig.isCreateNativeBundle(),
                nativeConfig.pgo().enabled(), nativeImage.isReused());
        if (unavailable.isPresent()) {
            LOGGER.warn(unavailable.get());
            return;
        }
        compressionWarning(config.linux(), formats,
                isUpxCompressed(upxCompressed.isPresent(), nativeImage.isReused(), nativeImage.getPath()))
                .ifPresent(LOGGER::warn);
        try {
            // the packages are built for the architecture of the executable, which the JVM of the build may not have
            PackagingContext context = context(config, outputTarget, applicationInfo, curateOutcome, os,
                    () -> Arch.executable(nativeImage.getPath()), applicationId)
                    .withOptionalLibraries(NativeApplicationImage.optionalLibraries(os, files));
            Packaging.check(context, formats);
            ApplicationImage image = NativeApplicationImage.build(context, nativeImage.getPath(), files,
                    jdkHomes(nativeConfig.graalvmHome(), nativeConfig.javaHome(), Path.of(System.getProperty("java.home"))));
            Packaging.build(context, image, formats);
        } catch (IOException e) {
            // the exception and its message : the message of some of them is a path alone
            throw new UncheckedIOException("Unable to package the native executable : " + e, e);
        }
    }

    /**
     * The formats that package the native executable : none when it is built for another operating system than the
     * one of the build (in a container, for Linux, on Windows or macOS), as a package is built on its operating system.
     * The formats that are skipped then are logged as a warning.
     *
     * @param target the operating system of the native executable ({@link #targetOs})
     * @param host the operating system of the build
     */
    static List<PackageFormat> nativeFormats(DesktopPackagingConfig config, TargetOs target, TargetOs host) {
        if (target != host) {
            Packaging.containerBuildWarning(config, target, host).ifPresent(LOGGER::warn);
            return List.of();
        }
        return Packaging.formats(config, target);
    }

    /**
     * Why the native executable of the build is not packaged : a native-image bundle ({@code quarkus.native.bundle.*})
     * is built instead of it, or it is the instrumented executable of a build with profile-guided optimization
     * ({@code quarkus.native.pgo.enabled}), whose optimized executable is built after the integration tests. An
     * executable of a former build ({@code quarkus.native.reuse-existing}) is packaged : the optimized one, once it is
     * built.
     *
     * @param bundle whether the native build writes a native-image bundle
     * @param pgo whether the native build is instrumented for profile-guided optimization
     * @param reused whether the native executable is the one of a former build, which the native build did not change
     * @return the warning, or empty when the native executable is packaged
     */
    static Optional<String> nativeExecutableUnavailable(boolean bundle, boolean pgo, boolean reused) {
        if (reused) {
            return Optional.empty();
        }
        if (bundle) {
            return Optional.of("No desktop package is built : the native build writes a native-image bundle"
                    + " (quarkus.native.bundle.enabled, name or dry-run), not a native executable");
        }
        if (pgo) {
            return Optional.of("No desktop package is built : with quarkus.native.pgo.enabled, the native executable of"
                    + " this build is instrumented, and the optimized one is built after the integration tests (the"
                    + " quarkus:build-enhanced-artifact goal). Package the optimized executable with a second build, with"
                    + " -Dquarkus.native.reuse-existing=true");
        }
        return Optional.empty();
    }

    /**
     * The JDKs that the Visual C++ runtime of a Windows native executable is copied from, in this order : the GraalVM
     * home of the native build ({@code quarkus.native.graalvm-home}), whose libraries the executable loads, the Java
     * home of the native build ({@code quarkus.native.java-home}), then the JDK of the build. Every JDK for Windows has
     * the runtime.
     *
     * @param buildJdk the home of the JDK of the build ({@code java.home})
     */
    static List<Path> jdkHomes(Optional<String> graalvmHome, File javaHome, Path buildJdk) {
        Set<Path> homes = new LinkedHashSet<>();
        graalvmHome.map(String::trim).filter(home -> !home.isEmpty()).map(Path::of).ifPresent(homes::add);
        if (javaHome != null) {
            homes.add(javaHome.toPath());
        }
        homes.add(buildJdk);
        return List.copyOf(homes);
    }

    /**
     * The warning of a native executable compressed with UPX, for the automatic dependencies of the Debian and RPM
     * packages : a compressed executable has no dynamic section, and {@code dpkg-shlibdeps} and {@code rpmbuild} do not
     * see the shared libraries that it is linked with.
     *
     * @param compressed whether the native executable is compressed ({@code quarkus.native.compression.level}) : by this
     *        build, or by the former build of a reused executable ({@link #isUpxCompressed})
     * @return the warning, or empty when the formats have no automatic dependencies to lose
     */
    static Optional<String> compressionWarning(DesktopPackagingConfig.Linux config, List<PackageFormat> formats,
            boolean compressed) {
        List<String> properties = new ArrayList<>();
        if (formats.contains(PackageFormat.DEB) && config.deb().autoDepends()) {
            properties.add("quarkus.desktop.packaging.linux.deb.depends");
        }
        if (formats.contains(PackageFormat.RPM) && config.rpm().autoRequires()) {
            properties.add("quarkus.desktop.packaging.linux.rpm.requires");
        }
        if (!compressed || properties.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("The native executable is compressed with UPX (quarkus.native.compression.level) : the Linux"
                + " packages do not depend on the packages of the shared libraries that it is linked with, which the"
                + " compression hides. List them in " + String.join(" and ", properties) + ", or do not compress the"
                + " executable");
    }

    /**
     * Whether the native executable is compressed with UPX : by this build, or, for the executable of a former build
     * ({@code quarkus.native.reuse-existing}), which Quarkus does not compress again, as its header shows. Not from the
     * configuration : the optimized executable of profile-guided optimization, which a build reuses, is not
     * compressed, and the compression of the former build may have another configuration.
     *
     * @param compressedByThisBuild whether the UPX compression of Quarkus ran in this build
     *        ({@code UpxCompressedBuildItem})
     * @param reused whether the native executable is the one of a former build
     * @param executable the native executable
     */
    static boolean isUpxCompressed(boolean compressedByThisBuild, boolean reused, Path executable) {
        if (compressedByThisBuild) {
            return true;
        }
        if (!reused) {
            return false;
        }
        try (InputStream in = Files.newInputStream(executable)) {
            return hasUpxMagic(in.readNBytes(UPX_HEADERS));
        } catch (IOException e) {
            LOGGER.debugf(e, "Unable to read %s : it is taken for an executable that is not compressed", executable);
            return false;
        }
    }

    /**
     * Whether the first bytes of an executable have the {@code UPX!} magic number, which UPX writes after the headers
     * of the loader of a compressed executable.
     */
    static boolean hasUpxMagic(byte[] header) {
        return new String(header, StandardCharsets.ISO_8859_1).contains("UPX!");
    }

    /**
     * Packages the jar of a JVM build, with a Java runtime. After the step of Quarkus that writes the AppCDS or AOT
     * archive in the directory of a fast-jar or an aot-jar ({@code quarkus.package.jar.aot.enabled}, which makes the
     * aot-jar the default type) : the archive is left out of the image (it was built for the JDK of the build and the
     * path of the jar, not for the runtime of the image).
     *
     * @param applicationId the name that the application gives itself at run time, for its shortcuts and desktop entry
     */
    @BuildStep(onlyIf = JvmBuild.class)
    void packageJar(DesktopPackagingConfig config, JarBuildItem jar,
            Optional<JvmStartupOptimizerArchiveResultBuildItem> startupArchive, List<PackagedJvmOptionBuildItem> jvmOptions,
            Optional<ApplicationIdBuildItem> applicationId, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<ArtifactResultBuildItem> artifactResults) {
        TargetOs os = TargetOs.host();
        List<PackageFormat> formats = config.enabled() ? Packaging.formats(config, os) : List.of();
        if (formats.isEmpty()) {
            return;
        }
        boolean uberJar = jar.isUberJar();
        checkJarType(jar.getType(), uberJar);
        JvmApplicationImage.severalOptionsWarning(config.jvm().options()).ifPresent(LOGGER::warn);
        try {
            PackagingContext context = context(config, outputTarget, applicationInfo, curateOutcome, os, Arch::host,
                    applicationId);
            Packaging.check(context, formats);
            ApplicationImage image = JvmApplicationImage.build(context, jar.getPath(), uberJar,
                    startupArchive.map(JvmStartupOptimizerArchiveResultBuildItem::getArchive), jvmOptions);
            Packaging.build(context, image, formats);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to package the application : " + e, e);
        }
    }

    /**
     * Checks the type of the jar of a JVM build : the packages are built from a fast-jar, from an aot-jar, which has
     * the layout of a fast-jar, or from an uber-jar.
     *
     * @param uberJar whether the jar has the whole application
     * @throws PackagingException for a jar of another type
     */
    static void checkJarType(PackageConfig.JarConfig.JarType type, boolean uberJar) {
        if (!uberJar && type != PackageConfig.JarConfig.JarType.FAST_JAR
                && type != PackageConfig.JarConfig.JarType.AOT_JAR) {
            throw new PackagingException("The packages of a JVM application are built from a fast-jar, an aot-jar or an"
                    + " uber-jar, not from a " + type + " : set quarkus.package.jar.type to fast-jar, aot-jar or uber-jar");
        }
    }

    /**
     * The operating system of the native executable : no cross compilation, and a container build produces a Linux
     * executable.
     */
    static TargetOs targetOs(boolean containerBuild) {
        return containerBuild ? TargetOs.LINUX : TargetOs.host();
    }

    /**
     * What the packages of the build share, from the build items of Quarkus : the directory of the project is the one of
     * its build file, or the parent of the build directory.
     *
     * @param arch the architecture of the packages, asked by the formats that need it
     * @param applicationId the name that the application gives itself at run time
     */
    private static PackagingContext context(DesktopPackagingConfig config, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome, TargetOs os,
            Supplier<Arch> arch, Optional<ApplicationIdBuildItem> applicationId) throws IOException {
        ApplicationModel model = curateOutcome.getApplicationModel();
        Path buildDirectory = outputTarget.getOutputDirectory().toAbsolutePath();
        // the directory of the project : the one of its build file, or the parent of the build directory
        WorkspaceModule module = model.getApplicationModule();
        Path projectDirectory = module != null && module.getModuleDir() != null ? module.getModuleDir().toPath()
                : buildDirectory.getParent();
        return Packaging.context(config, applicationInfo.getName(), applicationInfo.getVersion(),
                model.getAppArtifact().getGroupId(), projectDirectory, buildDirectory, os, arch)
                .withApplicationId(applicationId.map(ApplicationIdBuildItem::getId));
    }
}
