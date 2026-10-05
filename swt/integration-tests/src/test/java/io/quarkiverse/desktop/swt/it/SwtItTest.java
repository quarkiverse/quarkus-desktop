package io.quarkiverse.desktop.swt.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class SwtItTest {

    static final String DIRECTORY = "target/swt-it";

    @Test
    @Launch({ "swt", DIRECTORY })
    public void swt(LaunchResult result) throws Exception {
        String output = result.getOutput();
        assertEquals(0, result.exitCode(), output);
        assertTrue(output.contains("SUMMARY ok="), output);
        assertFalse(output.contains(" FAILED "), output);
        // The checks that pass on every platform
        for (String check : new String[] { "environment", "static-initializer", "gallery", "graphics", "widgets",
                "containers", "custom-widgets", "table-tree", "layouts", "graphics-advanced", "text-layout", "fonts",
                "images", "colors-cursors", "data-transfer", "async", "errors" }) {
            assertTrue(output.contains("RESULT " + check + " OK"), check + "\n" + output);
        }
        // The checks of what depends on the machine (printers, programs, assistive technologies...) : reported
        for (String check : new String[] { "desktop-services", "printing", "accessibility", "browser" }) {
            assertTrue(output.contains("RESULT " + check + " "), check + "\n" + output);
        }
        // quarkus.desktop.swt.application-name (application.properties)
        assertTrue(output.contains(" appName=Quarkus_Desktop_SWT_IT"), output);
        assertTrue(output.contains("RESULT lifecycle-run-returned OK disposed=true"), output);
        assertTrue(output.contains("RESULT shutdown-order OK"), output);
    }

    @Test
    @Launch("lifecycle")
    public void lifecycle(LaunchResult result) {
        String output = result.getOutput();
        assertEquals(0, result.exitCode(), output);
        assertFalse(output.contains(" FAILED "), output);
        for (String check : new String[] { "startup-event", "queued-before-start", "executor-order", "executor-later",
                "run-on-ui-thread", "ui-thread-call", "widget-beans", "quit-request", "shutdown-order" }) {
            assertTrue(output.contains("RESULT " + check + " OK"), check + "\n" + output);
        }
        assertTrue(output.contains("RESULT lifecycle-run-returned OK disposed=true"), output);
    }
}
