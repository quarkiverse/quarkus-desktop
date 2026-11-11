package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The name of the application set in {@code application.properties} ({@code quarkus.desktop.swt.application-name}),
 * which the build and the run time both read : the extension gives it to SWT ({@code Display.setAppName}) and to the
 * shortcuts and desktop entry of the packages ({@code ApplicationIdBuildItem}), rather than the name of the packages.
 * Needs a display. Not on macOS, where the {@code Display} needs the first thread of the process.
 */
@DisabledOnOs(OS.MAC)
class ConfiguredApplicationNameTest {

    @Singleton
    public static class MainWindow {

        static final CountDownLatch OPENED = new CountDownLatch(1);
        static volatile String appName;

        void open(@Observes SwtStartupEvent event) {
            appName = Display.getAppName();
            OPENED.countDown();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(MainWindow.class, ApplicationNameSteps.class))
            .overrideConfigKey("quarkus.desktop.swt.application-name", "Custom")
            .overrideRuntimeConfigKey("quarkus.desktop.swt.startup-event.enabled", "true")
            .addBuildChainCustomizer(ApplicationNameSteps.packagedName("Acme Editor"))
            .addBuildChainCustomizer(ApplicationNameSteps.recordApplicationId());

    @Test
    void configuredName() throws InterruptedException {
        assertTrue(MainWindow.OPENED.await(10, TimeUnit.SECONDS), "SwtStartupEvent not fired");
        assertEquals("Custom", MainWindow.appName);
        assertEquals("Custom", ApplicationNameSteps.applicationId());
    }
}
