package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;

/**
 * The packages of a JVM build are checked before the application image is built : a missing tool fails the build
 * before {@code jpackage} runs, and nothing is written in the output directory.
 */
class JvmBuildCheckTest {

    /**
     * The output directory of the packages, which the build does not create.
     */
    static final Path OUTPUT_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("jvm-build-check-" + UUID.randomUUID()).toAbsolutePath();

    /**
     * The production build of a jar, with a format of each operating system, whose tool is missing. The output
     * directory is checked as the build fails : the test instance may be of another load of this class, with another
     * output directory.
     */
    @RegisterExtension
    static final QuarkusProdModeTest TEST = new QuarkusProdModeTest().withEmptyApplication()
            .overrideConfigKey("quarkus.desktop.packaging.output-directory", OUTPUT_DIRECTORY.toString())
            .overrideConfigKey("quarkus.desktop.packaging.formats", "msi,dmg,deb")
            .overrideConfigKey("quarkus.desktop.packaging.tools.wix", "missing/wix")
            .overrideConfigKey("quarkus.desktop.packaging.tools.hdiutil", "missing/hdiutil")
            .overrideConfigKey("quarkus.desktop.packaging.tools.dpkg-deb", "missing/dpkg-deb")
            .assertBuildException(e -> {
                PackagingTest.assertBuildFailed(e, "packageJar");
                assertFalse(Files.exists(OUTPUT_DIRECTORY), OUTPUT_DIRECTORY + " exists : the application image was built");
            });

    /**
     * The build failed before the test.
     */
    @Test
    void theBuildFailsBeforeTheApplicationImage() {
        // the build fails
    }
}
