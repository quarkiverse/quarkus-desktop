package io.quarkiverse.desktop.swing.deployment;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.MethodInfo;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.deployment.AwtJavaBeansClassesBuildItem;
import io.quarkiverse.desktop.awt.deployment.DesktopAwtRuntimeInitBuildItem;
import io.quarkiverse.desktop.awt.deployment.DesktopTargetPlatformBuildItem;
import io.quarkiverse.desktop.awt.deployment.MemberEntry;
import io.quarkiverse.desktop.awt.deployment.ReachabilityLookups;
import io.quarkiverse.desktop.awt.deployment.ReachabilityLookupsBuildItem;
import io.quarkiverse.desktop.awt.deployment.ReflectivePublicMembersBuildItem;
import io.quarkiverse.desktop.awt.deployment.ResourceGlobs;
import io.quarkiverse.desktop.swing.runtime.DesktopSwingBuildTimeConfig;
import io.quarkiverse.desktop.swing.runtime.DesktopSwingBuildTimeConfig.IncludedLookAndFeel;
import io.quarkiverse.desktop.swing.runtime.DesktopSwingRecorder;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessFieldBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveFieldBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;

// NativeOrNativeSourcesBuild is deprecated, but Quarkus core has no replacement yet
@SuppressWarnings("deprecation")
class DesktopSwingProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopSwingProcessor.class);

    private static final String FEATURE = "desktop-swing";

    private static final String REASON = "Quarkus Desktop Swing";

    static final String LOOK_AND_FEEL_PROPERTY = "quarkus.desktop.swing.look-and-feel";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    // ------------------------------------------------------------------------------------------ run time initialization

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runtimeInitialization(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<RuntimeInitializedPackageBuildItem> packages,
            BuildProducer<RuntimeInitializedClassBuildItem> classes) {
        for (String packageName : entries(platform, config, SwingClassesAndResources.RUNTIME_INITIALIZED_PACKAGES,
                SwingClassesAndResources.WINDOWS_RUNTIME_INITIALIZED_PACKAGES,
                SwingClassesAndResources.LINUX_RUNTIME_INITIALIZED_PACKAGES,
                SwingClassesAndResources.MAC_RUNTIME_INITIALIZED_PACKAGES)) {
            packages.produce(new RuntimeInitializedPackageBuildItem(packageName));
        }
        for (String className : entries(platform, config, SwingClassesAndResources.RUNTIME_INITIALIZED_CLASSES,
                SwingClassesAndResources.WINDOWS_RUNTIME_INITIALIZED_CLASSES,
                SwingClassesAndResources.LINUX_RUNTIME_INITIALIZED_CLASSES,
                SwingClassesAndResources.MAC_RUNTIME_INITIALIZED_CLASSES)) {
            classes.produce(new RuntimeInitializedClassBuildItem(className));
        }
    }

    // ------------------------------------------------------------------------------------------------------ reflection

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reflection(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            BuildProducer<ReflectiveFieldBuildItem> reflectiveFields) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(entries(platform, config,
                SwingClassesAndResources.REFLECTIVE_CLASSES,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_CLASSES,
                SwingClassesAndResources.LINUX_REFLECTIVE_CLASSES,
                SwingClassesAndResources.MAC_REFLECTIVE_CLASSES)).methods().fields().reason(REASON).build());
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(entries(platform, config,
                SwingClassesAndResources.REFLECTIVE_FIELD_CLASSES,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_FIELD_CLASSES,
                SwingClassesAndResources.LINUX_REFLECTIVE_FIELD_CLASSES,
                SwingClassesAndResources.MAC_REFLECTIVE_FIELD_CLASSES)).constructors(false).fields().reason(REASON)
                .build());
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(entries(platform, config,
                SwingClassesAndResources.REFLECTIVE_CONSTRUCTORS,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_CONSTRUCTORS,
                SwingClassesAndResources.LINUX_REFLECTIVE_CONSTRUCTORS,
                SwingClassesAndResources.MAC_REFLECTIVE_CONSTRUCTORS)).reason(REASON).build());
        for (String method : entries(platform, config, SwingClassesAndResources.REFLECTIVE_METHODS,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_METHODS,
                SwingClassesAndResources.LINUX_REFLECTIVE_METHODS,
                SwingClassesAndResources.MAC_REFLECTIVE_METHODS)) {
            MemberEntry entry = MemberEntry.method(method);
            reflectiveMethods.produce(new ReflectiveMethodBuildItem(REASON, entry.className(), entry.name(),
                    entry.parameterTypes()));
        }
        for (String field : entries(platform, config, SwingClassesAndResources.REFLECTIVE_FIELDS,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_FIELDS,
                SwingClassesAndResources.LINUX_REFLECTIVE_FIELDS,
                SwingClassesAndResources.MAC_REFLECTIVE_FIELDS)) {
            MemberEntry entry = MemberEntry.field(field);
            reflectiveFields.produce(new ReflectiveFieldBuildItem(REASON, entry.className(), entry.name()));
        }
    }

    /**
     * The classes registered with their public members : the {@code REFLECTIVE_PUBLIC_MEMBERS} lists, and the JavaBeans
     * registration of the Swing classes, and of the AWT classes they extend
     * ({@code quarkus.desktop.swing.java-beans.jdk-classes}).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void javaBeans(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<ReflectivePublicMembersBuildItem> publicMembers,
            BuildProducer<AwtJavaBeansClassesBuildItem> awtJavaBeans) {
        String[] classes = entries(platform, config, SwingClassesAndResources.REFLECTIVE_PUBLIC_MEMBERS,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_PUBLIC_MEMBERS,
                SwingClassesAndResources.LINUX_REFLECTIVE_PUBLIC_MEMBERS,
                SwingClassesAndResources.MAC_REFLECTIVE_PUBLIC_MEMBERS);
        if (classes.length > 0) {
            publicMembers.produce(new ReflectivePublicMembersBuildItem(List.of(classes)));
        }
        if (config.javaBeans().jdkClasses()) {
            publicMembers.produce(new ReflectivePublicMembersBuildItem(List.of(entries(platform, config,
                    SwingClassesAndResources.JAVA_BEANS_CLASSES,
                    SwingClassesAndResources.WINDOWS_JAVA_BEANS_CLASSES,
                    SwingClassesAndResources.LINUX_JAVA_BEANS_CLASSES,
                    SwingClassesAndResources.MAC_JAVA_BEANS_CLASSES))));
            awtJavaBeans.produce(new AwtJavaBeansClassesBuildItem());
        }
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void serviceProviders(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(entries(platform, config,
                SwingClassesAndResources.SERVICE_PROVIDERS,
                SwingClassesAndResources.WINDOWS_SERVICE_PROVIDERS,
                SwingClassesAndResources.LINUX_SERVICE_PROVIDERS,
                SwingClassesAndResources.MAC_SERVICE_PROVIDERS)).methods().reason(REASON).build());
    }

    /**
     * The application classes that Swing creates by name (see {@link SwingApplicationClasses}), and the icon lookups of
     * the application look and feels.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void applicationClasses(CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            BuildProducer<ReachabilityLookupsBuildItem> lookups) {
        SwingApplicationClasses classes = SwingApplicationClasses.scan(combinedIndex.getIndex(),
                Thread.currentThread().getContextClassLoader());
        LOGGER.debugf("Application classes used by Swing with reflection : %s", classes.summary());
        if (!classes.constructed.isEmpty()) {
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(classes.constructed.toArray(String[]::new))
                    .reason(REASON).build());
        }
        for (MethodInfo createUI : classes.createUIMethods.values()) {
            reflectiveMethods.produce(new ReflectiveMethodBuildItem(REASON, createUI));
        }
        lookups.produce(new ReachabilityLookupsBuildItem(List.of(), List.of(), classes.lookAndFeelIconGlobs));
    }

    /**
     * The lookups of the Swing lists for {@code --exact-reachability-metadata} ({@code REFLECTIVE_TYPES},
     * {@code NEGATIVE_CLASS_LOOKUPS}, {@code METHOD_LOOKUPS}), the {@code .properties} files next to the resource
     * bundles and the module resources of the resource globs of the lists (see the Desktop AWT extension, which writes
     * them to the native build).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void reachabilityLookups(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<ReachabilityLookupsBuildItem> lookups) {
        List<String> reflectiveTypes = List.of(entries(platform, config, SwingClassesAndResources.REFLECTIVE_TYPES,
                SwingClassesAndResources.WINDOWS_REFLECTIVE_TYPES,
                SwingClassesAndResources.LINUX_REFLECTIVE_TYPES,
                SwingClassesAndResources.MAC_REFLECTIVE_TYPES));
        // the JavaBeans API probes these values as it probes the classes registered for it (see the Desktop AWT
        // extension)
        Set<String> types = new TreeSet<>(ReachabilityLookups.javaBeansTypes(reflectiveTypes));
        // Nimbus probes : the names of classes that do not exist, not filtered by look and feel (harmless)
        types.addAll(List.of(platform.withPlatform(SwingClassesAndResources.NEGATIVE_CLASS_LOOKUPS,
                SwingClassesAndResources.WINDOWS_NEGATIVE_CLASS_LOOKUPS,
                SwingClassesAndResources.LINUX_NEGATIVE_CLASS_LOOKUPS,
                SwingClassesAndResources.MAC_NEGATIVE_CLASS_LOOKUPS)));
        Set<String> globs = new TreeSet<>(ReachabilityLookups.bundlePropertiesGlobs(List.of(entries(platform, config,
                SwingClassesAndResources.RESOURCE_BUNDLES,
                SwingClassesAndResources.WINDOWS_RESOURCE_BUNDLES,
                SwingClassesAndResources.LINUX_RESOURCE_BUNDLES,
                SwingClassesAndResources.MAC_RESOURCE_BUNDLES))));
        globs.addAll(ReachabilityLookups.moduleGlobs(List.of(entries(platform, config,
                SwingClassesAndResources.RESOURCE_GLOBS,
                SwingClassesAndResources.WINDOWS_RESOURCE_GLOBS,
                SwingClassesAndResources.LINUX_RESOURCE_GLOBS,
                SwingClassesAndResources.MAC_RESOURCE_GLOBS))));
        globs.addAll(ReachabilityLookups.moduleGlobs(List.of(entries(platform, config,
                SwingClassesAndResources.RESOURCE_LOOKUPS,
                SwingClassesAndResources.WINDOWS_RESOURCE_LOOKUPS,
                SwingClassesAndResources.LINUX_RESOURCE_LOOKUPS,
                SwingClassesAndResources.MAC_RESOURCE_LOOKUPS))));
        if (config.javaBeans().jdkClasses()) {
            globs.addAll(ReachabilityLookups.moduleGlobs(List.of(SwingClassesAndResources.JAVA_BEANS_ICONS)));
        }
        lookups.produce(new ReachabilityLookupsBuildItem(types, List.of(entries(platform, config,
                SwingClassesAndResources.METHOD_LOOKUPS,
                SwingClassesAndResources.WINDOWS_METHOD_LOOKUPS,
                SwingClassesAndResources.LINUX_METHOD_LOOKUPS,
                SwingClassesAndResources.MAC_METHOD_LOOKUPS)), globs));
    }

    /**
     * The classes named in the Synth XML files of the application, created by the beans decoder of Synth. For
     * {@code --exact-reachability-metadata}, also the lookups of the decoder : it queries their public constructors and
     * methods ({@code ConstructorFinder}, {@code MethodFinder}) and introspects them for their properties
     * ({@code <void property="...">}).
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void synthXmlClasses(ApplicationArchivesBuildItem applicationArchives,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<ReachabilityLookupsBuildItem> lookups) {
        Set<String> classes = new TreeSet<>();
        for (ApplicationArchive archive : applicationArchives.getAllArchives()) {
            archive.accept(tree -> tree.walk(visit -> {
                String resource = visit.getRelativePath("/");
                if (SynthXmlClasses.isCandidate(resource)) {
                    Set<String> found = SynthXmlClasses.classes(visit.getPath());
                    if (!found.isEmpty()) {
                        LOGGER.debugf("Classes of the Synth XML file %s : %s", resource, found);
                        classes.addAll(found);
                    }
                }
            }));
        }
        Set<String> existing = new TreeSet<>();
        for (String className : classes) {
            if (exists(className)) {
                existing.add(className);
            } else {
                LOGGER.warnf("The class %s of a Synth XML file was not found", className);
            }
        }
        if (!existing.isEmpty()) {
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(existing.toArray(String[]::new)).methods()
                    .fields().reason(REASON).build());
            lookups.produce(new ReachabilityLookupsBuildItem(ReachabilityLookups.javaBeansTypes(existing), List.of(),
                    List.of()));
        }
    }

    /**
     * The look and feel class set at startup ({@code quarkus.desktop.swing.look-and-feel}, a run time property read at
     * build time when it is set), created by name.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void configuredLookAndFeel(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        Optional<String> lookAndFeel = ConfigProvider.getConfig().getOptionalValue(LOOK_AND_FEEL_PROPERTY, String.class)
                .map(String::trim).filter(value -> !value.isEmpty());
        if (lookAndFeel.isEmpty()) {
            return;
        }
        Optional<IncludedLookAndFeel> jdkLookAndFeel = jdkLookAndFeel(lookAndFeel.get(), platform);
        if (jdkLookAndFeel.isPresent()) {
            if (!new IncludedLookAndFeels(config.includedLookAndFeels()).isIncluded(jdkLookAndFeel.get())) {
                LOGGER.warnf("The look and feel set at startup (%s=%s) is not included in the native executable"
                        + " (quarkus.desktop.swing.included-look-and-feels=%s) : the application will use Metal",
                        LOOK_AND_FEEL_PROPERTY, lookAndFeel.get(), config.includedLookAndFeels());
            }
        } else if (lookAndFeel.get().contains(".")) {
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(lookAndFeel.get()).reason(REASON).build());
        }
    }

    /**
     * The JDK look and feel of a value of {@code quarkus.desktop.swing.look-and-feel}, empty for a class name.
     */
    static Optional<IncludedLookAndFeel> jdkLookAndFeel(String lookAndFeel, DesktopTargetPlatformBuildItem platform) {
        return Optional.ofNullable(switch (lookAndFeel.toLowerCase(Locale.ROOT)) {
            case "cross-platform", "metal", "javax.swing.plaf.metal.metallookandfeel" -> IncludedLookAndFeel.METAL;
            case "nimbus", "javax.swing.plaf.nimbus.nimbuslookandfeel" -> IncludedLookAndFeel.NIMBUS;
            case "motif", "com.sun.java.swing.plaf.motif.motiflookandfeel" -> IncludedLookAndFeel.MOTIF;
            case "windows", "windows-classic", "com.sun.java.swing.plaf.windows.windowslookandfeel",
                    "com.sun.java.swing.plaf.windows.windowsclassiclookandfeel" ->
                IncludedLookAndFeel.WINDOWS;
            case "gtk", "com.sun.java.swing.plaf.gtk.gtklookandfeel" -> IncludedLookAndFeel.GTK;
            // Aqua on macOS : registered by the Desktop AWT extension, always included
            case "system" -> platform.select(IncludedLookAndFeel.WINDOWS, IncludedLookAndFeel.GTK, null);
            default -> null;
        });
    }

    // ------------------------------------------------------------------------------------------------------------- JNI

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void jni(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<JniRuntimeAccessBuildItem> jniClasses,
            BuildProducer<JniRuntimeAccessMethodBuildItem> jniMethods,
            BuildProducer<JniRuntimeAccessFieldBuildItem> jniFields) {
        String[] classes = entries(platform, config, SwingClassesAndResources.JNI_RUNTIME_ACCESS_CLASSES,
                SwingClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_CLASSES,
                SwingClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_CLASSES,
                SwingClassesAndResources.MAC_JNI_RUNTIME_ACCESS_CLASSES);
        if (classes.length > 0) {
            jniClasses.produce(new JniRuntimeAccessBuildItem(true, true, true, classes));
        }
        for (String method : entries(platform, config, SwingClassesAndResources.JNI_RUNTIME_ACCESS_METHODS,
                SwingClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_METHODS,
                SwingClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_METHODS,
                SwingClassesAndResources.MAC_JNI_RUNTIME_ACCESS_METHODS)) {
            MemberEntry entry = MemberEntry.method(method);
            jniMethods.produce(new JniRuntimeAccessMethodBuildItem(entry.className(), entry.name(), entry.parameterTypes()));
        }
        for (String field : entries(platform, config, SwingClassesAndResources.JNI_RUNTIME_ACCESS_FIELDS,
                SwingClassesAndResources.WINDOWS_JNI_RUNTIME_ACCESS_FIELDS,
                SwingClassesAndResources.LINUX_JNI_RUNTIME_ACCESS_FIELDS,
                SwingClassesAndResources.MAC_JNI_RUNTIME_ACCESS_FIELDS)) {
            MemberEntry entry = MemberEntry.field(field);
            jniFields.produce(new JniRuntimeAccessFieldBuildItem(entry.className(), entry.name()));
        }
    }

    // ------------------------------------------------------------------------------------------------------- resources

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void resources(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            BuildProducer<NativeImageResourceBundleBuildItem> bundles,
            BuildProducer<NativeImageResourcePatternsBuildItem> resources) {
        for (String bundle : entries(platform, config, SwingClassesAndResources.RESOURCE_BUNDLES,
                SwingClassesAndResources.WINDOWS_RESOURCE_BUNDLES,
                SwingClassesAndResources.LINUX_RESOURCE_BUNDLES,
                SwingClassesAndResources.MAC_RESOURCE_BUNDLES)) {
            // Without module name : see the Desktop AWT extension
            bundles.produce(new NativeImageResourceBundleBuildItem(bundle));
        }
        // resources of the JDK modules (see the ResourceGlobs of the Desktop AWT extension)
        ResourceGlobs.patterns(List.of(entries(platform, config, SwingClassesAndResources.RESOURCE_GLOBS,
                SwingClassesAndResources.WINDOWS_RESOURCE_GLOBS,
                SwingClassesAndResources.LINUX_RESOURCE_GLOBS,
                SwingClassesAndResources.MAC_RESOURCE_GLOBS))).forEach(resources::produce);
        if (config.javaBeans().jdkClasses()) {
            // the icons of the bean infos of the Swing components
            ResourceGlobs.patterns(List.of(SwingClassesAndResources.JAVA_BEANS_ICONS)).forEach(resources::produce);
        }
    }

    // ---------------------------------------------------------------------------------------------- look and feel

    /**
     * Sets the configured look and feel when the application starts, before the {@code StartupEvent} observers (a
     * {@code ServiceStartBuildItem}) and so before the application runs, and after the AWT run time environment of native
     * executables is ready (setting a look and feel initializes the fonts).
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem lookAndFeel(DesktopSwingRecorder recorder,
            List<DesktopAwtRuntimeInitBuildItem> awtRuntimeInit) {
        recorder.setLookAndFeel();
        return new ServiceStartBuildItem(FEATURE);
    }

    /**
     * The entries of a common list and of the list of the target platform, for the included look and feels.
     */
    private static String[] entries(DesktopTargetPlatformBuildItem platform, DesktopSwingBuildTimeConfig config,
            String[] common, String[] windows, String[] linux, String[] mac) {
        return new IncludedLookAndFeels(config.includedLookAndFeels())
                .filter(platform.withPlatform(common, windows, linux, mac));
    }

    private static boolean exists(String className) {
        for (ClassLoader loader : new ClassLoader[] { Thread.currentThread().getContextClassLoader(),
                DesktopSwingProcessor.class.getClassLoader() }) {
            if (loader == null) {
                continue;
            }
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                // try the next class loader
            }
        }
        return false;
    }
}
