package io.quarkiverse.desktop.awt.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class AwtItTest {

    @Test
    @Launch({ "awt", "target" })
    public void awt(LaunchResult result) {
        String output = result.getOutput();
        assertEquals(0, result.exitCode(), output);
        assertTrue(output.contains("SUMMARY ok="), output);
        assertFalse(output.contains(" FAILED "), output);
        for (String check : new String[] { "environment", "static-initializer", "java2d", "fonts", "imageio",
                "imageio-plugins", "text-attributes", "java-beans", "print-stream", "print-services", "flavor-map",
                "sound" }) {
            assertTrue(output.contains("RESULT " + check + " OK"), check + "\n" + output);
        }
        // Same as the JVM running the tests : the text of the native components is encoded with it
        assertEquals(System.getProperty("sun.io.unicode.encoding"), value(output, "unicodeEncoding"), output);
        if (System.getProperty("native.image.path") != null) {
            // the native executable runs the application on the thread main (a new one on macOS, where the first
            // thread of the process runs the Cocoa event loop)
            assertEquals("main", value(output, "thread"), output);
            assertEquals(OS.MAC.isCurrentOs() ? "true" : "null", value(output, "mainThreadParked"), output);
        }
        if (!GraphicsEnvironment.isHeadless()) {
            // The application has a display when the tests have one
            assertTrue(output.contains("headless=false"), output);
            assertTrue(output.contains("RESULT frame OK"), output);
            assertTrue(output.contains("RESULT desktop OK"), output);
            assertTrue(output.contains("RESULT startup-event OK"), output);
            // Same scale as the JVM running the tests (DPI aware on Windows, like the java launcher)
            double scale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
            assertEquals(scale, Double.parseDouble(value(output, "scale")), 0.001, output);
            if (OS.MAC.isCurrentOs()) {
                // Same Java2D pipeline as the JVM running the tests : Metal (OpenGL when the Metal shaders are missing)
                assertEquals(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                        .getDefaultConfiguration().getClass().getName(), value(output, "configuration"), output);
            }
            // Same desktop integration as the JVM running the tests
            assertEquals(String.valueOf(Desktop.isDesktopSupported()), value(output, "desktop"), output);
            assertEquals(String.valueOf(Taskbar.isTaskbarSupported()), value(output, "taskbar"), output);
            assertEquals(String.valueOf(SystemTray.isSupported()), value(output, "tray"), output);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Launch("access-bridge")
    public void accessBridge(LaunchResult result) {
        String output = result.getOutput();
        assertEquals(0, result.exitCode(), output);
        assertTrue(output.contains("RESULT access-bridge OK") || output.contains("RESULT access-bridge SKIPPED"),
                output);
    }

    static String value(String output, String key) {
        Matcher matcher = Pattern.compile("\\b" + key + "=(\\S+)").matcher(output);
        assertTrue(matcher.find(), key + " not found in\n" + output);
        return matcher.group(1);
    }
}
