package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.Label;
import java.awt.Panel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A normal scoped bean extending {@code java.awt.Component} (a class bean or a producer) fails the build, a
 * {@code @Singleton} one does not.
 */
class NormalScopedComponentTest {

    @ApplicationScoped
    @Unremovable
    public static class NormalScopedPanel extends Panel {
    }

    @Singleton
    @Unremovable
    public static class SingletonCanvas extends Canvas {
    }

    @Singleton
    public static class Labels {

        @Produces
        @ApplicationScoped
        @Unremovable
        Label title() {
            return new Label();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(NormalScopedPanel.class, SingletonCanvas.class, Labels.class))
            .assertException(e -> {
                String messages = RunOnEdtValidationTest.messages(e);
                assertTrue(messages.contains(NormalScopedPanel.class.getName() + " is @ApplicationScoped"), messages);
                assertTrue(messages.contains("java.awt.Label (produced by"), messages);
                assertFalse(messages.contains(SingletonCanvas.class.getName()), messages);
            });

    @Test
    void error() {
        // the build fails
    }
}
