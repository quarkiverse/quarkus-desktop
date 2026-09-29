package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import javax.swing.JLabel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A warning for a bean with {@code @RunOnEdt} methods that injects a component bean directly (it is created on the
 * thread that first uses the bean), not through {@code Instance}.
 */
class RunOnEdtComponentInjectionTest {

    @Singleton
    public static class StatusBar extends JLabel {
    }

    @ApplicationScoped
    public static class DirectPresenter {

        @Inject
        StatusBar bar;

        @RunOnEdt
        void show(String text) {
            bar.setText(text);
        }
    }

    @ApplicationScoped
    public static class LazyPresenter {

        @Inject
        Instance<StatusBar> bar;

        @RunOnEdt
        void show(String text) {
            bar.get().setText(text);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(StatusBar.class, DirectPresenter.class, LazyPresenter.class))
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopAwtTest.message(r).contains("@RunOnEdt"))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), records.toString());
                String message = DesktopAwtTest.message(records.get(0));
                assertTrue(message.contains(DirectPresenter.class.getName()), message);
            });

    @Inject
    DirectPresenter direct;

    @Inject
    LazyPresenter lazy;

    @Test
    void warning() {
        // the log records are checked once the application stopped
    }
}
