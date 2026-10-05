package io.quarkiverse.desktop.swt.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

@QuarkusMainIntegrationTest
public class SwtItIT extends SwtItTest {

    /**
     * Also compares what the native executable rendered with what the JVM mode tests of the same build rendered : they
     * must be the same.
     */
    @Test
    @Launch({ "swt", DIRECTORY })
    @Override
    public void swt(LaunchResult result) throws Exception {
        super.swt(result);
        List<String> compared = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of(DIRECTORY), "*-native.*")) {
            for (Path nativeFile : files) {
                Path jvmFile = nativeFile.resolveSibling(nativeFile.getFileName().toString().replace("-native.", "-jvm."));
                if (!Files.isRegularFile(jvmFile)) {
                    // The JVM mode tests did not run in this build
                    continue;
                }
                if (nativeFile.toString().endsWith(".png")) {
                    assertSameImage(jvmFile, nativeFile);
                } else {
                    assertEquals(Files.readString(jvmFile), Files.readString(nativeFile),
                            nativeFile + " differs from " + jvmFile);
                }
                compared.add(nativeFile.getFileName().toString());
            }
        }
        System.out.println("Same as in JVM mode : " + compared);
    }

    /**
     * The application stops when its last shell closes (not in the JVM mode tests, where the policy is disabled).
     */
    @Test
    @Launch("exit-policy")
    public void exitPolicy(LaunchResult result) {
        String output = result.getOutput();
        assertEquals(0, result.exitCode(), output);
        assertFalse(output.contains(" FAILED "), output);
        assertTrue(output.contains("RESULT lifecycle-run-returned OK disposed=true"), output);
    }

    /**
     * Compares two PNG images pixel by pixel, decoded with SWT (no Display needed).
     */
    private static void assertSameImage(Path expected, Path actual) throws IOException {
        ImageData jvm = load(expected);
        ImageData image = load(actual);
        assertEquals(jvm.width + "x" + jvm.height, image.width + "x" + image.height, "size of " + actual);
        int different = 0;
        for (int x = 0; x < jvm.width; x++) {
            for (int y = 0; y < jvm.height; y++) {
                if (!jvm.palette.getRGB(jvm.getPixel(x, y)).equals(image.palette.getRGB(image.getPixel(x, y)))) {
                    different++;
                }
            }
        }
        assertTrue(different == 0, different + " pixels of " + actual + " differ from " + expected);
    }

    private static ImageData load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new ImageLoader().load(in)[0];
        }
    }
}
