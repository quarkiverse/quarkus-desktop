package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JPanel;

import jakarta.enterprise.context.ApplicationScoped;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The client proxy of a normal scoped bean extending a Swing component cannot be loaded : it overrides the final methods
 * of {@code javax.swing.JComponent}, which ArC cannot make non final (JDK classes are not transformed).
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
                Throwable cause = e;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                assertInstanceOf(IncompatibleClassChangeError.class, cause, cause.toString());
                assertTrue(cause.getMessage().contains("overrides final method javax.swing.JComponent"),
                        cause.getMessage());
            });

    @Test
    void proxyFails() {
        // the application fails to start
    }
}
