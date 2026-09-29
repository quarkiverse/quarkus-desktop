package io.quarkiverse.desktop.swing.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.logging.Level;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.os.OS;

/**
 * A look and feel that cannot be set is reported as a warning, and the default one is kept (the default look and feel of
 * the JDK : Aqua on macOS with a display, Metal elsewhere).
 */
class UnsupportedLookAndFeelTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.desktop.swing.look-and-feel", "com.example.MissingLookAndFeel")
            .setLogRecordPredicate(r -> r.getLevel().equals(Level.WARNING)
                    && DesktopSwingTest.message(r).contains("look and feel"))
            .assertLogRecords(records -> {
                assertEquals(1, records.size(), records.toString());
                String message = DesktopSwingTest.message(records.get(0));
                assertTrue(message.contains("com.example.MissingLookAndFeel"), message);
            });

    @Test
    void defaultLookAndFeelIsKept() {
        assertEquals(
                OS.MAC.isCurrent() && !GraphicsEnvironment.isHeadless() ? "com.apple.laf.AquaLookAndFeel"
                        : "javax.swing.plaf.metal.MetalLookAndFeel",
                UIManager.getLookAndFeel().getClass().getName());
    }
}
