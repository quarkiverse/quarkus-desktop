package io.quarkiverse.desktop.awt.deployment;

import java.util.Optional;

import org.jboss.jandex.DotName;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkiverse.desktop.awt.runtime.DesktopLifecycleRecorder;
import io.quarkiverse.desktop.awt.runtime.DesktopUi;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ArcConfig;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
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

    static final DotName DESKTOP_STARTUP_EVENT = DotName.createSimple(DesktopStartupEvent.class);

    @BuildStep
    AdditionalBeanBuildItem beans() {
        // looked up by the recorder
        return AdditionalBeanBuildItem.builder().addBeanClasses(DesktopUi.class).setUnremovable().build();
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
    }

    /**
     * Starts the user interface once the application started ({@code ApplicationStartBuildItem} : after the
     * {@code StartupEvent} observers).
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void startUserInterface(Optional<DesktopUiBuildItem> userInterface, ApplicationStartBuildItem applicationStart,
            DesktopLifecycleRecorder recorder, ShutdownContextBuildItem shutdown, LaunchModeBuildItem launchMode,
            ArcConfig arcConfig) {
        if (userInterface.isEmpty()) {
            return;
        }
        recorder.startUserInterface(shutdown, launchMode.getLaunchMode(),
                arcConfig.test().disableApplicationLifecycleObservers(),
                DesktopAwtProcessor.isPresent(DesktopAwtProcessor.QUARKUS_FX_APPLICATION,
                        Thread.currentThread().getContextClassLoader()));
    }
}
