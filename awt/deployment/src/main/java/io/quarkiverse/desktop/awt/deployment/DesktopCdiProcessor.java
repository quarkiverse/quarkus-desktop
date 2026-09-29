package io.quarkiverse.desktop.awt.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkiverse.desktop.awt.EdtExecutor;
import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkiverse.desktop.awt.runtime.DesktopHandlers;
import io.quarkiverse.desktop.awt.runtime.DesktopLifecycleRecorder;
import io.quarkiverse.desktop.awt.runtime.DesktopUi;
import io.quarkiverse.desktop.awt.runtime.RunOnEdtInterceptor;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ArcConfig;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.arc.processor.ObserverInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationStartBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;

/**
 * The CDI integration : {@code DesktopStartupEvent} and the lifecycle of the user interface.
 */
class DesktopCdiProcessor {

    private static final Logger LOGGER = Logger.getLogger(DesktopCdiProcessor.class);

    static final DotName DESKTOP_STARTUP_EVENT = DotName.createSimple(DesktopStartupEvent.class);

    static final DotName RUN_ON_EDT = DotName.createSimple(RunOnEdt.class);

    private static final Set<DotName> RUN_ON_EDT_SCOPES = Set.of(DotName.createSimple(ApplicationScoped.class),
            DotName.createSimple(Singleton.class), DotName.createSimple(Dependent.class));

    private static final Set<DotName> RUN_ON_EDT_RETURN_TYPES = Set.of(
            DotName.createSimple("java.util.concurrent.CompletionStage"),
            DotName.createSimple("java.util.concurrent.CompletableFuture"));

    private static final String DESKTOP_EVENTS_PACKAGE = "java.awt.desktop.";

    private static final String QUIT_EVENT = "java.awt.desktop.QuitEvent";

    @BuildStep
    AdditionalBeanBuildItem beans() {
        // looked up by the recorder
        return AdditionalBeanBuildItem.builder().addBeanClasses(DesktopUi.class).setUnremovable().build();
    }

    @BuildStep
    AdditionalBeanBuildItem edtBeans() {
        return AdditionalBeanBuildItem.builder().addBeanClasses(EdtExecutor.class, RunOnEdt.class,
                RunOnEdtInterceptor.class).build();
    }

    /**
     * Checks the beans with {@code @RunOnEdt} methods : errors for what cannot work (other return types than
     * {@code void} and {@code CompletionStage}, other scopes than {@code @ApplicationScoped},
     * {@code @Singleton} and {@code @Dependent}), warnings for what may not (a component bean injected directly).
     */
    @BuildStep
    void validateRunOnEdt(ValidationPhaseBuildItem validationPhase, BeanArchiveIndexBuildItem beanArchiveIndex,
            BuildProducer<ValidationErrorBuildItem> validationErrors) {
        IndexView index = beanArchiveIndex.getIndex();
        List<Throwable> errors = new ArrayList<>();
        for (BeanInfo bean : validationPhase.getContext().beans().classBeans()) {
            boolean runOnEdt = false;
            for (Map.Entry<MethodInfo, Set<AnnotationInstance>> entry : bean.getInterceptedMethodsBindings().entrySet()) {
                if (entry.getValue().stream().noneMatch(binding -> binding.name().equals(RUN_ON_EDT))) {
                    continue;
                }
                runOnEdt = true;
                MethodInfo method = entry.getKey();
                Type returnType = method.returnType();
                if (returnType.kind() != Type.Kind.VOID && !RUN_ON_EDT_RETURN_TYPES.contains(returnType.name())) {
                    errors.add(new DeploymentException("@RunOnEdt method " + describe(method) + " returns " + returnType
                            + " : a @RunOnEdt method returns void (it runs later when called on another thread) or a"
                            + " CompletionStage (completed once it ran on the event dispatch thread)"));
                }
            }
            if (!runOnEdt) {
                continue;
            }
            if (!RUN_ON_EDT_SCOPES.contains(bean.getScope().getDotName())) {
                errors.add(new DeploymentException("The bean " + bean.getBeanClass() + " has @RunOnEdt methods and is @"
                        + bean.getScope().getDotName().withoutPackagePrefix() + " : its instance belongs to the context of"
                        + " the caller, which may be gone when the method runs on the event dispatch thread. Use"
                        + " @ApplicationScoped, @Singleton or @Dependent."));
            }
            for (InjectionPointInfo injectionPoint : bean.getAllInjectionPoints()) {
                BeanInfo injected = injectionPoint.getResolvedBean();
                if (injectionPoint.isProgrammaticLookup() || injected == null) {
                    continue;
                }
                DotName type = injected.getImplClazz() != null ? injected.getImplClazz().name()
                        : injected.getProviderType().name();
                if (DesktopAwtProcessor.isComponent(type, index)) {
                    LOGGER.warnf("The bean %s has @RunOnEdt methods and injects the component bean %s (%s) : the component"
                            + " is created with the bean, on the thread that first uses it, not always the event dispatch"
                            + " thread. Inject Instance<%s> (or Provider) and get it in the @RunOnEdt methods.",
                            bean.getBeanClass(), type, injectionPoint.getTargetInfo(), type.withoutPackagePrefix());
                }
            }
        }
        if (!errors.isEmpty()) {
            validationErrors.produce(new ValidationErrorBuildItem(errors));
        }
    }

    private static String describe(MethodInfo method) {
        return method.declaringClass().name() + "." + method.name() + "()";
    }

    /**
     * The application has a user interface when it observes {@code DesktopStartupEvent} (decided at build time : the
     * other applications never start the AWT toolkit, and their native executables do not reach its code).
     */
    @BuildStep
    void userInterface(BeanDiscoveryFinishedBuildItem beanDiscovery, BuildProducer<DesktopUiBuildItem> userInterface) {
        for (ObserverInfo observer : beanDiscovery.getObservers()) {
            if (!observer.isAsync() && observer.getObservedType().name().equals(DESKTOP_STARTUP_EVENT)) {
                userInterface.produce(new DesktopUiBuildItem());
                return;
            }
        }
        List<String> desktopEvents = desktopEvents(beanDiscovery.getObservers());
        if (!desktopEvents.isEmpty()) {
            LOGGER.warnf("The application observes %s, but not DesktopStartupEvent : the events of java.awt.Desktop are only"
                    + " fired to an application with a user interface (observing DesktopStartupEvent)", desktopEvents);
        }
    }

    /**
     * The {@code java.awt.Desktop} events that the application observes (among those that the extension fires : the
     * {@code java.awt.desktop} events of the handlers, and {@code QuitRequest}). Warns for the other
     * {@code java.awt.desktop} events, and for the asynchronous observers.
     *
     * @return the class names of the events
     */
    static List<String> desktopEvents(Collection<ObserverInfo> observers) {
        Set<String> fired = DesktopHandlers.eventTypes();
        Set<String> events = new TreeSet<>();
        for (ObserverInfo observer : observers) {
            String type = observer.getObservedType().name().toString();
            if (fired.contains(type)) {
                if (observer.isAsync()) {
                    LOGGER.warnf("%s observes %s asynchronously : it is fired synchronously on the event dispatch thread,"
                            + " use @Observes", describe(observer), type);
                } else {
                    events.add(type);
                }
            } else if (type.equals(QUIT_EVENT)) {
                LOGGER.warnf("%s observes %s, which is not fired : observe io.quarkiverse.desktop.awt.QuitRequest",
                        describe(observer), type);
            } else if (type.startsWith(DESKTOP_EVENTS_PACKAGE)) {
                LOGGER.warnf("%s observes %s, which is not fired : register a listener with"
                        + " java.awt.Desktop.addAppEventListener", describe(observer), type);
            }
        }
        return new ArrayList<>(events);
    }

    private static String describe(ObserverInfo observer) {
        MethodInfo method = observer.getObserverMethod();
        return method != null ? describe(method) : observer.getBeanClass().toString();
    }

    /**
     * Starts the user interface once the application started ({@code ApplicationStartBuildItem} : after the
     * {@code StartupEvent} observers), with the handlers of the observed {@code java.awt.Desktop} events.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void startUserInterface(Optional<DesktopUiBuildItem> userInterface, ApplicationStartBuildItem applicationStart,
            DesktopLifecycleRecorder recorder, ShutdownContextBuildItem shutdown, LaunchModeBuildItem launchMode,
            ArcConfig arcConfig, BeanDiscoveryFinishedBuildItem beanDiscovery) {
        if (userInterface.isEmpty()) {
            return;
        }
        recorder.startUserInterface(shutdown, launchMode.getLaunchMode(),
                arcConfig.test().disableApplicationLifecycleObservers(),
                DesktopAwtProcessor.isPresent(DesktopAwtProcessor.QUARKUS_FX_APPLICATION,
                        Thread.currentThread().getContextClassLoader()),
                desktopEvents(beanDiscovery.getObservers()));
    }
}
