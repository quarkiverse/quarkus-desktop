package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.test.QuarkusExtensionTest;
import picocli.CommandLine.Command;

/**
 * An SWT user interface without {@code SwtStartupEvent} observer, whose main comes from another extension (Picocli) :
 * its command, which is not a {@code @QuarkusMain}, calls {@code SwtLifecycle.run()}, which names the process, so the
 * packages get the name as application id. The main does not run in this test : it needs no display.
 */
class ApplicationIdWithPicocliTest {

    @TopCommand
    @Command(name = "app")
    public static class AppCommand implements Runnable {

        @Inject
        SwtLifecycle lifecycle;

        @Override
        public void run() {
            lifecycle.run();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(AppCommand.class, ApplicationNameSteps.class))
            .overrideConfigKey("quarkus.desktop.swt.application-name", "Acme Editor")
            .addBuildChainCustomizer(ApplicationNameSteps.recordApplicationId());

    @Test
    void applicationId() {
        assertEquals("Acme Editor", ApplicationNameSteps.applicationId());
    }
}
