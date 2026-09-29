package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.Panel;

import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A class interceptor binding on a component bean fails the build, a method binding does not.
 */
class ComponentInterceptorBindingTest {

    @Singleton
    @Unremovable
    @RunOnEdt
    public static class Board extends Panel {
    }

    @Singleton
    @Unremovable
    public static class Chart extends Canvas {

        @RunOnEdt
        public void refresh() {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Board.class, Chart.class))
            .assertException(e -> {
                String messages = RunOnEdtValidationTest.messages(e);
                assertTrue(messages.contains(Board.class.getName() + " has a class interceptor binding"), messages);
                assertFalse(messages.contains(Chart.class.getName()), messages);
            });

    @Test
    void error() {
        // the build fails
    }
}
