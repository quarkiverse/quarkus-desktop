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
 * The default name of the application with the Desktop Packaging extension : the name of its packages
 * ({@code quarkus.desktop.packaging.name}, here produced by a build step of the test), which the extension gives to SWT
 * ({@code Display.setAppName}) and to the shortcuts and desktop entry of the packages ({@code ApplicationIdBuildItem}).
 * Needs a display. Not on macOS, where the {@code Display} needs the first thread of the process.
 */
@DisabledOnOs(OS.MAC)
class PackagedApplicationNameTest {

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
            .overrideRuntimeConfigKey("quarkus.desktop.swt.startup-event.enabled", "true")
            .addBuildChainCustomizer(ApplicationNameSteps.packagedName("Acme Editor"))
            .addBuildChainCustomizer(ApplicationNameSteps.recordApplicationId());

    @Test
    void nameOfThePackages() throws InterruptedException {
        assertTrue(MainWindow.OPENED.await(10, TimeUnit.SECONDS), "SwtStartupEvent not fired");
        assertEquals("Acme Editor", MainWindow.appName);
        assertEquals("Acme Editor", ApplicationNameSteps.applicationId());
    }
}
