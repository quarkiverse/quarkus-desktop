package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * ArC intercepts the static methods with interceptor bindings of any class of the application : the same return types
 * as the other {@code @RunOnEdt} methods, and a build error for a private static method, which ArC does not intercept.
 */
class RunOnEdtStaticMethodTest {

    public static class Status {

        @RunOnEdt
        static int count() {
            return 0;
        }

        @RunOnEdt
        private static void hidden() {
        }

        @RunOnEdt
        static void show(String text) {
        }

        @RunOnEdt
        static CompletionStage<String> title() {
            return null;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Status.class))
            .assertException(e -> {
                String messages = RunOnEdtValidationTest.messages(e);
                assertTrue(messages.contains("count() returns int"), messages);
                assertTrue(messages.contains("hidden() is private and static"), messages);
                assertFalse(messages.contains("show()"), messages);
                assertFalse(messages.contains("title()"), messages);
            });

    @Test
    void errors() {
        // the build fails
    }
}
