package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.RequestScoped;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Build errors for the {@code @RunOnEdt} methods that cannot work : other return types than {@code void} and
 * {@code CompletionStage}, beans of other scopes than {@code @ApplicationScoped}, {@code @Singleton} and
 * {@code @Dependent} (ArC fails the build for private methods).
 */
class RunOnEdtValidationTest {

    @RequestScoped
    @Unremovable
    public static class Presenter {

        @RunOnEdt
        String title() {
            return "title";
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Presenter.class))
            .assertException(e -> {
                String messages = messages(e);
                assertTrue(messages.contains("title() returns java.lang.String"), messages);
                assertTrue(messages.contains("is @RequestScoped"), messages);
            });

    @Test
    void errors() {
        // the build fails
    }

    /**
     * The messages of an exception, of its causes and of their suppressed exceptions.
     */
    static String messages(Throwable e) {
        StringBuilder messages = new StringBuilder();
        collect(e, messages);
        return messages.toString();
    }

    private static void collect(Throwable e, StringBuilder messages) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t).append('\n');
            for (Throwable suppressed : t.getSuppressed()) {
                collect(suppressed, messages);
            }
            if (t.getCause() == t) {
                break;
            }
        }
    }
}
