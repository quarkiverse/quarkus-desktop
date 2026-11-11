package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * An SWT user interface without {@code SwtStartupEvent} observer : its {@code @QuarkusMain} calls
 * {@code SwtLifecycle.run()}, which names the process, so the packages get the name as application id. The main does
 * not run in this test : it needs no display.
 */
class ApplicationIdWithQuarkusMainTest {

    @QuarkusMain
    public static class Main implements QuarkusApplication {

        @Inject
        SwtLifecycle lifecycle;

        @Override
        public int run(String... args) {
            // the shells are opened by tasks for the user interface thread
            lifecycle.run();
            return 0;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Main.class, ApplicationNameSteps.class))
            .overrideConfigKey("quarkus.desktop.swt.application-name", "Acme Editor")
            .addBuildChainCustomizer(ApplicationNameSteps.recordApplicationId());

    @Test
    void applicationId() {
        assertEquals("Acme Editor", ApplicationNameSteps.applicationId());
    }
}
