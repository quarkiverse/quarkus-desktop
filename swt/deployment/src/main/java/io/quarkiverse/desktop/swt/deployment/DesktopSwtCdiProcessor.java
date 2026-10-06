package io.quarkiverse.desktop.swt.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkiverse.desktop.swt.UiThreadExecutor;
import io.quarkiverse.desktop.swt.runtime.DesktopSwtRecorder;
import io.quarkiverse.desktop.swt.runtime.RunOnUiThreadInterceptor;
import io.quarkiverse.desktop.swt.runtime.SwtApplication;
import io.quarkiverse.desktop.swt.runtime.SwtUi;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ArcConfig;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.deployment.staticmethods.InterceptedStaticMethodBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.arc.processor.ObserverInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Overridable;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.ApplicationStartBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.QuarkusApplicationClassBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.runtime.LaunchMode;

/**
 * The CDI integration : {@code SwtStartupEvent}, the user interface thread, and the main of the user interface
 * applications.
 */
class DesktopSwtCdiProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopSwtCdiProcessor.class);

    static final DotName SWT_STARTUP_EVENT = DotName.createSimple(SwtStartupEvent.class);

    static final DotName RUN_ON_UI_THREAD = DotName.createSimple(RunOnUiThread.class);

    /**
     * The SWT classes that cannot be the type of a normal scoped bean : the widgets, and the devices ({@code Display},
     * {@code Printer}), whose constructors create native resources.
     */
    static final List<String> UI_OBJECT_CLASSES = List.of("org.eclipse.swt.widgets.Widget",
            "org.eclipse.swt.graphics.Device");

    private static final DotName QUARKUS_MAIN = DotName.createSimple("io.quarkus.runtime.annotations.QuarkusMain");

    private static final DotName QUARKUS_APPLICATION = DotName.createSimple("io.quarkus.runtime.QuarkusApplication");

    private static final DotName SWT_APPLICATION = DotName.createSimple(SwtApplication.class);

    private static final DotName STARTUP_EVENT = DotName.createSimple("io.quarkus.runtime.StartupEvent");

    private static final DotName MONITORED = DotName.createSimple("io.quarkus.arc.runtime.dev.console.Monitored");

    private static final Set<DotName> RUN_ON_UI_THREAD_SCOPES = Set.of(DotName.createSimple(ApplicationScoped.class),
            DotName.createSimple(Singleton.class), DotName.createSimple(Dependent.class));

    private static final Set<DotName> RUN_ON_UI_THREAD_RETURN_TYPES = Set.of(
            DotName.createSimple("java.util.concurrent.CompletionStage"),
            DotName.createSimple("java.util.concurrent.CompletableFuture"));

    private static final DotName TIMEOUT = DotName.createSimple("org.eclipse.microprofile.faulttolerance.Timeout");

    private static final List<DotName> FAULT_TOLERANCE_THREADS = List.of(TIMEOUT,
            DotName.createSimple("org.eclipse.microprofile.faulttolerance.Asynchronous"),
            DotName.createSimple("io.smallrye.faulttolerance.api.AsynchronousNonBlocking"));

    /**
     * The name of {@code SwtLifecycle} in the constant pool of a class calling it.
     */
    private static final byte[] SWT_LIFECYCLE = SwtLifecycle.class.getName().replace('.', '/')
            .getBytes(StandardCharsets.US_ASCII);

    @BuildStep
    AdditionalBeanBuildItem beans() {
        // looked up by the recorder, and by the static methods of UiThread
        return AdditionalBeanBuildItem.builder().addBeanClasses(SwtUi.class).setUnremovable().build();
    }

    @BuildStep
    AdditionalBeanBuildItem uiThreadBeans() {
        // SwtApplication : removable, unless it is the main of the application
        return AdditionalBeanBuildItem.builder().addBeanClasses(UiThreadExecutor.class, RunOnUiThread.class,
                RunOnUiThreadInterceptor.class, SwtApplication.class).build();
    }

    // ---------------------------------------------------------------------------------------------- user interface

    /**
     * The application has a user interface when it observes {@code SwtStartupEvent} (decided at build time).
     */
    @BuildStep
    void userInterface(BeanDiscoveryFinishedBuildItem beanDiscovery, BuildProducer<SwtUiBuildItem> userInterface) {
        boolean ui = false;
        for (ObserverInfo observer : beanDiscovery.getObservers()) {
            if (observer.getObservedType().name().equals(SWT_STARTUP_EVENT)) {
                if (observer.isAsync()) {
                    LOGGER.warnf("%s observes SwtStartupEvent asynchronously : it is never notified, and the application"
                            + " has no user interface unless it observes it with @Observes (the event is fired"
                            + " synchronously on the user interface thread)", describe(observer));
                } else {
                    ui = true;
                }
            }
        }
        if (ui) {
            userInterface.produce(new SwtUiBuildItem());
        }
    }

    /**
     * The main of a user interface application without a {@code @QuarkusMain} : runs the user interface on the main
     * thread. Quarkus only uses it when the application has no {@code @QuarkusMain}. Overridable : the main of another
     * extension (Picocli...) replaces it, and the application then calls {@code SwtLifecycle.run()} itself.
     * <p>
     * Quarkus FX does not provide its main when {@code SwtLifecycle} is on the class path : this one runs the user
     * interface, and JavaFX runs embedded in SWT ({@code javafx.embed.swt.FXCanvas}). The earlier versions of Quarkus
     * FX declare an overridable main too, and Quarkus rejects the two producers ({@code ChainBuildException}).
     */
    @BuildStep
    @Overridable
    QuarkusApplicationClassBuildItem mainApplication(Optional<SwtUiBuildItem> userInterface) {
        return userInterface.isPresent() ? new QuarkusApplicationClassBuildItem(SwtApplication.class) : null;
    }

    /**
     * Keeps {@code SwtApplication}, which Quarkus looks up when it runs the application, and warns about the
     * {@code @QuarkusMain} of a user interface application that does not call {@code SwtLifecycle.run()} : the user
     * interface would never start, unless another class calls it.
     * <p>
     * The check is a heuristic : no warning when a {@code @QuarkusMain} class, or a {@code QuarkusApplication} that a main
     * may run ({@code Quarkus.run(Application.class)}), mentions {@code SwtLifecycle}.
     */
    @BuildStep
    void checkMainApplication(Optional<SwtUiBuildItem> userInterface, CombinedIndexBuildItem combinedIndex,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        if (userInterface.isEmpty()) {
            return;
        }
        unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(SwtApplication.class));
        IndexView index = combinedIndex.getIndex();
        Set<DotName> mains = new TreeSet<>();
        for (AnnotationInstance quarkusMain : index.getAnnotations(QUARKUS_MAIN)) {
            if (quarkusMain.target() != null && quarkusMain.target().kind() == AnnotationTarget.Kind.CLASS) {
                mains.add(quarkusMain.target().asClass().name());
            }
        }
        if (mains.isEmpty()) {
            return;
        }
        Set<DotName> candidates = new TreeSet<>(mains);
        index.getAllKnownImplementations(QUARKUS_APPLICATION).stream().map(ClassInfo::name)
                .filter(name -> !name.equals(SWT_APPLICATION)).forEach(candidates::add);
        if (candidates.stream().noneMatch(name -> references(name.toString(), SWT_LIFECYCLE))) {
            LOGGER.warnf("No @QuarkusMain calls SwtLifecycle.run() (%s) : the user interface starts (and SwtStartupEvent"
                    + " is fired) when the main thread runs it. Inject SwtLifecycle and call run() once the work before"
                    + " the user interface is done.", mains.stream().map(DotName::toString).collect(Collectors.joining(", ")));
        }
    }

    /**
     * Whether the class file of a class mentions the given name in its constant pool.
     */
    private static boolean references(String className, byte[] name) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (in == null) {
                return true;
            }
            return indexOf(in.readAllBytes(), name) >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        outer: for (int i = 0; i <= bytes.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (bytes[i + j] != part[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Configures the user interface before the {@code StartupEvent} observers (a {@code ServiceStartBuildItem}), once the
     * CDI container is initialized : they may queue tasks for the user interface thread.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem configureUserInterface(DesktopSwtRecorder recorder, ShutdownContextBuildItem shutdown,
            LaunchModeBuildItem launchMode, ArcConfig arcConfig, ApplicationInfoBuildItem applicationInfo,
            BeanContainerBuildItem beanContainer) {
        recorder.configureUserInterface(shutdown, launchMode.getLaunchMode(),
                arcConfig.test().disableApplicationLifecycleObservers(), valueOf(applicationInfo.getName()),
                valueOf(applicationInfo.getVersion()));
        return new ServiceStartBuildItem(DesktopSwtProcessor.FEATURE);
    }

    /**
     * {@code quarkus.application.name} and {@code quarkus.application.version} are not set in tests.
     */
    private static String valueOf(String applicationInfo) {
        return ApplicationInfoBuildItem.UNSET_VALUE.equals(applicationInfo) ? null : applicationInfo;
    }

    /**
     * Once the application started ({@code ApplicationStartBuildItem} : after the {@code StartupEvent} observers) :
     * <ul>
     * <li>registers the task that ends the user interface when the application stops, after the task firing
     * {@code ShutdownEvent} : the shutdown tasks run in the reverse order, the user interface stops before the
     * {@code ShutdownEvent} observers, whatever stops the application (a signal, the end of a test...);</li>
     * <li>in tests, where no main runs the user interface, runs it on a thread of the extension, when it is enabled.</li>
     * </ul>
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void startUserInterface(Optional<SwtUiBuildItem> userInterface, LaunchModeBuildItem launchMode,
            ApplicationStartBuildItem applicationStart, ShutdownContextBuildItem shutdown, DesktopSwtRecorder recorder) {
        recorder.stopUserInterfaceFirst(shutdown);
        if (userInterface.isPresent() && launchMode.getLaunchMode() == LaunchMode.TEST) {
            recorder.runUserInterfaceInTests();
        }
    }

    // --------------------------------------------------------------------------------------------------- build checks

    /**
     * Checks the beans with {@code @RunOnUiThread} methods : errors for what cannot work (other return types than
     * {@code void} and {@code CompletionStage}, other scopes than {@code @ApplicationScoped}, {@code @Singleton} and
     * {@code @Dependent}, private static methods, which ArC does not intercept), warnings for what may not (a fault
     * tolerance timeout or asynchronous method).
     */
    @BuildStep
    void validateRunOnUiThread(ValidationPhaseBuildItem validationPhase, BeanArchiveIndexBuildItem beanArchiveIndex,
            List<InterceptedStaticMethodBuildItem> interceptedStaticMethods,
            BuildProducer<ValidationErrorBuildItem> validationErrors) {
        IndexView index = beanArchiveIndex.getIndex();
        List<Throwable> errors = new ArrayList<>();
        for (BeanInfo bean : validationPhase.getContext().beans().classBeans()) {
            // a class binding on a widget bean is reported by validateWidgetBeans
            boolean widget = isUiObject(bean.getBeanClass(), index);
            boolean runOnUiThread = false;
            List<String> invalid = new ArrayList<>();
            for (Map.Entry<MethodInfo, Set<AnnotationInstance>> entry : bean.getInterceptedMethodsBindings().entrySet()) {
                MethodInfo method = entry.getKey();
                if (!hasRunOnUiThread(entry.getValue()) || widget && isSwtClass(method.declaringClass().name())) {
                    continue;
                }
                runOnUiThread = true;
                if (!hasValidReturnType(method)) {
                    invalid.add(describe(method) + " returns " + method.returnType());
                }
                warnFaultTolerance(method);
            }
            if (!invalid.isEmpty()) {
                errors.add(new DeploymentException((invalid.size() == 1 ? "@RunOnUiThread method " + invalid.get(0)
                        : "The bean " + bean.getBeanClass() + " has @RunOnUiThread methods (@RunOnUiThread on a class"
                                + " applies to its business methods) that return neither void nor a CompletionStage : "
                                + summary(invalid))
                        + " : a @RunOnUiThread method returns void (it runs later when called on another thread) or a"
                        + " CompletionStage (completed once it ran on the user interface thread)"));
            }
            if (runOnUiThread && !RUN_ON_UI_THREAD_SCOPES.contains(bean.getScope().getDotName())) {
                errors.add(new DeploymentException("The bean " + bean.getBeanClass() + " has @RunOnUiThread methods and"
                        + " is @" + bean.getScope().getDotName().withoutPackagePrefix() + " : its instance belongs to the"
                        + " context of the caller, which may be gone when the method runs on the user interface thread."
                        + " Use @ApplicationScoped, @Singleton or @Dependent."));
            }
        }
        for (InterceptedStaticMethodBuildItem staticMethod : interceptedStaticMethods) {
            MethodInfo method = staticMethod.getMethod();
            if (hasRunOnUiThread(staticMethod.getBindings())) {
                if (!hasValidReturnType(method)) {
                    errors.add(new DeploymentException("@RunOnUiThread method " + describe(method) + " returns "
                            + method.returnType() + " : a @RunOnUiThread method returns void (it runs later when called"
                            + " on another thread) or a CompletionStage (completed once it ran on the user interface"
                            + " thread)"));
                }
                warnFaultTolerance(method);
            }
        }
        for (AnnotationInstance annotation : index.getAnnotations(RUN_ON_UI_THREAD)) {
            if (annotation.target() != null && annotation.target().kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo method = annotation.target().asMethod();
                if (Modifier.isStatic(method.flags()) && Modifier.isPrivate(method.flags())) {
                    errors.add(new DeploymentException("@RunOnUiThread method " + describe(method) + " is private and"
                            + " static : ArC does not intercept it, it would run on the calling thread. Make it package"
                            + " private."));
                }
            }
        }
        if (!errors.isEmpty()) {
            validationErrors.produce(new ValidationErrorBuildItem(errors));
        }
    }

    private static boolean hasRunOnUiThread(Collection<AnnotationInstance> bindings) {
        return bindings.stream().anyMatch(binding -> binding.name().equals(RUN_ON_UI_THREAD));
    }

    private static boolean hasValidReturnType(MethodInfo method) {
        Type returnType = method.returnType();
        return returnType.kind() == Type.Kind.VOID || RUN_ON_UI_THREAD_RETURN_TYPES.contains(returnType.name());
    }

    private static String summary(List<String> items) {
        int shown = 5;
        return items.size() <= shown ? String.join(", ", items)
                : String.join(", ", items.subList(0, shown)) + " and " + (items.size() - shown) + " more";
    }

    /**
     * Warns about the fault tolerance annotations that break {@code @RunOnUiThread} : the interceptors run on the user
     * interface thread, a {@code @Timeout} interrupts it, and an asynchronous method runs on another thread.
     */
    private static void warnFaultTolerance(MethodInfo method) {
        for (DotName annotation : FAULT_TOLERANCE_THREADS) {
            if (method.hasDeclaredAnnotation(annotation) || method.declaringClass().hasDeclaredAnnotation(annotation)) {
                LOGGER.warnf("@RunOnUiThread method %s is @%s : the fault tolerance interceptor runs on the user interface"
                        + " thread, %s. Call it from a worker thread, and use @RunOnUiThread for the user interface update"
                        + " only.", describe(method), annotation.withoutPackagePrefix(),
                        annotation.equals(TIMEOUT) ? "and interrupts it when the timeout expires"
                                : "and runs the method on another thread");
            }
        }
    }

    /**
     * Checks the beans whose type is an SWT widget or device : errors for what cannot work, warnings for what may not.
     * <ul>
     * <li>Error : a normal scoped widget or device bean (a class bean or a producer). Its client proxy is a subclass,
     * created with the no-args constructor of the class : a {@code Shell} subclass would create another shell, on the
     * thread that first uses the bean, and a {@code Composite} subclass cannot be proxied (its no-args constructor is
     * package private).</li>
     * <li>Error : an interceptor binding on a widget class (or inherited, or from a stereotype) : it intercepts the
     * methods that the class inherits from SWT. The binding that dev mode adds for the monitoring of ArC is ignored.</li>
     * <li>Warning : a widget bean injected into a {@code @QuarkusMain} class or a {@code StartupEvent} observer : it is
     * created on their thread, before the user interface thread runs.</li>
     * </ul>
     */
    @BuildStep
    void validateWidgetBeans(ValidationPhaseBuildItem validationPhase, CombinedIndexBuildItem combinedIndex,
            BuildProducer<ValidationErrorBuildItem> validationErrors) {
        IndexView index = combinedIndex.getIndex();
        List<Throwable> errors = new ArrayList<>();
        for (BeanInfo bean : validationPhase.getContext().beans()) {
            if (bean.isInterceptor() || bean.isDecorator()) {
                continue;
            }
            if (bean.getTypes().stream().anyMatch(type -> type.name().equals(QUARKUS_APPLICATION))) {
                warnInjectedWidgets(bean, "the @QuarkusMain class", index);
            }
            DotName type = bean.getImplClazz() != null ? bean.getImplClazz().name() : bean.getProviderType().name();
            if (!isUiObject(type, index)) {
                continue;
            }
            if (bean.getScope().isNormal()) {
                errors.add(new DeploymentException("The bean " + describe(bean) + " is @"
                        + bean.getScope().getDotName().withoutPackagePrefix() + " and is an SWT widget or device : its"
                        + " client proxy extends it, so creating the proxy creates another widget (or Display), on the"
                        + " thread that first uses the bean. Use @Singleton or @Dependent, or better a bean that creates"
                        + " its widgets (SWT widgets need their parent when they are created)."));
            }
            if (bean.isClassBean()) {
                Set<String> classBindings = new TreeSet<>();
                bean.getInterceptedMethodsBindings().forEach((method, bindings) -> {
                    if (isSwtClass(method.declaringClass().name())) {
                        bindings.stream().filter(binding -> !binding.name().equals(MONITORED))
                                .forEach(binding -> classBindings.add("@" + binding.name().withoutPackagePrefix()));
                    }
                });
                if (!classBindings.isEmpty()) {
                    errors.add(new DeploymentException("The widget bean " + bean.getBeanClass() + " has a class"
                            + " interceptor binding " + classBindings + " : it intercepts the methods that the class"
                            + " inherits from SWT. Put the interceptor bindings (@RunOnUiThread...) on methods, or on a"
                            + " presenter bean that is not a widget."));
                }
            }
        }
        for (ObserverInfo observer : validationPhase.getContext().get(BuildExtension.Key.OBSERVERS)) {
            BeanInfo bean = observer.getDeclaringBean();
            MethodInfo method = observer.getObserverMethod();
            if (bean == null || method == null || Modifier.isStatic(method.flags())) {
                continue;
            }
            if (observer.getObservedType().name().equals(STARTUP_EVENT)) {
                warnInjectedWidgets(bean, "the StartupEvent observer " + method.declaringClass().name() + "."
                        + method.name() + "()", index);
            }
            if (bean.isClassBean() && BuiltinScope.DEPENDENT.is(bean.getScope())
                    && observer.getObservedType().name().equals(SWT_STARTUP_EVENT)) {
                LOGGER.debugf("The @Dependent bean %s observes SwtStartupEvent : a new instance is created for the event"
                        + " and destroyed right after it, while its widgets stay open", bean.getBeanClass());
            }
        }
        if (!errors.isEmpty()) {
            validationErrors.produce(new ValidationErrorBuildItem(errors));
        }
    }

    /**
     * Warns about the widget beans injected directly into a bean that the main thread creates before the user interface
     * thread runs.
     */
    private static void warnInjectedWidgets(BeanInfo bean, String what, IndexView index) {
        for (InjectionPointInfo injectionPoint : bean.getAllInjectionPoints()) {
            BeanInfo injected = injectionPoint.getResolvedBean();
            if (injectionPoint.isProgrammaticLookup() || injected == null) {
                continue;
            }
            DotName type = injected.getImplClazz() != null ? injected.getImplClazz().name()
                    : injected.getProviderType().name();
            if (isUiObject(type, index)) {
                LOGGER.warnf("The widget bean %s is injected into %s (%s) : it is created there, before the user interface"
                        + " thread runs. Inject Instance<%s>, and get it on the user interface thread (a SwtStartupEvent"
                        + " observer, a @RunOnUiThread method).", type, what, injectionPoint.getTargetInfo(),
                        type.withoutPackagePrefix());
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

    private static String describe(MethodInfo method) {
        return method.declaringClass().name() + "." + method.name() + "()";
    }

    private static String describe(ObserverInfo observer) {
        MethodInfo method = observer.getObserverMethod();
        return method != null ? describe(method) : observer.getBeanClass().toString();
    }

    static boolean isSwtClass(DotName name) {
        return name.toString().startsWith("org.eclipse.swt.");
    }

    /**
     * Whether the class is an SWT widget or device, using the index for application classes and class loading (without
     * initialization) for the others.
     */
    static boolean isUiObject(DotName name, IndexView index) {
        DotName current = name;
        while (current != null) {
            if (UI_OBJECT_CLASSES.contains(current.toString())) {
                return true;
            }
            ClassInfo classInfo = index.getClassByName(current);
            if (classInfo == null) {
                return isUiObjectClass(current.toString());
            }
            current = classInfo.superName();
        }
        return false;
    }

    private static boolean isUiObjectClass(String className) {
        if (className.startsWith("java.") || className.startsWith("jakarta.")) {
            return false;
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (ClassLoader loader : Stream.of(classLoader, DesktopSwtCdiProcessor.class.getClassLoader())
                .filter(l -> l != null).toList()) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                for (String uiObjectClass : UI_OBJECT_CLASSES) {
                    if (Class.forName(uiObjectClass, false, loader).isAssignableFrom(type)) {
                        return true;
                    }
                }
                return false;
            } catch (ClassNotFoundException | LinkageError e) {
                // try the next class loader
            }
        }
        return false;
    }
}
