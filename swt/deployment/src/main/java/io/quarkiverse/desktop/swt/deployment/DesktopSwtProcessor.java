package io.quarkiverse.desktop.swt.deployment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.deployment.SwtPlatformBuildItem.WindowingSystem;
import io.quarkiverse.desktop.swt.runtime.DesktopSwtConfig;
import io.quarkiverse.desktop.swt.runtime.DesktopSwtConfig.NativeLibraries;
import io.quarkiverse.desktop.swt.runtime.DesktopSwtRecorder;
import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.ModuleEnableNativeAccessBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageRunnerBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathTree;
import io.smallrye.common.os.OS;

/**
 * SWT in JVM mode and in native executables : the SWT jar of the platform, and what the native executables need.
 */
// NativeOrNativeSourcesBuild and NativeBuild are deprecated, but Quarkus core has no replacement yet
@SuppressWarnings("deprecation")
class DesktopSwtProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopSwtProcessor.class);

    static final String FEATURE = "desktop-swt";

    private static final String REASON = "Quarkus Desktop SWT";

    /**
     * Every SWT jar has this class : the SWT jar of a platform.
     */
    static final String LIBRARY_CLASS = "org/eclipse/swt/internal/Library.class";

    static final String SWT_GROUP_ID = "org.eclipse.platform";

    static final String SWT_HOST_ARTIFACT_ID = "org.eclipse.swt";

    /**
     * The options of the Windows executable, read by native-image from the application jar.
     */
    static final String WINDOWS_NATIVE_IMAGE_PROPERTIES = "META-INF/native-image/io.quarkiverse.desktop/"
            + "quarkus-desktop-swt-windows/native-image.properties";

    /**
     * The options of the macOS executable, read by native-image from the application jar.
     */
    static final String MAC_NATIVE_IMAGE_PROPERTIES = "META-INF/native-image/io.quarkiverse.desktop/"
            + "quarkus-desktop-swt-macos/native-image.properties";

    /**
     * The capability of the Desktop AWT extension ({@code DesktopCapabilities.AWT}, declared in the {@code pom.xml} of
     * its runtime module), which this extension does not depend on.
     */
    static final String AWT_CAPABILITY = "io.quarkiverse.desktop.awt";

    /**
     * The lookups expected to fail, for native executables built with exact reachability metadata.
     */
    static final String REACHABILITY_METADATA = "META-INF/native-image/io.quarkiverse.desktop/quarkus-desktop-swt/"
            + "reachability-metadata.json";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    // ------------------------------------------------------------------------------------------------------- SWT jar

    /**
     * The SWT jar of the application : the one with the native libraries of a platform (the {@code org.eclipse.swt}
     * host jar is empty).
     */
    @BuildStep
    void swtPlatform(CurateOutcomeBuildItem curateOutcome, NativeConfig nativeConfig,
            BuildProducer<SwtPlatformBuildItem> platform) {
        List<SwtPlatformBuildItem> jars = new ArrayList<>();
        for (ResolvedDependency dependency : curateOutcome.getApplicationModel().getRuntimeDependencies()) {
            PathTree tree = dependency.getContentTree();
            if (tree.contains(LIBRARY_CLASS)) {
                jars.add(platform(dependency, manifest(tree)));
            }
        }
        if (jars.isEmpty()) {
            String message = "The application has no SWT jar for " + OS.current() + " " + System.getProperty("os.arch")
                    + " (org.eclipse.platform:org.eclipse.swt.<ws>.<os>.<arch>) : Maven adds it to"
                    + " org.eclipse.platform:org.eclipse.swt, Gradle does not. Add the dependency on the SWT jar of the"
                    + " platform, e.g. org.eclipse.platform:org.eclipse.swt." + hostFragment() + ".";
            if (nativeConfig.enabled()) {
                // the native build needs its native libraries and its classes
                throw new IllegalStateException(message);
            }
            LOGGER.warn(message);
            return;
        }
        if (jars.size() > 1) {
            // a container build without the exclusion of the SWT jar of the build host, typically
            String message = "The application has several SWT jars : " + jars + ". Their classes conflict, keep the SWT"
                    + " jar of the target platform only (exclude the fragments that org.eclipse.swt adds for the build"
                    + " host, see the container build in the documentation of the Desktop SWT extension)";
            if (nativeConfig.enabled()) {
                throw new IllegalStateException(message);
            }
            LOGGER.warnf("%s : %s is used.", message, jars.get(0));
        }
        LOGGER.debugf("SWT jar : %s", jars.get(0));
        platform.produce(jars.get(0));
    }

    /**
     * SWT loads its native libraries ({@code System.load}) : enables the native access of SWT, so that the JDK does not
     * warn about it (JDK 24 and later). Quarkus enables it for the unnamed module, where SWT runs : the
     * {@code Enable-Native-Access} attribute of the manifest of the runner jar (and an option of the JVM of JBang).
     * <p>
     * Not the JVM of dev mode, which starts before any build step runs, with the JVM options of the descriptors of the
     * extensions : the one of this extension gives it {@code --enable-native-access=ALL-UNNAMED}
     * ({@code dev-mode.jvm-option.std.enable-native-access}, from the {@code devMode} configuration of the
     * {@code quarkus-extension-maven-plugin} in the {@code pom.xml} of the runtime module).
     */
    @BuildStep
    void nativeAccess(Optional<SwtPlatformBuildItem> platform,
            BuildProducer<ModuleEnableNativeAccessBuildItem> nativeAccess) {
        String module = platform.map(swt -> manifest(swt.getJar().getContentTree()).getMainAttributes()
                .getValue("Automatic-Module-Name")).orElse(null);
        nativeAccess.produce(new ModuleEnableNativeAccessBuildItem(module != null ? module : SWT_HOST_ARTIFACT_ID));
    }

    private static Manifest manifest(PathTree tree) {
        Manifest manifest = tree.apply("META-INF/MANIFEST.MF", visit -> {
            if (visit == null) {
                return null;
            }
            try (InputStream in = Files.newInputStream(visit.getPath())) {
                return new Manifest(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return manifest != null ? manifest : new Manifest();
    }

    /**
     * The platform of an SWT jar : the {@code SWT-WS}, {@code SWT-OS} and {@code SWT-Arch} attributes of its manifest
     * (from the artifact id when they are missing).
     */
    static SwtPlatformBuildItem platform(ResolvedDependency jar, Manifest manifest) {
        Attributes attributes = manifest.getMainAttributes();
        String[] parts = jar.getArtifactId().split("\\.");
        String ws = Optional.ofNullable(attributes.getValue("SWT-WS"))
                .orElse(parts.length == 6 ? parts[3] : "");
        String os = Optional.ofNullable(attributes.getValue("SWT-OS"))
                .orElse(parts.length == 6 ? parts[4] : "");
        String arch = Optional.ofNullable(attributes.getValue("SWT-Arch"))
                .orElse(parts.length == 6 ? parts[5] : "");
        WindowingSystem windowingSystem = switch (ws.toLowerCase(Locale.ROOT)) {
            case "win32" -> WindowingSystem.WIN32;
            case "cocoa" -> WindowingSystem.COCOA;
            case "gtk" -> WindowingSystem.GTK;
            default -> switch (OS.current()) {
                // no attribute : a jar of the host, repackaged
                case WINDOWS -> WindowingSystem.WIN32;
                case MAC -> WindowingSystem.COCOA;
                default -> WindowingSystem.GTK;
            };
        };
        return new SwtPlatformBuildItem(jar, windowingSystem, os, arch);
    }

    /**
     * The artifact id suffix of the SWT jar of the build host ({@code win32.win32.x86_64}...).
     */
    static String hostFragment() {
        String arch = hostArch();
        return switch (OS.current()) {
            case WINDOWS -> "win32.win32." + arch;
            case MAC -> "cocoa.macosx." + arch;
            default -> "gtk.linux." + arch;
        };
    }

    /**
     * The architecture of the build host, as SWT names it.
     */
    static String hostArch() {
        String arch = System.getProperty("os.arch", "");
        return switch (arch) {
            case "amd64" -> "x86_64";
            case "arm64" -> "aarch64";
            default -> arch;
        };
    }

    /**
     * Fails a native build without the SWT jar of its target : a container build (Linux) from Windows or macOS, where
     * Maven adds the SWT jar of the build host.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    @Produce(ArtifactResultBuildItem.class)
    void checkTargetPlatform(Optional<SwtPlatformBuildItem> platform, NativeImageRunnerBuildItem nativeImageRunner) {
        WindowingSystem target = targetWindowingSystem(OS.current(), nativeImageRunner.isContainerBuild());
        String targetName = target == WindowingSystem.GTK ? "linux" : OS.current().name().toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) {
            throw new IllegalStateException("The native executable for " + targetName + " needs the SWT jar of the"
                    + " platform : add the dependency org.eclipse.platform:org.eclipse.swt." + fragment(target)
                    + " (with the version of org.eclipse.swt)");
        }
        if (platform.get().getWindowingSystem() != target) {
            throw new IllegalStateException("The native executable is built for " + targetName
                    + (nativeImageRunner.isContainerBuild() ? " (a container build)" : "") + ", but the SWT jar of the"
                    + " application is " + platform.get() + ". Use the dependency org.eclipse.platform:org.eclipse.swt."
                    + fragment(target) + " instead (in a Maven profile of the container build), or build on "
                    + targetName + ".");
        }
        if (!platform.get().getArch().isEmpty() && !platform.get().getArch().equals(hostArch())) {
            LOGGER.warnf("The SWT jar of the application is %s, the native image builder runs on %s : the native libraries"
                    + " of SWT may not load", platform.get(), hostArch());
        }
    }

    /**
     * The quarkus-awt rule : no cross compilation, and a container build produces a Linux executable.
     */
    static WindowingSystem targetWindowingSystem(OS host, boolean containerBuild) {
        if (containerBuild) {
            return WindowingSystem.GTK;
        }
        return switch (host) {
            case WINDOWS -> WindowingSystem.WIN32;
            case MAC -> WindowingSystem.COCOA;
            default -> WindowingSystem.GTK;
        };
    }

    private static String fragment(WindowingSystem windowingSystem) {
        String arch = hostArch();
        return switch (windowingSystem) {
            case WIN32 -> "win32.win32." + arch;
            case COCOA -> "cocoa.macosx." + arch;
            case GTK -> "gtk.linux." + arch;
        };
    }

    static boolean isSwt(ResolvedDependency dependency) {
        return dependency.getGroupId().equals(SWT_GROUP_ID) && (dependency.getArtifactId().equals(SWT_HOST_ARTIFACT_ID)
                || dependency.getArtifactId().startsWith(SWT_HOST_ARTIFACT_ID + "."));
    }

    // ------------------------------------------------------------------------------------------ run time initialization

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runtimeInitialization(SwtPlatformBuildItem platform, BuildProducer<RuntimeInitializedPackageBuildItem> packages) {
        for (String packageName : platform.withPlatform(SwtClassesAndResources.RUNTIME_INITIALIZED_PACKAGES,
                SwtClassesAndResources.WINDOWS_RUNTIME_INITIALIZED_PACKAGES,
                SwtClassesAndResources.LINUX_RUNTIME_INITIALIZED_PACKAGES,
                SwtClassesAndResources.MAC_RUNTIME_INITIALIZED_PACKAGES)) {
            packages.produce(new RuntimeInitializedPackageBuildItem(packageName));
        }
    }

    /**
     * The classes of the application and of its libraries (indexed or not) whose static initializer uses SWT :
     * initialized at run time, as in JVM mode (see {@link SwtStaticInitializerScanner}).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runtimeInitializedSwtUsers(CombinedIndexBuildItem combinedIndex, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<RuntimeInitializedClassBuildItem> classes) {
        long start = System.nanoTime();
        Set<String> users = SwtStaticInitializerScanner.scan(SwtStaticInitializerScanner.classFiles(
                scannedTrees(curateOutcome.getApplicationModel()), combinedIndex.getIndex().getKnownClasses(),
                Thread.currentThread().getContextClassLoader()));
        LOGGER.debugf("Classes using SWT in their static initializer, initialized at run time (%d ms) : %s",
                (System.nanoTime() - start) / 1_000_000, users);
        for (String className : users) {
            classes.produce(new RuntimeInitializedClassBuildItem(className));
        }
    }

    /**
     * The application and the libraries whose static initializers are scanned : not Quarkus itself, nor the runtime
     * artifacts of the extensions, which initialize their classes as they need, nor SWT (initialized at run time).
     */
    static List<PathTree> scannedTrees(ApplicationModel model) {
        List<PathTree> trees = new ArrayList<>();
        trees.add(model.getAppArtifact().getContentTree());
        for (ResolvedDependency dependency : model.getRuntimeDependencies()) {
            if (!dependency.isRuntimeExtensionArtifact() && !isQuarkus(dependency.getGroupId()) && !isSwt(dependency)) {
                trees.add(dependency.getContentTree());
            }
        }
        return trees;
    }

    private static boolean isQuarkus(String groupId) {
        return groupId.equals("io.quarkus") || groupId.startsWith("io.quarkus.");
    }

    // ------------------------------------------------------------------------------------------------------ reflection

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reflection(SwtPlatformBuildItem platform, BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(platform.withPlatform(
                SwtClassesAndResources.REFLECTIVE_CONSTRUCTORS, SwtClassesAndResources.WINDOWS_REFLECTIVE_CONSTRUCTORS,
                SwtClassesAndResources.LINUX_REFLECTIVE_CONSTRUCTORS, SwtClassesAndResources.MAC_REFLECTIVE_CONSTRUCTORS))
                .reason(REASON).build());
    }

    /**
     * The providers of the services that SWT loads from the class path (optional SWT fragments).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void classPathServices(SwtPlatformBuildItem platform, BuildProducer<ServiceProviderBuildItem> serviceProviders) {
        for (String service : platform.withPlatform(SwtClassesAndResources.CLASS_PATH_SERVICES,
                SwtClassesAndResources.WINDOWS_CLASS_PATH_SERVICES, SwtClassesAndResources.LINUX_CLASS_PATH_SERVICES,
                SwtClassesAndResources.MAC_CLASS_PATH_SERVICES)) {
            ServiceProviderBuildItem providers = ServiceProviderBuildItem.allProvidersFromClassPath(service);
            if (!providers.providers().isEmpty()) {
                serviceProviders.produce(providers);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------- JNI

    /**
     * The members of SWT that its native code looks up : the struct classes and the callbacks computed from the SWT jar
     * ({@link SwtNativeAccess}), and the fixed ones of {@link SwtClassesAndResources}.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void jni(SwtPlatformBuildItem platform, BuildProducer<JniRuntimeAccessBuildItem> jniClasses,
            BuildProducer<JniRuntimeAccessMethodBuildItem> jniMethods) {
        long start = System.nanoTime();
        SwtNativeAccess access = SwtNativeAccess.scan(consumer -> platform.getJar().getContentTree().walk(visit -> {
            String path = visit.getRelativePath("/");
            if (path.endsWith(".class") && !path.startsWith("META-INF/")) {
                try {
                    consumer.accept(path.substring(0, path.length() - ".class".length()),
                            Files.readAllBytes(visit.getPath()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }));
        LOGGER.debugf("SWT JNI metadata of %s (%d ms) : %d struct classes, %d callbacks", platform.getJar().toCompactCoords(),
                (System.nanoTime() - start) / 1_000_000, access.structClasses().size(), access.callbacks().size());
        if (access.structClasses().isEmpty() || access.callbacks().isEmpty()) {
            LOGGER.warnf("No %s found in %s : the native executable is likely to fail with NoSuchFieldError or"
                    + " \"No more callbacks\". Please report it to the Quarkus Desktop project.",
                    access.structClasses().isEmpty() ? "struct class" : "callback", platform.getJar().toCompactCoords());
        }
        jniClasses.produce(new JniRuntimeAccessBuildItem(false, false, true,
                access.structClasses().toArray(String[]::new)));
        for (SwtNativeAccess.Method method : access.callbacks()) {
            jniMethods.produce(new JniRuntimeAccessMethodBuildItem(method.className(), method.name(),
                    method.parameterTypes().toArray(String[]::new)));
        }
        String[] classes = platform.withPlatform(SwtClassesAndResources.JNI_RUNTIME_ACCESS_CLASSES,
                SwtClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_CLASSES,
                SwtClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_CLASSES,
                SwtClassesAndResources.MAC_JNI_RUNTIME_ACCESS_CLASSES);
        if (classes.length > 0) {
            jniClasses.produce(new JniRuntimeAccessBuildItem(true, true, true, classes));
        }
        for (String method : platform.withPlatform(SwtClassesAndResources.JNI_RUNTIME_ACCESS_METHODS,
                SwtClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_METHODS,
                SwtClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_METHODS,
                SwtClassesAndResources.MAC_JNI_RUNTIME_ACCESS_METHODS)) {
            MemberEntry entry = MemberEntry.method(method);
            jniMethods.produce(new JniRuntimeAccessMethodBuildItem(entry.className(), entry.name(), entry.parameterTypes()));
        }
    }

    // ------------------------------------------------------------------------------------------------------- resources

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void resources(SwtPlatformBuildItem platform, DesktopSwtConfig config,
            BuildProducer<NativeImageResourceBundleBuildItem> bundles,
            BuildProducer<NativeImageResourcePatternsBuildItem> resourcePatterns,
            BuildProducer<NativeImageResourceBuildItem> resources) {
        for (String bundle : platform.withPlatform(SwtClassesAndResources.RESOURCE_BUNDLES,
                SwtClassesAndResources.WINDOWS_RESOURCE_BUNDLES, SwtClassesAndResources.LINUX_RESOURCE_BUNDLES,
                SwtClassesAndResources.MAC_RESOURCE_BUNDLES)) {
            bundles.produce(new NativeImageResourceBundleBuildItem(bundle));
        }
        resourcePatterns.produce(NativeImageResourcePatternsBuildItem.builder()
                .includeGlobs(platform.withPlatform(SwtClassesAndResources.RESOURCE_GLOBS,
                        SwtClassesAndResources.WINDOWS_RESOURCE_GLOBS, SwtClassesAndResources.LINUX_RESOURCE_GLOBS,
                        SwtClassesAndResources.MAC_RESOURCE_GLOBS))
                .build());
        if (config.nativeLibraries() == NativeLibraries.EMBEDDED) {
            // extracted by SWT (org.eclipse.swt.internal.Library) from the resources of the executable
            List<String> libraries = nativeLibraries(platform.getJar().getContentTree());
            LOGGER.debugf("Native libraries of SWT embedded in the native executable : %s", libraries);
            resources.produce(new NativeImageResourceBuildItem(libraries));
        }
    }

    /**
     * The native libraries of an SWT jar, at its root : {@code swt-*.dll}, {@code WebView2Loader.dll},
     * {@code libswt-*.so}, {@code libswt-*.jnilib}. Not the library of the SWT_AWT bridge ({@code swt-awt}), which needs
     * the AWT library of a JDK.
     */
    static List<String> nativeLibraries(PathTree tree) {
        List<String> libraries = new ArrayList<>();
        tree.walk(visit -> {
            String name = visit.getRelativePath("/");
            if (isNativeLibrary(name)) {
                libraries.add(name);
            }
        });
        libraries.sort(null);
        return libraries;
    }

    static boolean isNativeLibrary(String name) {
        if (name.contains("/")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean library = lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".jnilib")
                || lower.endsWith(".dylib");
        return library && !lower.startsWith("swt-awt-") && !lower.startsWith("libswt-awt-");
    }

    /**
     * The main native library of SWT ({@code swt-win32-4974r10.dll}, {@code libswt-gtk-4974r10.so},
     * {@code libswt-cocoa-4974r10.jnilib}), loaded first.
     */
    static Optional<String> mainNativeLibrary(List<String> libraries, WindowingSystem windowingSystem) {
        String prefix = switch (windowingSystem) {
            case WIN32 -> "swt-win32-";
            case GTK -> "libswt-gtk-";
            case COCOA -> "libswt-cocoa-";
        };
        return libraries.stream().filter(library -> library.startsWith(prefix)).findFirst();
    }

    // ------------------------------------------------------------------------------------------------ native libraries

    /**
     * {@code quarkus.desktop.swt.native-libraries=next-to-executable} : the executable loads the native libraries of SWT
     * from its directory. Before the {@code StartupEvent} observers (a {@code ServiceStartBuildItem}), which may load
     * them.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem useLibrariesNextToExecutable(SwtPlatformBuildItem platform, DesktopSwtConfig config,
            DesktopSwtRecorder recorder) {
        if (config.nativeLibraries() != NativeLibraries.NEXT_TO_EXECUTABLE) {
            return null;
        }
        mainNativeLibrary(nativeLibraries(platform.getJar().getContentTree()), platform.getWindowingSystem())
                .ifPresent(recorder::useLibrariesNextToExecutable);
        return new ServiceStartBuildItem(FEATURE + "-native-libraries");
    }

    /**
     * Copies the native libraries of SWT next to the native executable ({@code next-to-executable}). The
     * {@code ArtifactResultBuildItem} is never produced : it makes this step run after the native build.
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void copyNativeLibraries(SwtPlatformBuildItem platform, DesktopSwtConfig config, NativeImageBuildItem nativeImage,
            BuildProducer<ArtifactResultBuildItem> artifactResults) {
        if (config.nativeLibraries() != NativeLibraries.NEXT_TO_EXECUTABLE) {
            return;
        }
        Path directory = nativeImage.getPath().toAbsolutePath().getParent();
        PathTree tree = platform.getJar().getContentTree();
        for (String library : nativeLibraries(tree)) {
            tree.accept(library, visit -> {
                try {
                    Files.copy(visit.getPath(), directory.resolve(library), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to copy the SWT library " + library + " to " + directory, e);
                }
            });
        }
        LOGGER.debugf("Native libraries of SWT copied to %s", directory);
    }

    // ---------------------------------------------------------------------------------------------- Windows executable

    /**
     * The subsystem of the Windows executable ({@code quarkus.desktop.swt.windows.subsystem}).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void windowsExecutable(SwtPlatformBuildItem platform, DesktopSwtConfig config,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        if (!platform.isWindows() || config.windows().subsystem() != DesktopSwtConfig.Subsystem.WINDOWS) {
            return;
        }
        // The entry point of a console application : the Windows subsystem expects WinMain otherwise
        generatedResources.produce(new GeneratedResourceBuildItem(WINDOWS_NATIVE_IMAGE_PROPERTIES,
                nativeImageProperties(List.of("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS",
                        "-H:NativeLinkerOption=/ENTRY:mainCRTStartup")).getBytes(StandardCharsets.ISO_8859_1)));
    }

    /**
     * The content of a {@code native-image.properties} file with the given options.
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

    // ------------------------------------------------------------------------------------------------ macOS executable

    /**
     * SWT runs on the first thread of macOS native executables : warns when an option moves the application off it (the
     * Desktop AWT extension does not park the first thread when this extension is present).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    @Produce(ArtifactResultBuildItem.class)
    void macosMainThread(SwtPlatformBuildItem platform, NativeConfig nativeConfig) {
        if (platform.isMac() && nativeArgs(nativeConfig).anyMatch(DesktopSwtProcessor::isRunMainInNewThreadOption)) {
            LOGGER.warn("-H:+RunMainInNewThread moves main off the first thread of the process : SWT cannot create its"
                    + " Display on macOS");
        }
    }

    static Stream<String> nativeArgs(NativeConfig nativeConfig) {
        return Stream.concat(nativeConfig.additionalBuildArgs().orElse(List.of()).stream(),
                nativeConfig.additionalBuildArgsAppend().orElse(List.of()).stream());
    }

    /**
     * Whether a native image option moves {@code main} to a new thread : {@code -H:+RunMainInNewThread}, not
     * {@code -H:-RunMainInNewThread}.
     */
    static boolean isRunMainInNewThreadOption(String option) {
        return option.trim().startsWith("-H:+RunMainInNewThread");
    }

    /**
     * The minimum macOS version and the SDK version of the macOS executable
     * ({@code quarkus.desktop.swt.macos.jdk-build-version}) : the ones of the {@code java} launcher of the JDK used for
     * the native build, so that the executable starts on the same macOS versions, and AppKit gives its windows and
     * controls the same look, as in JVM mode.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void macExecutable(NativeImageRunnerBuildItem nativeImageRunner, DesktopSwtConfig config, NativeConfig nativeConfig,
            Capabilities capabilities, BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        List<String> args = macExecutableArgs(targetWindowingSystem(OS.current(), nativeImageRunner.isContainerBuild()),
                config.macos().jdkBuildVersion(), capabilities.isPresent(AWT_CAPABILITY), builderJdkHome(nativeConfig));
        if (!args.isEmpty()) {
            LOGGER.debugf("macOS executable options : %s", args);
            generatedResources.produce(new GeneratedResourceBuildItem(MAC_NATIVE_IMAGE_PROPERTIES,
                    nativeImageProperties(args).getBytes(StandardCharsets.ISO_8859_1)));
        }
    }

    /**
     * The options of the macOS executable : the {@code -platform_version} of the linker, with the versions of the
     * {@code java} launcher of the JDK used for the native build.
     *
     * @param target the windowing system of the executable ({@link #targetWindowingSystem}) : a container build
     *        produces a Linux executable
     * @param jdkBuildVersion {@code quarkus.desktop.swt.macos.jdk-build-version}
     * @param awt whether the application has the Desktop AWT extension
     * @param jdkHome the home of the JDK used for the native build ({@link #builderJdkHome})
     * @return empty for an executable of another platform, when disabled, with the Desktop AWT extension, or when the
     *         versions of the launcher are unknown
     */
    static List<String> macExecutableArgs(WindowingSystem target, boolean jdkBuildVersion, boolean awt, Path jdkHome) {
        if (target != WindowingSystem.COCOA || !jdkBuildVersion) {
            return List.of();
        }
        if (awt) {
            // The Desktop AWT extension writes the same versions in macOS executables, unless
            // quarkus.desktop.awt.macos.jdk-build-version=false : a single -platform_version option, and a single
            // property that decides it
            LOGGER.debug("The Desktop AWT extension writes the minimum macOS version and the SDK version of the native"
                    + " executable (quarkus.desktop.awt.macos.jdk-build-version)");
            return List.of();
        }
        Optional<SwtMacExecutable.BuildVersion> version = SwtMacExecutable.launcherBuildVersion(jdkHome);
        if (version.isEmpty()) {
            LOGGER.warnf("The minimum macOS version and the SDK version of the java launcher of %s are unknown : the"
                    + " native executable declares the ones of the Xcode tools (it only starts on that macOS version"
                    + " and later, and gets the look of that version)", jdkHome);
            return List.of();
        }
        return List.of(version.get().linkerOption());
    }

    /**
     * The home of the JDK used by the native build : the GraalVM home, the Java home configured for native builds, or
     * the home of the JDK running the build.
     */
    static Path builderJdkHome(NativeConfig nativeConfig) {
        return nativeConfig.graalvmHome().filter(home -> !home.isBlank()).map(Path::of).filter(Files::isDirectory)
                .or(() -> Optional.ofNullable(nativeConfig.javaHome()).map(File::toPath).filter(Files::isDirectory))
                .orElse(Path.of(System.getProperty("java.home")));
    }

    // -------------------------------------------------------------------------------- exact reachability metadata

    /**
     * The lookups that SWT expects to fail, registered for native executables built with exact reachability metadata
     * ({@code --exact-reachability-metadata}), where a lookup missing from the metadata fails instead.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reachabilityMetadata(SwtPlatformBuildItem platform, NativeConfig nativeConfig,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        if (nativeArgs(nativeConfig).noneMatch(DesktopSwtProcessor::isExactReachabilityMetadataOption)) {
            return;
        }
        generatedResources.produce(new GeneratedResourceBuildItem(REACHABILITY_METADATA,
                reachabilityMetadata(List.of(platform.withPlatform(SwtClassesAndResources.NEGATIVE_CLASS_LOOKUPS,
                        SwtClassesAndResources.WINDOWS_NEGATIVE_CLASS_LOOKUPS,
                        SwtClassesAndResources.LINUX_NEGATIVE_CLASS_LOOKUPS,
                        SwtClassesAndResources.MAC_NEGATIVE_CLASS_LOOKUPS))).getBytes(StandardCharsets.UTF_8)));
    }

    static boolean isExactReachabilityMetadataOption(String option) {
        String trimmed = option.trim();
        // --exact-reachability-metadata[=packages], --exact-reachability-metadata-path=..., and the former option
        return trimmed.startsWith("--exact-reachability-metadata") || trimmed.startsWith("-H:ThrowMissingRegistrationErrors");
    }

    /**
     * A reachability metadata file registering types (the format where a lookup of a missing type is expected to fail).
     */
    static String reachabilityMetadata(List<String> types) {
        Json.JsonArrayBuilder reflection = Json.array();
        for (String type : types) {
            reflection.add(Json.object().put("type", type));
        }
        StringBuilder json = new StringBuilder();
        try {
            Json.object().put("reflection", reflection).appendTo(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return json.toString();
    }
}
