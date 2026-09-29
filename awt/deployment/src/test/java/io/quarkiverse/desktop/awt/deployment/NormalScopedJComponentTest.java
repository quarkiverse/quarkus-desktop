package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JPanel;

import jakarta.enterprise.context.ApplicationScoped;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A normal scoped bean extending a Swing component fails the build : otherwise the application fails to start, its
 * client proxy overriding the final methods of {@code javax.swing.JComponent} ({@code IncompatibleClassChangeError}).
 */
class NormalScopedJComponentTest {

    @ApplicationScoped
    @Unremovable
    public static class StatusPanel extends JPanel {
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(StatusPanel.class))
            .assertException(e -> {
                String messages = RunOnEdtValidationTest.messages(e);
                assertTrue(messages.contains(StatusPanel.class.getName() + " is @ApplicationScoped"), messages);
            });

    @Test
    void error() {
        // the build fails
    }
}
