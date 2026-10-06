package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkus.arc.Unremovable;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A bean with {@code @RunOnUiThread} methods of another scope than {@code @ApplicationScoped}, {@code @Singleton} and
 * {@code @Dependent} fails the build : its instance belongs to the context of the caller.
 */
class RunOnUiThreadScopeTest {

    @RequestScoped
    @Unremovable
    public static class RequestPresenter {

        @RunOnUiThread
        void show() {
        }
    }

    @Singleton
    @Unremovable
    public static class SingletonPresenter {

        @RunOnUiThread
        void show() {
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(RequestPresenter.class, SingletonPresenter.class))
            .assertException(e -> {
                String messages = RunOnUiThreadReturnTypeTest.messages(e);
                assertInstanceOf(DeploymentException.class, e, messages);
                assertTrue(messages.contains(RequestPresenter.class.getName() + " has @RunOnUiThread methods and is"
                        + " @RequestScoped"), messages);
                assertFalse(messages.contains(SingletonPresenter.class.getName()), messages);
            });

    @Test
    void error() {
        // the build fails
    }
}
