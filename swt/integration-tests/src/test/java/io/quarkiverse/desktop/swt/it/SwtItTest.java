package io.quarkiverse.desktop.swt.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/**
 * The application is launched by the tests ({@code QuarkusMainLauncher}) rather than by {@code @Launch}, which fails
 * before the test with the whole output as message when the exit code differs : the failed checks come first in the
 * messages of these tests, the reports of the continuous integration only keep their beginning.
 */
@QuarkusMainTest
public class SwtItTest {

    static final String DIRECTORY = "target/swt-it";

    @Test
    public void swt(QuarkusMainLauncher launcher) throws Exception {
        LaunchResult result = launcher.launch("swt", DIRECTORY);
        String output = assertSucceeded(result);
        assertTrue(output.contains("SUMMARY ok="), output);
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
    public void lifecycle(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("lifecycle");
        String output = assertSucceeded(result);
        for (String check : new String[] { "startup-event", "queued-before-start", "executor-order", "executor-later",
                "run-on-ui-thread", "ui-thread-call", "widget-beans", "quit-request", "shutdown-order" }) {
            assertTrue(output.contains("RESULT " + check + " OK"), check + "\n" + output);
        }
        assertTrue(output.contains("RESULT lifecycle-run-returned OK disposed=true"), output);
    }

    /**
     * Checks that the application exited with 0 and that no check failed.
     *
     * @return the output of the application
     */
    static String assertSucceeded(LaunchResult result) {
        String output = result.getOutput();
        String errors = result.getErrorOutput();
        if (errors != null && !errors.isBlank()) {
            output = output + "\n" + errors;
        }
        String failed = output.lines().filter(line -> line.contains(" FAILED ")).collect(Collectors.joining("\n"));
        String message = (failed.isEmpty() ? "" : failed + "\n\n") + output;
        assertEquals(0, result.exitCode(), message);
        assertTrue(failed.isEmpty(), message);
        return output;
    }
}
