package io.quarkiverse.desktop.awt.deployment;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import jakarta.enterprise.inject.spi.DeploymentException;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.deployment.DesktopTargetPlatformBuildItem.Platform;
import io.quarkiverse.desktop.awt.runtime.DesktopAwtConfig;
import io.quarkiverse.desktop.awt.runtime.DesktopAwtRecorder;
import io.quarkiverse.desktop.awt.runtime.graal.DesktopAwtFeature;
import io.quarkiverse.desktop.awt.runtime.graal.OverrideChecksFeature;
import io.quarkiverse.desktop.awt.runtime.macos.MacMainThread;
import io.quarkiverse.desktop.awt.runtime.macos.ParkMainThreadEnabled;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.arc.processor.ObserverInfo;
import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.NativeImageEnableAllCharsetsBuildItem;
import io.quarkus.deployment.builditem.NativeImageFeatureBuildItem;
import io.quarkus.deployment.builditem.QuarkusApplicationClassBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessFieldBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageSystemPropertyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveFieldBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.builditem.nativeimage.UnsupportedOSBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageRunnerBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathTree;
import io.quarkus.runtime.LocalesBuildTimeConfig;
import io.smallrye.common.os.OS;

// NativeOrNativeSourcesBuild and NativeBuild are deprecated, but Quarkus core has no replacement yet
@SuppressWarnings("deprecation")
class DesktopAwtProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopAwtProcessor.class);

    private static final String FEATURE = "desktop-awt";

    private static final String REASON = "Quarkus Desktop AWT";

    static final String QUARKUS_AWT_GROUP_ID = "io.quarkus";
    static final String QUARKUS_AWT_ARTIFACT_ID = "quarkus-awt";

    /**
     * The Windows substitutions of {@code io.quarkus:quarkus-awt} (made for headless Java2D) that break AWT GUI
     * applications. The other substitutions (the {@code JDKSubstitutions} marker class, which Quarkus core checks, the
     * font configurations, input methods of JDK 21) stay, except the macOS ones ({@link #QUARKUS_AWT_MAC_GUI_BLOCKERS})
     * and the Type 1 fonts one ({@link #QUARKUS_AWT_FEATURE_BLOCKERS}).
     */
    static final List<String> QUARKUS_AWT_WINDOWS_GUI_BLOCKERS = List.of(
            // WObjectPeer.initIDs() does nothing : every heavyweight peer (window, component, tray icon) crashes
            "io/quarkus/awt/runtime/Target_sun_awt_windows_WObjectPeer.class",
            // WindowsFlags.initNativeFlags() returns false : no DPI awareness, Direct3D flags ignored
            "io/quarkus/awt/runtime/Target_sun_java2d_windows_WindowsFlags.class",
            // WToolkit.getPrintJob(...) throws : no AWT print jobs (Toolkit.getPrintJob)
            "io/quarkus/awt/runtime/Target_sun_awt_windows_WToolkit.class");

    /**
     * The substitutions of {@code io.quarkus:quarkus-awt} that disable an AWT feature on every platform, removed when
     * present. A quarkus-awt version without them has the feature : they are not reported as missing.
     */
    static final List<String> QUARKUS_AWT_FEATURE_BLOCKERS = List.of(
            // Type1Font.verifyPFA/verifyPFB throw : no Type 1 fonts (.pfa and .pfb files,
            // Font.createFont(Font.TYPE1_FONT, ...), and the Type 1 fonts of the system on Linux). Native executables
            // read them as the JDK does, with the freetype library of the JDK, which supports them, and the JNI callback
            // Type1Font.readFile that quarkus-awt registers
            "io/quarkus/awt/runtime/Target_sun_font_Type1Font.class");

    /**
     * The resource of the reflection configuration of the classes registered with their public members.
     */
    static final String PUBLIC_MEMBERS_REFLECT_CONFIG = "META-INF/native-image/io.quarkiverse.desktop/"
            + "quarkus-desktop-awt-public-members/reflect-config.json";

    /**
     * The macOS substitutions of {@code io.quarkus:quarkus-awt} (Quarkus versions whose quarkus-awt supports macOS native
     * executables : the Quarkus pull request "Enable quarkus-awt on macOS", https://github.com/quarkusio/quarkus/pull/56979)
     * that force headless AWT. Absent from older versions : a name absent
     * from the jar removes nothing. {@code Target_sun_awt_FontConfiguration_Mac} stays (the macOS font configuration
     * stubs every lookup, its minimal {@code fontconfig.properties} is enough for GUI applications), and
     * {@code Target_sun_awt_HeadlessToolkit} and {@code MacHeadless}, only used by the removed ones, are then inert.
     */
    static final List<String> QUARKUS_AWT_MAC_GUI_BLOCKERS = List.of(
            // headless by default, createToolkit returns HeadlessToolkit(LWCToolkit), AWTError when headful
            "io/quarkus/awt/runtime/Target_sun_awt_PlatformGraphicsInfo_Mac.class",
            // initAppkit does nothing (AWTError when headful), getMultiClickTime returns 500, no NSImage:// images
            "io/quarkus/awt/runtime/Target_sun_lwawt_macosx_LWCToolkit.class",
            // rebuildDevices throws : no screen
            "io/quarkus/awt/runtime/Target_sun_awt_CGraphicsEnvironment.class",
            // PrinterJob.getPrinterJob throws
            "io/quarkus/awt/runtime/Target_sun_print_PlatformPrinterJobProxy.class");

    /**
     * A class of the quarkus-awt versions that support macOS native executables (and have the macOS substitutions).
     */
    static final String QUARKUS_AWT_MAC_SENTINEL = "io/quarkus/awt/runtime/Target_sun_awt_FontConfiguration_Mac.class";

    /**
     * The substitutions of quarkus-awt that are kept, known not to break AWT GUI applications.
     */
    static final Set<String> QUARKUS_AWT_KNOWN_KEPT = Set.of(
            "io/quarkus/awt/runtime/Target_sun_awt_FontConfiguration_Linux.class",
            "io/quarkus/awt/runtime/Target_sun_awt_FontConfiguration_Mac.class",
            "io/quarkus/awt/runtime/Target_sun_awt_FontConfiguration_Windows.class",
            "io/quarkus/awt/runtime/Target_sun_awt_HeadlessToolkit.class",
            "io/quarkus/awt/runtime/Target_sun_awt_im_CompositionAreaHandler.class",
            "io/quarkus/awt/runtime/Target_sun_awt_im_ExecutableInputMethodManager.class");

    static final String MAC_QUARKUS_TOO_OLD = "Quarkus Desktop needs a quarkus-awt with macOS support to build AWT and"
            + " Swing applications natively on macOS, found quarkus-awt %s : build Quarkus from the pull request \"Enable"
            + " quarkus-awt on macOS\" (https://github.com/quarkusio/quarkus/pull/56979, not in a Quarkus release yet), and"
            + " use GraalVM 25.1 or later on Apple silicon. JVM mode works with this version.";

    /**
     * The libraries a macOS native executable using AWT loads from its directory : copied there by GraalVM 25.1 and later
     * ({@code libjava} and {@code libjvm} are shims generated by GraalVM).
     */
    static final List<String> MAC_REQUIRED_LIBRARIES = List.of("libawt.dylib", "libawt_lwawt.dylib", "libosxapp.dylib",
            "libfontmanager.dylib", "libfreetype.dylib", "libjavajpeg.dylib", "liblcms.dylib", "libmlib_image.dylib",
            "libjava.dylib", "libjvm.dylib");

    /**
     * The {@code QuarkusApplication} of the Quarkus FX launcher, which runs JavaFX on the first thread itself.
     */
    static final String QUARKUS_FX_APPLICATION = "io.quarkiverse.fx.QuarkusFxApplication";

    /**
     * The interceptor binding that dev mode adds to the beans for the monitoring of ArC.
     */
    private static final DotName MONITORED = DotName.createSimple("io.quarkus.arc.runtime.dev.console.Monitored");

    private static final DotName QUARKUS_APPLICATION = DotName.createSimple("io.quarkus.runtime.QuarkusApplication");

    private static final DotName STARTUP_EVENT = DotName.createSimple("io.quarkus.runtime.StartupEvent");

    private static final DotName COMPONENT = DotName.createSimple("java.awt.Component");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    // ------------------------------------------------------------------------------------------------------ quarkus-awt

    @BuildStep
    RemovedResourceBuildItem removeQuarkusAwtGuiBlockers(CurateOutcomeBuildItem curateOutcome) {
        Optional<ResolvedDependency> quarkusAwt = quarkusAwt(curateOutcome);
        // The substitutions are private classes of quarkus-awt : one renamed or added would break GUI applications
        quarkusAwt.ifPresent(DesktopAwtProcessor::checkQuarkusAwtContent);
        Set<String> removed = new HashSet<>(QUARKUS_AWT_WINDOWS_GUI_BLOCKERS);
        // A name absent from the jar removes nothing (quarkus-awt versions without macOS support)
        removed.addAll(QUARKUS_AWT_MAC_GUI_BLOCKERS);
        removed.addAll(QUARKUS_AWT_FEATURE_BLOCKERS);
        return new RemovedResourceBuildItem(quarkusAwt.map(ResolvedDependency::getKey)
                .orElse(ArtifactKey.ga(QUARKUS_AWT_GROUP_ID, QUARKUS_AWT_ARTIFACT_ID)), removed);
    }

    /**
     * Fails native builds for macOS with a quarkus-awt version that does not support macOS native executables, saying
     * which Quarkus version is needed (quarkus-awt fails them too, without saying it).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void macSupportCheck(DesktopTargetPlatformBuildItem platform, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<UnsupportedOSBuildItem> unsupported) {
        if (platform.isMac()) {
            quarkusAwt(curateOutcome).flatMap(DesktopAwtProcessor::macUnsupportedMessage)
                    .ifPresent(message -> unsupported.produce(new UnsupportedOSBuildItem(OS.MAC, message)));
        }
    }

    /**
     * The error message of a macOS native build with the given quarkus-awt, empty when it supports macOS native
     * executables.
     */
    static Optional<String> macUnsupportedMessage(ResolvedDependency quarkusAwt) {
        return contains(quarkusAwt, QUARKUS_AWT_MAC_SENTINEL) ? Optional.empty()
                : Optional.of(String.format(MAC_QUARKUS_TOO_OLD, quarkusAwt.getVersion()));
    }

    /**
     * Warns about the substitutions of quarkus-awt that the extension expects to remove but does not find, and about the
     * substitutions it does not know.
     */
    static void checkQuarkusAwtContent(ResolvedDependency quarkusAwt) {
        List<String> missing = missingGuiBlockers(quarkusAwt);
        if (!missing.isEmpty()) {
            LOGGER.warnf("%s %s does not contain %s : Quarkus Desktop AWT cannot remove these substitutions, which break"
                    + " AWT GUI applications in native executables. If they were renamed, please report it to the Quarkus"
                    + " Desktop project.", quarkusAwt.getKey().toGacString(), quarkusAwt.getVersion(), missing);
        }
        List<String> unknown = unknownSubstitutions(quarkusAwt);
        if (!unknown.isEmpty()) {
            LOGGER.warnf("%s %s has substitutions that Quarkus Desktop AWT does not know : %s. If one forces headless AWT,"
                    + " AWT GUI applications break in native executables (for instance AWT windows crash or never show) :"
                    + " please report it to the Quarkus Desktop project.", quarkusAwt.getKey().toGacString(),
                    quarkusAwt.getVersion(), unknown);
        }
    }

    /**
     * The substitutions that the extension removes : the Windows ones, and the macOS ones when this quarkus-awt version
     * supports macOS.
     */
    static List<String> expectedGuiBlockers(ResolvedDependency quarkusAwt) {
        List<String> expected = new ArrayList<>(QUARKUS_AWT_WINDOWS_GUI_BLOCKERS);
        if (contains(quarkusAwt, QUARKUS_AWT_MAC_SENTINEL)) {
            expected.addAll(QUARKUS_AWT_MAC_GUI_BLOCKERS);
        }
        return expected;
    }

    /**
     * The expected substitutions ({@link #expectedGuiBlockers}) absent from quarkus-awt.
     */
    static List<String> missingGuiBlockers(ResolvedDependency quarkusAwt) {
        return expectedGuiBlockers(quarkusAwt).stream().filter(entry -> !contains(quarkusAwt, entry)).toList();
    }

    /**
     * The substitutions of quarkus-awt ({@code io/quarkus/awt/runtime/Target_*}) that are neither removed nor known to be
     * harmless.
     */
    static List<String> unknownSubstitutions(ResolvedDependency quarkusAwt) {
        List<String> expected = expectedGuiBlockers(quarkusAwt);
        List<String> unknown = new ArrayList<>();
        try {
            quarkusAwt.getContentTree().walk(visit -> {
                String name = visit.getRelativePath("/");
                if (name.startsWith("io/quarkus/awt/runtime/Target_") && name.endsWith(".class")
                        && !expected.contains(name) && !QUARKUS_AWT_FEATURE_BLOCKERS.contains(name)
                        && !QUARKUS_AWT_KNOWN_KEPT.contains(name)) {
                    unknown.add(name);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Unable to read %s", quarkusAwt);
        }
        unknown.sort(null);
        return unknown;
    }

    private static Optional<ResolvedDependency> quarkusAwt(CurateOutcomeBuildItem curateOutcome) {
        return curateOutcome.getApplicationModel().getRuntimeDependencies().stream()
                .filter(d -> QUARKUS_AWT_GROUP_ID.equals(d.getGroupId()) && QUARKUS_AWT_ARTIFACT_ID.equals(d.getArtifactId()))
                .findFirst();
    }

    private static boolean contains(ResolvedDependency dependency, String entry) {
        try {
            return dependency.getContentTree().contains(entry);
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Unable to read %s", dependency);
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ target platform

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    DesktopTargetPlatformBuildItem targetPlatform(NativeImageRunnerBuildItem nativeImageRunner) {
        return new DesktopTargetPlatformBuildItem(targetPlatform(OS.current(), nativeImageRunner.isContainerBuild()));
    }

    /**
     * The quarkus-awt rule : no cross compilation, and a container build produces a Linux executable.
     */
    static Platform targetPlatform(OS host, boolean containerBuild) {
        if (containerBuild) {
            return Platform.LINUX;
        }
        return switch (host) {
            case WINDOWS -> Platform.WINDOWS;
            case MAC -> Platform.MAC;
            default -> Platform.LINUX;
        };
    }

    // ------------------------------------------------------------------------------------------ run time initialization

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runtimeInitialization(DesktopTargetPlatformBuildItem platform,
            BuildProducer<RuntimeInitializedPackageBuildItem> packages,
            BuildProducer<RuntimeInitializedClassBuildItem> classes) {
        for (String packageName : platform.withPlatform(AwtClassesAndResources.RUNTIME_INITIALIZED_PACKAGES,
                AwtClassesAndResources.WINDOWS_RUNTIME_INITIALIZED_PACKAGES,
                AwtClassesAndResources.LINUX_RUNTIME_INITIALIZED_PACKAGES,
                AwtClassesAndResources.MAC_RUNTIME_INITIALIZED_PACKAGES)) {
            packages.produce(new RuntimeInitializedPackageBuildItem(packageName));
        }
        for (String className : platform.withPlatform(AwtClassesAndResources.RUNTIME_INITIALIZED_CLASSES,
                AwtClassesAndResources.WINDOWS_RUNTIME_INITIALIZED_CLASSES,
                AwtClassesAndResources.LINUX_RUNTIME_INITIALIZED_CLASSES,
                AwtClassesAndResources.MAC_RUNTIME_INITIALIZED_CLASSES)) {
            classes.produce(new RuntimeInitializedClassBuildItem(className));
        }
    }

    /**
     * The classes of the application and of its libraries (indexed or not) whose static initializer uses the desktop
     * modules : initialized at run time, as in JVM mode (see {@link DesktopStaticInitializerScanner}).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runtimeInitializedDesktopUsers(CombinedIndexBuildItem combinedIndex, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<RuntimeInitializedClassBuildItem> classes) {
        long start = System.nanoTime();
        Set<String> users = DesktopStaticInitializerScanner.scan(DesktopStaticInitializerScanner.classFiles(
                scannedTrees(curateOutcome.getApplicationModel()), combinedIndex.getIndex().getKnownClasses(),
                Thread.currentThread().getContextClassLoader()));
        LOGGER.debugf("Classes using the desktop modules in their static initializer, initialized at run time (%d ms) : %s",
                (System.nanoTime() - start) / 1_000_000, users);
        for (String className : users) {
            classes.produce(new RuntimeInitializedClassBuildItem(className));
        }
    }

    /**
     * The application and the libraries whose static initializers are scanned : not Quarkus itself nor the runtime
     * artifacts of the extensions, which initialize their classes as they need.
     */
    static List<PathTree> scannedTrees(ApplicationModel model) {
        List<PathTree> trees = new ArrayList<>();
        trees.add(model.getAppArtifact().getContentTree());
        for (ResolvedDependency dependency : model.getRuntimeDependencies()) {
            if (!dependency.isRuntimeExtensionArtifact() && !isQuarkus(dependency.getGroupId())) {
                trees.add(dependency.getContentTree());
            }
        }
        return trees;
    }

    private static boolean isQuarkus(String groupId) {
        return groupId.equals(QUARKUS_AWT_GROUP_ID) || groupId.startsWith(QUARKUS_AWT_GROUP_ID + ".");
    }

    // ------------------------------------------------------------------------------------------------------ reflection

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reflection(DesktopTargetPlatformBuildItem platform, BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            BuildProducer<ReflectiveFieldBuildItem> reflectiveFields) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(platform.withPlatform(
                AwtClassesAndResources.REFLECTIVE_CLASSES,
                AwtClassesAndResources.WINDOWS_REFLECTIVE_CLASSES,
                AwtClassesAndResources.LINUX_REFLECTIVE_CLASSES,
                AwtClassesAndResources.MAC_REFLECTIVE_CLASSES)).methods().fields().reason(REASON).build());
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(platform.withPlatform(
                AwtClassesAndResources.REFLECTIVE_CONSTRUCTORS,
                AwtClassesAndResources.WINDOWS_REFLECTIVE_CONSTRUCTORS,
                AwtClassesAndResources.LINUX_REFLECTIVE_CONSTRUCTORS,
                AwtClassesAndResources.MAC_REFLECTIVE_CONSTRUCTORS)).reason(REASON).build());
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(AwtClassesAndResources.TRANSFERRED_SERIALIZABLE_CLASSES)
                .serialization().reason(REASON).build());
        reflectiveClasses.produce(ReflectiveClassBuildItem
                .builder(AwtClassesAndResources.TEXT_ATTRIBUTE_SERIALIZABLE_CLASSES).serialization().reason(REASON)
                .build());
        for (String method : platform.withPlatform(AwtClassesAndResources.REFLECTIVE_METHODS,
                AwtClassesAndResources.WINDOWS_REFLECTIVE_METHODS,
                AwtClassesAndResources.LINUX_REFLECTIVE_METHODS,
                AwtClassesAndResources.MAC_REFLECTIVE_METHODS)) {
            MemberEntry entry = MemberEntry.method(method);
            reflectiveMethods.produce(new ReflectiveMethodBuildItem(REASON, entry.className(), entry.name(),
                    entry.parameterTypes()));
        }
        for (String field : platform.withPlatform(AwtClassesAndResources.REFLECTIVE_FIELDS,
                AwtClassesAndResources.WINDOWS_REFLECTIVE_FIELDS,
                AwtClassesAndResources.LINUX_REFLECTIVE_FIELDS,
                AwtClassesAndResources.MAC_REFLECTIVE_FIELDS)) {
            MemberEntry entry = MemberEntry.field(field);
            reflectiveFields.produce(new ReflectiveFieldBuildItem(REASON, entry.className(), entry.name()));
        }
    }

    /**
     * The classes registered with their public members : always the {@code REFLECTIVE_PUBLIC_MEMBERS} lists, and the
     * {@code JAVA_BEANS_CLASSES} lists when {@code quarkus.desktop.awt.java-beans.jdk-classes} is enabled or when
     * another extension needs them (the JavaBeans registration of the Swing classes, which extend AWT classes).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void publicMembers(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config,
            List<AwtJavaBeansClassesBuildItem> javaBeansRequests,
            BuildProducer<ReflectivePublicMembersBuildItem> publicMembers) {
        publicMembers.produce(new ReflectivePublicMembersBuildItem(List.of(platform.withPlatform(
                AwtClassesAndResources.REFLECTIVE_PUBLIC_MEMBERS,
                AwtClassesAndResources.WINDOWS_REFLECTIVE_PUBLIC_MEMBERS,
                AwtClassesAndResources.LINUX_REFLECTIVE_PUBLIC_MEMBERS,
                AwtClassesAndResources.MAC_REFLECTIVE_PUBLIC_MEMBERS))));
        if (config.javaBeans().jdkClasses() || !javaBeansRequests.isEmpty()) {
            publicMembers.produce(new ReflectivePublicMembersBuildItem(List.of(platform.withPlatform(
                    AwtClassesAndResources.JAVA_BEANS_CLASSES,
                    AwtClassesAndResources.WINDOWS_JAVA_BEANS_CLASSES,
                    AwtClassesAndResources.LINUX_JAVA_BEANS_CLASSES,
                    AwtClassesAndResources.MAC_JAVA_BEANS_CLASSES))));
        }
    }

    /**
     * Registers the classes of the {@link ReflectivePublicMembersBuildItem}s with their public constructors, methods
     * (inherited ones included) and fields. Quarkus has no build item for public methods and fields only : the extension
     * adds a reflection configuration file to the native build, as Quarkus does for its own reflection configuration.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void publicMembersReflectConfig(List<ReflectivePublicMembersBuildItem> publicMembers,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        Set<String> classNames = new TreeSet<>();
        for (ReflectivePublicMembersBuildItem item : publicMembers) {
            classNames.addAll(item.getClassNames());
        }
        if (!classNames.isEmpty()) {
            generatedResources.produce(new GeneratedResourceBuildItem(PUBLIC_MEMBERS_REFLECT_CONFIG,
                    publicMembersReflectConfig(classNames).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * The reflection configuration ({@code reflect-config.json}) of classes registered with their public members.
     */
    static String publicMembersReflectConfig(Collection<String> classNames) {
        Json.JsonArrayBuilder classes = Json.array();
        for (String className : classNames) {
            classes.add(Json.object()
                    .put("name", className)
                    .put("allPublicConstructors", true)
                    .put("allPublicMethods", true)
                    .put("allPublicFields", true));
        }
        StringBuilder json = new StringBuilder();
        try {
            classes.appendTo(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return json.toString();
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void serviceProviders(DesktopTargetPlatformBuildItem platform,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(platform.withPlatform(
                AwtClassesAndResources.SERVICE_PROVIDERS,
                AwtClassesAndResources.WINDOWS_SERVICE_PROVIDERS,
                AwtClassesAndResources.LINUX_SERVICE_PROVIDERS,
                AwtClassesAndResources.MAC_SERVICE_PROVIDERS)).methods().reason(REASON).build());
    }

    // ------------------------------------------------------------------------------------ exact reachability metadata

    /**
     * The lookups of the lists ({@code REFLECTIVE_TYPES}, {@code NEGATIVE_CLASS_LOOKUPS}, {@code METHOD_LOOKUPS}) and the
     * ones computed from names : the JavaBeans probes of the classes registered for the JavaBeans API (by this extension
     * and the Swing extension) with their supertypes and serialized forms, the {@code .properties} files next to the
     * resource bundles of the lists, the module resources of the resource globs of the lists, and the resource bundles of the
     * JDK that do not
     * exist, for the locales of the application.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reachabilityLookups(DesktopTargetPlatformBuildItem platform, LocalesBuildTimeConfig locales,
            List<ReflectivePublicMembersBuildItem> publicMembers,
            BuildProducer<ReachabilityLookupsBuildItem> lookups) {
        String[] reflectiveTypes = platform.withPlatform(AwtClassesAndResources.REFLECTIVE_TYPES,
                AwtClassesAndResources.WINDOWS_REFLECTIVE_TYPES,
                AwtClassesAndResources.LINUX_REFLECTIVE_TYPES,
                AwtClassesAndResources.MAC_REFLECTIVE_TYPES);
        Set<String> types = new TreeSet<>(List.of(reflectiveTypes));
        types.addAll(List.of(platform.withPlatform(AwtClassesAndResources.NEGATIVE_CLASS_LOOKUPS,
                AwtClassesAndResources.WINDOWS_NEGATIVE_CLASS_LOOKUPS,
                AwtClassesAndResources.LINUX_NEGATIVE_CLASS_LOOKUPS,
                AwtClassesAndResources.MAC_NEGATIVE_CLASS_LOOKUPS)));
        // the JavaBeans API probes the classes it handles : the classes registered with their public members and the
        // values of the REFLECTIVE_TYPES lists (not the var handles of java.lang.invoke)
        Set<String> javaBeansClasses = new TreeSet<>();
        for (ReflectivePublicMembersBuildItem item : publicMembers) {
            javaBeansClasses.addAll(item.getClassNames());
        }
        for (String type : reflectiveTypes) {
            if (!type.startsWith("java.lang.invoke.")) {
                javaBeansClasses.add(type);
            }
        }
        types.addAll(ReachabilityLookups.javaBeansTypes(javaBeansClasses));
        Set<String> globs = new TreeSet<>(ReachabilityLookups.bundlePropertiesGlobs(List.of(platform.withPlatform(
                AwtClassesAndResources.RESOURCE_BUNDLES,
                AwtClassesAndResources.WINDOWS_RESOURCE_BUNDLES,
                AwtClassesAndResources.LINUX_RESOURCE_BUNDLES,
                AwtClassesAndResources.MAC_RESOURCE_BUNDLES))));
        globs.addAll(ReachabilityLookups.javaBeansSerializedForms(javaBeansClasses));
        globs.addAll(ReachabilityLookups.moduleGlobs(List.of(platform.withPlatform(AwtClassesAndResources.RESOURCE_GLOBS,
                AwtClassesAndResources.WINDOWS_RESOURCE_GLOBS,
                AwtClassesAndResources.LINUX_RESOURCE_GLOBS,
                AwtClassesAndResources.MAC_RESOURCE_GLOBS))));
        // the files of the JDK that the recorder extracts into java.home at startup, absent when the JDK of the build has
        // none (a JDK without Metal shaders) : not found, instead of a MissingResourceRegistrationError
        globs.add(DesktopAwtRecorder.RESOURCES + "*");
        lookups.produce(new ReachabilityLookupsBuildItem(types, List.of(platform.withPlatform(
                AwtClassesAndResources.METHOD_LOOKUPS,
                AwtClassesAndResources.WINDOWS_METHOD_LOOKUPS,
                AwtClassesAndResources.LINUX_METHOD_LOOKUPS,
                AwtClassesAndResources.MAC_METHOD_LOOKUPS)), globs));
        Set<Locale> applicationLocales = new LinkedHashSet<>(locales.locales());
        locales.defaultLocale().ifPresent(applicationLocales::add);
        lookups.produce(ReachabilityLookups.missingBundles(AwtClassesAndResources.ABSENT_RESOURCE_BUNDLES,
                applicationLocales, "java.desktop"));
    }

    /**
     * The providers of the application and of its libraries for the services of the JDK desktop modules that the JDK
     * also looks up on the class path (ImageIO plugins, print services, sound providers...), and the lookups of these
     * service files.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void classPathServices(BuildProducer<ServiceProviderBuildItem> serviceProviders,
            BuildProducer<ReachabilityLookupsBuildItem> lookups) {
        List<String> files = new ArrayList<>();
        for (String service : AwtClassesAndResources.CLASS_PATH_SERVICES) {
            ServiceProviderBuildItem providers = ServiceProviderBuildItem.allProvidersFromClassPath(service);
            if (!providers.providers().isEmpty()) {
                serviceProviders.produce(providers);
            }
            files.add(ServiceProviderBuildItem.SPI_ROOT + service);
        }
        lookups.produce(new ReachabilityLookupsBuildItem(List.of(), List.of(), files));
    }

    /**
     * Registers the lookups of the {@link ReachabilityLookupsBuildItem}s, for native executables built with exact
     * reachability metadata ({@code quarkus.desktop.awt.exact-reachability-metadata}) : a reachability metadata file of
     * the native build (the format that has lookups expected to fail and module resources).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reachabilityMetadata(DesktopAwtConfig config, NativeConfig nativeConfig,
            List<ReachabilityLookupsBuildItem> lookups,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        if (!exactReachabilityMetadata(config, nativeConfig)) {
            return;
        }
        generatedResources.produce(new GeneratedResourceBuildItem(ReachabilityLookups.REACHABILITY_METADATA,
                ReachabilityLookups.reachabilityMetadata(lookups).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Whether the native build uses exact reachability metadata : {@code quarkus.desktop.awt.exact-reachability-metadata},
     * or the native image options of the Quarkus configuration.
     */
    static boolean exactReachabilityMetadata(DesktopAwtConfig config, NativeConfig nativeConfig) {
        return config.exactReachabilityMetadata().orElseGet(() -> Stream
                .concat(nativeConfig.additionalBuildArgs().orElse(List.of()).stream(),
                        nativeConfig.additionalBuildArgsAppend().orElse(List.of()).stream())
                .anyMatch(DesktopAwtProcessor::isExactReachabilityMetadataOption));
    }

    static boolean isExactReachabilityMetadataOption(String option) {
        String trimmed = option.trim();
        // --exact-reachability-metadata[=packages], --exact-reachability-metadata-path=..., and the former option
        return trimmed.startsWith("--exact-reachability-metadata") || trimmed.startsWith("-H:ThrowMissingRegistrationErrors");
    }

    // ------------------------------------------------------------------------------------------------------------- JNI

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void jni(DesktopTargetPlatformBuildItem platform, BuildProducer<JniRuntimeAccessBuildItem> jniClasses,
            BuildProducer<JniRuntimeAccessMethodBuildItem> jniMethods,
            BuildProducer<JniRuntimeAccessFieldBuildItem> jniFields) {
        jniClasses.produce(new JniRuntimeAccessBuildItem(true, true, true, platform.withPlatform(
                AwtClassesAndResources.JNI_RUNTIME_ACCESS_CLASSES,
                AwtClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_CLASSES,
                AwtClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_CLASSES,
                AwtClassesAndResources.MAC_JNI_RUNTIME_ACCESS_CLASSES)));
        for (String method : platform.withPlatform(AwtClassesAndResources.JNI_RUNTIME_ACCESS_METHODS,
                AwtClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_METHODS,
                AwtClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_METHODS,
                AwtClassesAndResources.MAC_JNI_RUNTIME_ACCESS_METHODS)) {
            MemberEntry entry = MemberEntry.method(method);
            jniMethods.produce(new JniRuntimeAccessMethodBuildItem(entry.className(), entry.name(), entry.parameterTypes()));
        }
        for (String field : platform.withPlatform(AwtClassesAndResources.JNI_RUNTIME_ACCESS_FIELDS,
                AwtClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_FIELDS,
                AwtClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_FIELDS,
                AwtClassesAndResources.MAC_JNI_RUNTIME_ACCESS_FIELDS)) {
            MemberEntry entry = MemberEntry.field(field);
            jniFields.produce(new JniRuntimeAccessFieldBuildItem(entry.className(), entry.name()));
        }
    }

    // ------------------------------------------------------------------------------------------------------- resources

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void resources(DesktopTargetPlatformBuildItem platform, BuildProducer<NativeImageResourceBundleBuildItem> bundles,
            BuildProducer<NativeImageResourcePatternsBuildItem> resources) {
        for (String bundle : platform.withPlatform(AwtClassesAndResources.RESOURCE_BUNDLES,
                AwtClassesAndResources.WINDOWS_RESOURCE_BUNDLES,
                AwtClassesAndResources.LINUX_RESOURCE_BUNDLES,
                AwtClassesAndResources.MAC_RESOURCE_BUNDLES)) {
            // Without module name : native-image finds the module of a JDK bundle from its package, and GraalVM 25.3+
            // checks the module lookups of bundles (UIDefaults) against the bundle name only
            bundles.produce(new NativeImageResourceBundleBuildItem(bundle));
        }
        for (String bundle : AwtClassesAndResources.ABSENT_RESOURCE_BUNDLES) {
            bundles.produce(new NativeImageResourceBundleBuildItem(bundle));
        }
        // resources of the JDK modules (see ResourceGlobs)
        ResourceGlobs.patterns(List.of(platform.withPlatform(AwtClassesAndResources.RESOURCE_GLOBS,
                AwtClassesAndResources.WINDOWS_RESOURCE_GLOBS,
                AwtClassesAndResources.LINUX_RESOURCE_GLOBS,
                AwtClassesAndResources.MAC_RESOURCE_GLOBS))).forEach(resources::produce);
    }

    // --------------------------------------------------------------------------------------------- run time defaults

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runTimeDefaults(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config,
            ApplicationInfoBuildItem applicationInfo,
            BuildProducer<NativeImageFeatureBuildItem> features,
            BuildProducer<NativeImageSystemPropertyBuildItem> builderProperties) {
        features.produce(new NativeImageFeatureBuildItem(DesktopAwtFeature.class));
        // the methods of the application and library classes whose declaration AWT and Swing check with reflection
        features.produce(new NativeImageFeatureBuildItem(OverrideChecksFeature.class));
        if (platform.isWindows() && config.windows().dpiAware()) {
            builderProperties.produce(new NativeImageSystemPropertyBuildItem(DesktopAwtFeature.DPI_AWARE, "true"));
        }
        if (platform.isMac()) {
            builderProperties.produce(new NativeImageSystemPropertyBuildItem(DesktopAwtFeature.MAC_APPLICATION_NAME,
                    config.macos().applicationName().orElse(applicationInfo.getName())));
        }
    }

    /**
     * The Java Access Bridge (Windows) : included, or ignored so that the toolkit does not fail to start when the user
     * enabled it.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void accessBridge(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<JniRuntimeAccessBuildItem> jniClasses,
            BuildProducer<NativeImageSystemPropertyBuildItem> builderProperties) {
        if (!platform.isWindows()) {
            // The Java Access Bridge only exists on Windows : ignore a configured assistive technology
            builderProperties.produce(
                    new NativeImageSystemPropertyBuildItem(DesktopAwtFeature.IGNORE_ASSISTIVE_TECHNOLOGIES, "true"));
            return;
        }
        if (config.windows().accessBridge()) {
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(AwtClassesAndResources.ACCESS_BRIDGE_PROVIDER)
                    .methods().reason(REASON).build());
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(AwtClassesAndResources.ACCESSIBLE_ROLE)
                    .fields().reason(REASON).build());
            jniClasses.produce(new JniRuntimeAccessBuildItem(true, true, true, AwtClassesAndResources.ACCESS_BRIDGE));
        } else {
            builderProperties.produce(
                    new NativeImageSystemPropertyBuildItem(DesktopAwtFeature.IGNORE_ASSISTIVE_TECHNOLOGIES, "true"));
        }
    }

    // ------------------------------------------------------------------------------------------------ java.home, fonts

    /**
     * All the charsets : a native executable only has the standard ones otherwise. Fonts in legacy encodings need them on
     * every platform (the cmap subtables and name records in Shift_JIS, GBK, Big5, EUC-KR or Johab of TrueType fonts :
     * {@code sun.font.CMap}, {@code TrueTypeFont} ; the encoders of the X11 fonts, {@code sun.font.XMap}), as do the text
     * transfers in the charsets of other applications and the RTF font charsets of Swing ({@code \fcharset} :
     * windows-125x, ms932, ms936...). The JDK font configuration of Windows uses windows-125x, GBK, windows-31j,
     * x-windows-949... : on Windows, the minimal font configuration leaves them out (a smaller native executable).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void charsets(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config,
            BuildProducer<NativeImageEnableAllCharsetsBuildItem> charsets) {
        if (!platform.isWindows() || config.windows().fontConfiguration() == DesktopAwtConfig.FontConfiguration.JDK) {
            charsets.produce(new NativeImageEnableAllCharsetsBuildItem());
        }
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem runtimeHome(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config,
            NativeConfig nativeConfig, DesktopAwtRecorder recorder,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<NativeImageResourceBuildItem> resources,
            BuildProducer<DesktopAwtRuntimeInitBuildItem> runtimeInit) {
        Path jdkHome = builderJdkHome(nativeConfig);
        embed(jdkHome.resolve("lib").resolve("psfontj2d.properties"), DesktopAwtRecorder.POSTSCRIPT_FONTS,
                generatedResources, resources);
        String fontConfiguration = null;
        if (platform.isWindows() && config.windows().fontConfiguration() == DesktopAwtConfig.FontConfiguration.JDK) {
            byte[] data = embed(jdkHome.resolve("lib").resolve("fontconfig.properties.src"),
                    DesktopAwtRecorder.FONT_CONFIGURATION, generatedResources, resources);
            if (data != null) {
                fontConfiguration = "quarkus-desktop-awt-fonts-" + sha256(data).substring(0, 16);
            }
        }
        // Before the application starts, so before any AWT class is used : the other steps that use AWT at startup
        // consume DesktopAwtRuntimeInitBuildItem
        recorder.initRuntimeHome(fontConfiguration, platform.isMac());
        runtimeInit.produce(new DesktopAwtRuntimeInitBuildItem());
        return new ServiceStartBuildItem(FEATURE);
    }

    /**
     * The home of the JDK used by the native build : the GraalVM home, the Java home configured for native builds, or the
     * home of the JDK running the build.
     */
    static Path builderJdkHome(NativeConfig nativeConfig) {
        return nativeConfig.graalvmHome().filter(home -> !home.isBlank()).map(Path::of).filter(Files::isDirectory)
                .or(() -> Optional.ofNullable(nativeConfig.javaHome()).map(File::toPath).filter(Files::isDirectory))
                .orElse(Path.of(System.getProperty("java.home")));
    }

    /**
     * Embeds a file of the JDK in the native executable.
     *
     * @return the content of the file, or {@code null} when it does not exist
     */
    private static byte[] embed(Path file, String resource, BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<NativeImageResourceBuildItem> resources) {
        if (!Files.isRegularFile(file)) {
            LOGGER.warnf("%s not found : it is not included in the native executable", file);
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            generatedResources.produce(new GeneratedResourceBuildItem(resource, data));
            resources.produce(new NativeImageResourceBuildItem(resource));
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------- Windows executable

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void windowsExecutable(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config,
            OutputTargetBuildItem outputTarget, BuildProducer<GeneratedResourceBuildItem> generatedResources)
            throws IOException {
        if (!platform.isWindows()) {
            return;
        }
        List<String> args = WindowsExecutable.nativeImageArgs(config.windows(), outputTarget.getOutputDirectory());
        if (!args.isEmpty()) {
            LOGGER.debugf("Windows executable options : %s", args);
            generatedResources.produce(new GeneratedResourceBuildItem(WindowsExecutable.NATIVE_IMAGE_PROPERTIES,
                    WindowsExecutable.nativeImageProperties(args).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Copies the Visual C++ runtime next to the native executable. {@code ArtifactResultBuildItem} is never produced :
     * it makes this step run after the native build.
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void copyVcRuntime(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config, NativeConfig nativeConfig,
            NativeImageBuildItem nativeImage, BuildProducer<ArtifactResultBuildItem> artifactResults) {
        if (platform.isWindows() && config.windows().copyVcRuntime()) {
            WindowsExecutable.copyVcRuntime(builderJdkHome(nativeConfig), nativeImage.getPath());
        }
    }

    // ------------------------------------------------------------------------------------------------ macOS executable

    /**
     * Keeps the first thread of macOS native executables in the Cocoa event loop (see
     * {@code io.quarkiverse.desktop.awt.runtime.macos.MacMainThread}).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void macosMainThread(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config, NativeConfig nativeConfig,
            Optional<QuarkusApplicationClassBuildItem> quarkusApplication,
            BuildProducer<NativeImageSystemPropertyBuildItem> builderProperties) {
        if (!platform.isMac()) {
            return;
        }
        DesktopAwtConfig.Macos macos = config.macos();
        builderProperties.produce(new NativeImageSystemPropertyBuildItem(ParkMainThreadEnabled.PROPERTY,
                String.valueOf(macos.parkMainThread())));
        builderProperties.produce(new NativeImageSystemPropertyBuildItem(MacMainThread.STACK_SIZE_PROPERTY,
                String.valueOf(macos.mainThreadStackSize().asLongValue())));
        builderProperties.produce(new NativeImageSystemPropertyBuildItem(MacMainThread.EXIT_HALT_TIMEOUT_PROPERTY,
                String.valueOf(macos.exitHaltTimeout().toMillis())));
        boolean fxLauncher = quarkusApplication.map(item -> QUARKUS_FX_APPLICATION.equals(item.getClassName()))
                .orElse(false);
        if (!macos.parkMainThread() && !fxLauncher) {
            if (isPresent(QUARKUS_FX_APPLICATION, Thread.currentThread().getContextClassLoader())) {
                // a @QuarkusMain of the application that delegates to QuarkusFxApplication, which then runs the Cocoa
                // event loop on the first thread itself
                LOGGER.debug("quarkus.desktop.awt.macos.park-main-thread=false with Quarkus FX : the first thread must call"
                        + " QuarkusFxApplication.run");
            } else {
                LOGGER.warn("quarkus.desktop.awt.macos.park-main-thread=false : no thread runs the Cocoa event loop, an AWT"
                        + " or Swing user interface hangs at its first window");
            }
        }
        if (Stream.concat(nativeConfig.additionalBuildArgs().orElse(List.of()).stream(),
                nativeConfig.additionalBuildArgsAppend().orElse(List.of()).stream())
                .anyMatch(DesktopAwtProcessor::isRunMainInNewThreadOption)) {
            LOGGER.warn("-H:+RunMainInNewThread moves main off the first thread of the process : an AWT or Swing user"
                    + " interface hangs on macOS");
        }
        if (macos.parkMainThread()) {
            checkQuarkusRun(Thread.currentThread().getContextClassLoader());
        }
    }

    /**
     * Whether a native image option moves {@code main} to a new thread : {@code -H:+RunMainInNewThread}, not
     * {@code -H:-RunMainInNewThread}.
     */
    static boolean isRunMainInNewThreadOption(String option) {
        return option.trim().startsWith("-H:+RunMainInNewThread");
    }

    static boolean isPresent(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Warns when {@code Quarkus.run}, which the extension replaces on macOS, changed.
     */
    static void checkQuarkusRun(ClassLoader classLoader) {
        try {
            List<String> missing = QuarkusRunCheck.missingSteps(classLoader);
            if (!missing.isEmpty()) {
                LOGGER.warnf("Quarkus.run(Class, BiConsumer, String...) of this Quarkus version does not do %s any more :"
                        + " Quarkus Desktop AWT replaces it on macOS and may not start the application as Quarkus does."
                        + " Please report it to the Quarkus Desktop project.", missing);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf(e, "Unable to check Quarkus.run");
        }
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void macExecutable(DesktopTargetPlatformBuildItem platform, DesktopAwtConfig config, NativeConfig nativeConfig,
            ApplicationInfoBuildItem applicationInfo, OutputTargetBuildItem outputTarget,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) throws IOException {
        if (!platform.isMac()) {
            return;
        }
        List<String> args = new ArrayList<>();
        if (config.macos().jdkBuildVersion()) {
            Path jdkHome = builderJdkHome(nativeConfig);
            Optional<MacExecutable.BuildVersion> version = MacExecutable.launcherBuildVersion(jdkHome);
            version.ifPresentOrElse(v -> args.add(v.linkerOption()), () -> LOGGER.warnf("The minimum macOS version and"
                    + " the SDK version of the java launcher of %s are unknown : the native executable declares the ones"
                    + " of the Xcode tools (it only starts on that macOS version and later)", jdkHome));
        }
        if (config.macos().infoPlist()) {
            String name = config.macos().applicationName().orElse(applicationInfo.getName());
            args.addAll(MacExecutable.nativeImageArgs(MacExecutable.infoPlist(name, applicationInfo.getVersion()),
                    outputTarget.getOutputDirectory()));
        }
        if (!args.isEmpty()) {
            LOGGER.debugf("macOS executable options : %s", args);
            generatedResources.produce(new GeneratedResourceBuildItem(MacExecutable.NATIVE_IMAGE_PROPERTIES,
                    WindowsExecutable.nativeImageProperties(args).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Checks that the libraries of the JDK that a macOS native executable loads are next to it (GraalVM 25.1 and later
     * copies them). {@code ArtifactResultBuildItem} is never produced : it makes this step run after the native build.
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void checkMacLibraries(DesktopTargetPlatformBuildItem platform, NativeImageBuildItem nativeImage,
            BuildProducer<ArtifactResultBuildItem> artifactResults) {
        if (!platform.isMac() || nativeImage.isReused()) {
            return;
        }
        List<String> missing = MacExecutable.missingLibraries(nativeImage.getPath());
        if (missing.size() == MAC_REQUIRED_LIBRARIES.size()) {
            LOGGER.warnf("The native executable %s has no AWT library next to it : either it does not use AWT, or this"
                    + " GraalVM does not package the macOS AWT libraries (GraalVM 25.1 or later is needed)",
                    nativeImage.getPath());
        } else if (!missing.isEmpty()) {
            throw new IllegalStateException("The native executable " + nativeImage.getPath() + " needs " + missing
                    + " next to it : this GraalVM does not package the macOS AWT libraries (GraalVM 25.1 or later is"
                    + " needed, see https://github.com/oracle/graal/issues/13272)");
        }
    }

    // ----------------------------------------------------------------------------------------------------- CDI, dev mode

    /**
     * Checks the component beans (beans extending {@code java.awt.Component}) : errors for what cannot work, warnings for
     * what may not.
     * <ul>
     * <li>Error : a normal scoped component bean (a class bean or a producer). Its client proxy is a subclass : creating
     * it creates another component, outside the event dispatch thread, and the proxy of a Swing component cannot even be
     * loaded (it overrides the final methods of {@code javax.swing.JComponent}, which ArC cannot make non final).</li>
     * <li>Error : an interceptor binding on a component class (or inherited, or from a stereotype) : it intercepts the
     * hundreds of methods that the class inherits from AWT and Swing. The binding that dev mode adds for the monitoring of
     * ArC ({@code quarkus.arc.dev-mode.monitoring-enabled}) is ignored.</li>
     * <li>Warning : a {@code @Dependent} component bean observing {@code DesktopStartupEvent} : a new instance is
     * created for the notification, and destroyed (its {@code @PreDestroy} methods run) right after it, while its window
     * stays open.</li>
     * <li>Warning : a component bean injected into a {@code @QuarkusMain} class or a {@code StartupEvent} observer : it is
     * created on their thread, outside the event dispatch thread.</li>
     * </ul>
     */
    @BuildStep
    void validateComponentBeans(ValidationPhaseBuildItem validationPhase, CombinedIndexBuildItem combinedIndex,
            BuildProducer<ValidationErrorBuildItem> validationErrors) {
        IndexView index = combinedIndex.getIndex();
        List<Throwable> errors = new ArrayList<>();
        for (BeanInfo bean : validationPhase.getContext().beans()) {
            if (bean.isInterceptor() || bean.isDecorator()) {
                continue;
            }
            if (bean.getTypes().stream().anyMatch(type -> type.name().equals(QUARKUS_APPLICATION))) {
                warnInjectedComponents(bean, "the @QuarkusMain class", index);
            }
            DotName type = bean.getImplClazz() != null ? bean.getImplClazz().name() : bean.getProviderType().name();
            if (!isComponent(type, index)) {
                continue;
            }
            if (bean.getScope().isNormal()) {
                errors.add(new DeploymentException("The bean " + describe(bean) + " is @"
                        + bean.getScope().getDotName().withoutPackagePrefix() + " and extends java.awt.Component : its"
                        + " client proxy extends it too, so creating the proxy creates another component, outside the"
                        + " event dispatch thread (and the proxy of a Swing component cannot be loaded). Use @Singleton or"
                        + " @Dependent for AWT and Swing component beans."));
            }
            if (bean.isClassBean()) {
                Set<String> classBindings = new TreeSet<>();
                bean.getInterceptedMethodsBindings().forEach((method, bindings) -> {
                    if (isJdkClass(method.declaringClass().name())) {
                        bindings.stream().filter(binding -> !binding.name().equals(MONITORED))
                                .forEach(binding -> classBindings.add("@" + binding.name().withoutPackagePrefix()));
                    }
                });
                if (!classBindings.isEmpty()) {
                    errors.add(new DeploymentException("The component bean " + bean.getBeanClass() + " has a class"
                            + " interceptor binding " + classBindings + " : it intercepts the methods that the class"
                            + " inherits from AWT and Swing. Put the interceptor bindings (@RunOnEdt...) on methods, or on"
                            + " a presenter bean that is not a component."));
                }
            }
        }
        for (ObserverInfo observer : validationPhase.getContext().get(BuildExtension.Key.OBSERVERS)) {
            BeanInfo bean = observer.getDeclaringBean();
            MethodInfo method = observer.getObserverMethod();
            if (bean == null || method == null || Modifier.isStatic(method.flags())) {
                // a static observer creates no instance
                continue;
            }
            DotName observed = observer.getObservedType().name();
            if (bean.isClassBean() && BuiltinScope.DEPENDENT.is(bean.getScope())
                    && observed.equals(DesktopCdiProcessor.DESKTOP_STARTUP_EVENT)
                    && isComponent(bean.getBeanClass(), index)) {
                LOGGER.warnf("The component bean %s is @Dependent and observes DesktopStartupEvent : a new instance is"
                        + " created for the event and destroyed right after it (its @PreDestroy methods run) while its"
                        + " window stays open. Use @Singleton, or observe the event in another bean and open the window"
                        + " with Instance<%s>.", bean.getBeanClass(), bean.getBeanClass().withoutPackagePrefix());
            }
            if (observed.equals(STARTUP_EVENT)) {
                warnInjectedComponents(bean, "the StartupEvent observer " + method.declaringClass().name() + "."
                        + method.name() + "()", index);
            }
        }
        if (!errors.isEmpty()) {
            validationErrors.produce(new ValidationErrorBuildItem(errors));
        }
    }

    /**
     * Warns about the component beans injected directly into a bean that the main thread creates : they are created on
     * the main thread, and in the default auto mode, the {@code DesktopStartupEvent} observers run at the same time on the
     * event dispatch thread (a bean waiting for the event dispatch thread while ArC creates it deadlocks with them).
     */
    private static void warnInjectedComponents(BeanInfo bean, String what, IndexView index) {
        for (InjectionPointInfo injectionPoint : bean.getAllInjectionPoints()) {
            BeanInfo injected = injectionPoint.getResolvedBean();
            if (injectionPoint.isProgrammaticLookup() || injected == null) {
                continue;
            }
            DotName type = injected.getImplClazz() != null ? injected.getImplClazz().name()
                    : injected.getProviderType().name();
            if (isComponent(type, index)) {
                LOGGER.warnf("The component bean %s is injected into %s (%s) : it is created there, outside the event"
                        + " dispatch thread. Inject Instance<%s>, and get it on the event dispatch thread (a"
                        + " DesktopStartupEvent observer, a @RunOnEdt method).", type, what,
                        injectionPoint.getTargetInfo(), type.withoutPackagePrefix());
            }
        }
    }

    private static String describe(BeanInfo bean) {
        if (bean.isClassBean()) {
            return bean.getBeanClass().toString();
        }
        if (bean.isSynthetic() || bean.getDeclaringBean() == null) {
            return "synthetic bean " + bean.getProviderType();
        }
        return bean.getProviderType() + " (produced by " + bean.getTarget().map(Object::toString).orElse("?") + " of "
                + bean.getDeclaringBean().getBeanClass() + ")";
    }

    static boolean isJdkClass(DotName name) {
        String className = name.toString();
        return className.startsWith("java.") || className.startsWith("javax.");
    }

    /**
     * The AWT toolkit, its threads and windows outlive the application in dev mode (live reload) and in tests (several
     * applications in the same JVM) : dispose the windows when the application stops, after the {@code ShutdownEvent}
     * observers (a {@code ServiceStartBuildItem} registers the task before the one firing {@code ShutdownEvent}, and
     * shutdown tasks run in reverse order) and before the CDI container stops (the task is registered after the container
     * is initialized). The test application of continuous testing only disposes the windows it opened : the other ones
     * are those of the dev mode application.
     */
    @BuildStep(onlyIfNot = IsNormal.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem disposeWindowsOnShutdown(DesktopAwtRecorder recorder, ShutdownContextBuildItem shutdownContext,
            LaunchModeBuildItem launchMode, BeanContainerBuildItem beanContainer) {
        recorder.disposeWindowsOnShutdown(shutdownContext, launchMode.isAuxiliaryApplication());
        return new ServiceStartBuildItem(FEATURE);
    }

    /**
     * The AWT event dispatch thread outlives the application in dev mode (live reload) and in tests (several
     * applications in the same JVM), with the context class loader of the first application : dispatch the events with
     * the class loader of the running application, so that the classes that Swing loads by name on the event dispatch
     * thread (look and feels, editor kits...) are the application ones.
     */
    @BuildStep(onlyIfNot = IsNormal.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    void applicationClassLoaderOnEventDispatchThread(DesktopAwtRecorder recorder,
            ShutdownContextBuildItem shutdownContext) {
        recorder.useApplicationClassLoaderOnEventDispatchThread(shutdownContext);
    }

    /**
     * Whether the class extends {@code java.awt.Component}, using the index for application classes and class loading
     * (without initialization) for the others.
     */
    static boolean isComponent(DotName name, IndexView index) {
        DotName current = name;
        while (current != null) {
            if (current.equals(COMPONENT)) {
                return true;
            }
            ClassInfo classInfo = index.getClassByName(current);
            if (classInfo == null) {
                return isComponentClass(current.toString());
            }
            current = classInfo.superName();
        }
        return false;
    }

    private static boolean isComponentClass(String className) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (ClassLoader loader : Stream.of(classLoader, DesktopAwtProcessor.class.getClassLoader())
                .filter(l -> l != null).toList()) {
            try {
                return java.awt.Component.class.isAssignableFrom(Class.forName(className, false, loader));
            } catch (ClassNotFoundException | LinkageError e) {
                // try the next class loader
            }
        }
        return false;
    }
}
