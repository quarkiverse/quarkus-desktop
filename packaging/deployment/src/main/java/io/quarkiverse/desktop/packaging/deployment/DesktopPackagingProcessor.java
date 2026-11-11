package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.packaging.deployment.windows.WindowsResources;
import io.quarkiverse.desktop.packaging.spi.NativeExecutableFileBuildItem;
import io.quarkiverse.desktop.packaging.spi.PackagedJvmOptionBuildItem;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarBuildItem;
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
// NativeOrNativeSourcesBuild and NativeBuild are deprecated, but Quarkus core has no replacement yet
@SuppressWarnings("deprecation")
class DesktopPackagingProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopPackagingProcessor.class);

    static final String FEATURE = "desktop-packaging";

    /**
     * The options of the Windows executable, read by native-image from the application jar.
     */
    static final String WINDOWS_NATIVE_IMAGE_PROPERTIES = "META-INF/native-image/io.quarkiverse.desktop/"
            + "quarkus-desktop-packaging-windows/native-image.properties";

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

    // ---------------------------------------------------------------------------------------------- Windows executable

    /**
     * The icon and the version information of the Windows native executable
     * ({@code quarkus.desktop.packaging.windows.executable-resources}) : a compiled resource file that the linker adds
     * to the executable, with a {@code -H:NativeLinkerOption} option in a {@code native-image.properties} file
     * generated in the application jar.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void windowsExecutableResources(DesktopPackagingConfig config, NativeImageRunnerBuildItem nativeImageRunner,
            OutputTargetBuildItem outputTarget, ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) throws IOException {
        if (!config.enabled() || !config.windows().executableResources()
                || targetOs(nativeImageRunner.isContainerBuild()) != TargetOs.WINDOWS) {
            return;
        }
        PackagingContext context = context(config, outputTarget, applicationInfo, curateOutcome, TargetOs.WINDOWS);
        byte[] resources = WindowsResources.res(context.icons(), context.app(), context.app().name() + ".exe");
        Optional<Path> file = writeResources(outputTarget.getOutputDirectory(), resources);
        if (file.isEmpty()) {
            return;
        }
        generatedResources.produce(new GeneratedResourceBuildItem(WINDOWS_NATIVE_IMAGE_PROPERTIES,
                nativeImageProperties(List.of("-H:NativeLinkerOption=" + file.get())).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Writes the compiled resource file in the build directory, or in the temporary directory when the path of the
     * build directory has white space (native-image splits the options of a {@code native-image.properties} file on
     * white space).
     *
     * @return the absolute path of the file, or empty when no directory without white space is available
     */
    static Optional<Path> writeResources(Path buildDirectory, byte[] resources) throws IOException {
        Path directory = buildDirectory.toAbsolutePath().resolve("quarkus-desktop-packaging");
        if (hasWhiteSpace(directory)) {
            directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                    .resolve("quarkus-desktop-packaging-" + Integer.toHexString(java.util.Arrays.hashCode(resources)));
            if (hasWhiteSpace(directory)) {
                LOGGER.warn("The native executable does not get the icon and the version of the application : the paths"
                        + " of the build directory and of the temporary directory contain white space. Build from a"
                        + " directory without white space, or set"
                        + " quarkus.desktop.packaging.windows.executable-resources=false");
                return Optional.empty();
            }
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
     * Packages the native executable. After the steps that change it or write files next to it : the UPX compression
     * of Quarkus, and the steps of the extensions that produce {@code NativeExecutableFileBuildItem}. The
     * {@code ArtifactResultBuildItem} is never produced (the packages are not the artifact of the build, which the
     * integration tests run) : it makes this step part of the build.
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void packageNativeExecutable(DesktopPackagingConfig config, NativeImageBuildItem nativeImage,
            NativeImageRunnerBuildItem nativeImageRunner, Optional<UpxCompressedBuildItem> upxCompressed,
            List<NativeExecutableFileBuildItem> files, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<ArtifactResultBuildItem> artifactResults) {
        TargetOs os = targetOs(nativeImageRunner.isContainerBuild());
        List<PackageFormat> formats = config.enabled() ? Packaging.formats(config, os) : List.of();
        if (formats.isEmpty()) {
            return;
        }
        try {
            PackagingContext context = context(config, outputTarget, applicationInfo, curateOutcome, os);
            ApplicationImage image = NativeApplicationImage.build(context, nativeImage.getPath(), files);
            Packaging.build(context, image, formats);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to package the native executable : " + e.getMessage(), e);
        }
    }

    /**
     * Packages the jar of a JVM build, with a Java runtime.
     */
    @BuildStep(onlyIf = JvmBuild.class)
    void packageJar(DesktopPackagingConfig config, JarBuildItem jar, List<PackagedJvmOptionBuildItem> jvmOptions,
            OutputTargetBuildItem outputTarget, ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<ArtifactResultBuildItem> artifactResults) {
        TargetOs os = TargetOs.host();
        List<PackageFormat> formats = config.enabled() ? Packaging.formats(config, os) : List.of();
        if (formats.isEmpty()) {
            return;
        }
        boolean uberJar = jar.isUberJar();
        if (!uberJar && jar.getType() != PackageConfig.JarConfig.JarType.FAST_JAR) {
            throw new PackagingException("The packages of a JVM application are built from a fast-jar or an uber-jar, not"
                    + " from a " + jar.getType() + " (quarkus.package.jar.type)");
        }
        List<String> options = new ArrayList<>();
        for (PackagedJvmOptionBuildItem option : jvmOptions) {
            if (option.getOs().isEmpty() || option.getOs().get() == os.os()) {
                options.add(option.getOption());
            }
        }
        try {
            PackagingContext context = context(config, outputTarget, applicationInfo, curateOutcome, os);
            ApplicationImage image = JvmApplicationImage.build(context, jar.getPath(), uberJar, options);
            Packaging.build(context, image, formats);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to package the application : " + e.getMessage(), e);
        }
    }

    /**
     * The operating system of the native executable : no cross compilation, and a container build produces a Linux
     * executable.
     */
    static TargetOs targetOs(boolean containerBuild) {
        return containerBuild ? TargetOs.LINUX : TargetOs.host();
    }

    private static PackagingContext context(DesktopPackagingConfig config, OutputTargetBuildItem outputTarget,
            ApplicationInfoBuildItem applicationInfo, CurateOutcomeBuildItem curateOutcome, TargetOs os) throws IOException {
        ApplicationModel model = curateOutcome.getApplicationModel();
        Path buildDirectory = outputTarget.getOutputDirectory().toAbsolutePath();
        // the directory of the project : the one of its build file, or the parent of the build directory
        WorkspaceModule module = model.getApplicationModule();
        Path projectDirectory = module != null && module.getModuleDir() != null ? module.getModuleDir().toPath()
                : buildDirectory.getParent();
        return Packaging.context(config, applicationInfo.getName(), applicationInfo.getVersion(),
                model.getAppArtifact().getGroupId(), projectDirectory, buildDirectory, os);
    }
}
