package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.DeploymentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A {@code @RunOnUiThread} method returning neither {@code void} nor a {@code CompletionStage} fails the build : it
 * would return before running on the user interface thread.
 */
class RunOnUiThreadReturnTypeTest {

    @ApplicationScoped
    @Unremovable
    public static class Presenter {

        @RunOnUiThread
        String title() {
            return "title";
        }

        @RunOnUiThread
        void show() {
        }

        @RunOnUiThread
        CompletionStage<String> load() {
            return null;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Presenter.class))
            .assertException(e -> {
                String messages = messages(e);
                assertInstanceOf(DeploymentException.class, e, messages);
                assertTrue(e.getMessage().contains("@RunOnUiThread method " + Presenter.class.getName()
                        + ".title() returns java.lang.String"), messages);
                // the valid methods are not reported
                assertTrue(!messages.contains("show()") && !messages.contains("load()"), messages);
            });

    @Test
    void error() {
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
