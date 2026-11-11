package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.quarkus.test.common.PathTestHelper;

/**
 * The packages of a native build are checked before the native executable is built : a missing tool fails the build
 * in the step that checks them, which runs before native-image. The GraalVM of the build is a directory of the test,
 * whose native-image fails : the test needs no GraalVM, nor a container runtime.
 */
class NativeBuildCheckTest {

    /**
     * The production build of a native executable, with a format of each operating system, whose tool is missing. The
     * configuration of the test has the ordinal 500 : above the system properties (400), into which Surefire copies
     * every {@code -D} of the Maven command line (the formats of the packaging jobs, which build this module with
     * {@code -am}), and above the environment (300).
     */
    @RegisterExtension
    static final QuarkusProdModeTest TEST = new QuarkusProdModeTest().withEmptyApplication().setBuildNative(true)
            .overrideConfigKey("config_ordinal", "500")
            .overrideConfigKey("quarkus.native.graalvm-home", graalvmHome().toString())
            .overrideConfigKey("quarkus.desktop.packaging.formats", "msi,dmg,deb")
            .overrideConfigKey("quarkus.desktop.packaging.tools.wix", "missing/wix")
            .overrideConfigKey("quarkus.desktop.packaging.tools.hdiutil", "missing/hdiutil")
            .overrideConfigKey("quarkus.desktop.packaging.tools.dpkg-deb", "missing/dpkg-deb")
            .assertBuildException(e -> PackagingTest.assertBuildFailed(e, "checkPackages"));

    /**
     * The GraalVM of the build, in the build directory of the module : a native-image that fails, whose name is
     * native-image.cmd on Windows. Quarkus only checks that it exists, until native-image runs.
     */
    private static Path graalvmHome() {
        Path home = PathTestHelper.getTestClassesLocation(NativeBuildCheckTest.class).getParent()
                .resolve("quarkus-desktop-packaging-graalvm").toAbsolutePath();
        try {
            Path bin = Files.createDirectories(home.resolve("bin"));
            for (String name : List.of("native-image", "native-image.cmd")) {
                if (!Files.exists(bin.resolve(name))) {
                    Files.writeString(bin.resolve(name), "exit 1\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return home;
    }

    /**
     * The build failed before the test.
     */
    @Test
    void theBuildFailsBeforeNativeImage() {
        // the build fails
    }
}
