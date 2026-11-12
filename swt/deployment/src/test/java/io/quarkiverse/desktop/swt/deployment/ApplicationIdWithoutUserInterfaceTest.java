package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * An application without an SWT user interface (no {@code SwtStartupEvent} observer, no class using
 * {@code SwtLifecycle}), here a {@code @QuarkusMain} that creates its {@code Display} itself : the extension does not
 * name its process, so the packages get no application id, even with {@code quarkus.desktop.swt.application-name}. The
 * main does not run in this test : it needs no display.
 */
class ApplicationIdWithoutUserInterfaceTest {

    @QuarkusMain
    public static class Main implements QuarkusApplication {

        @Override
        public int run(String... args) {
            Display display = new Display();
            Shell shell = new Shell(display);
            shell.open();
            while (!shell.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
            display.dispose();
            return 0;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Main.class, ApplicationNameSteps.class))
            .overrideConfigKey("quarkus.desktop.swt.application-name", "Acme Editor")
            .addBuildChainCustomizer(ApplicationNameSteps.recordApplicationId());

    @Test
    void noApplicationId() {
        assertEquals(ApplicationNameSteps.NONE, ApplicationNameSteps.applicationId());
    }
}
