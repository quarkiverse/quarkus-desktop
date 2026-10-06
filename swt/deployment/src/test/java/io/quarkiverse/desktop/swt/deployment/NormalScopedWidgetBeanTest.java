package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Singleton;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A normal scoped bean whose type is an SWT widget or device fails the build (its client proxy would create another
 * widget), a {@code @Singleton} one does not. Producers : SWT forbids subclassing its widgets.
 */
class NormalScopedWidgetBeanTest {

    @Singleton
    public static class Widgets {

        @Produces
        @ApplicationScoped
        @Unremovable
        Shell mainShell() {
            return new Shell();
        }

        @Produces
        @Singleton
        @Unremovable
        Display display() {
            return Display.getDefault();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Widgets.class))
            .assertException(e -> {
                String messages = RunOnUiThreadReturnTypeTest.messages(e);
                assertInstanceOf(DeploymentException.class, e, messages);
                assertTrue(messages.contains("The bean " + Shell.class.getName() + " (produced by"), messages);
                assertTrue(messages.contains("is @ApplicationScoped and is an SWT widget or device"), messages);
                assertFalse(messages.contains(Display.class.getName() + " (produced by"), messages);
            });

    @Test
    void error() {
        // the build fails
    }
}
