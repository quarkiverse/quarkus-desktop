package io.quarkiverse.desktop.swing.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.swing.UIManager;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The observers of {@code StartupEvent} see the look and feel configured with {@code quarkus.desktop.swing.look-and-feel}.
 */
class LookAndFeelStartupEventTest {

    @Singleton
    public static class StartupObserver {

        volatile String lookAndFeel;

        void onStart(@Observes StartupEvent event) {
            lookAndFeel = UIManager.getLookAndFeel().getClass().getName();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(StartupObserver.class))
            .overrideConfigKey("quarkus.desktop.swing.look-and-feel", "nimbus");

    @Inject
    StartupObserver observer;

    @Test
    void lookAndFeelIsSetBeforeStartupEvent() {
        assertEquals("javax.swing.plaf.nimbus.NimbusLookAndFeel", observer.lookAndFeel);
    }
}
