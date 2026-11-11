package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;

/**
 * The packages of a native build are checked before the native executable is built : a missing tool fails the build
 * in the step that checks them, which runs before native-image. The GraalVM of the build is a directory of the test,
 * whose native-image fails : the test needs no GraalVM, nor a container runtime.
 */
class NativeBuildCheckTest {

    /**
     * The production build of a native executable, with a format of each operating system, whose tool is missing.
     */
    @RegisterExtension
    static final QuarkusProdModeTest TEST = new QuarkusProdModeTest().withEmptyApplication().setBuildNative(true)
            .overrideConfigKey("quarkus.native.graalvm-home", graalvmHome().toString())
            .overrideConfigKey("quarkus.desktop.packaging.formats", "msi,dmg,deb")
            .overrideConfigKey("quarkus.desktop.packaging.tools.wix", "missing/wix")
            .overrideConfigKey("quarkus.desktop.packaging.tools.hdiutil", "missing/hdiutil")
            .overrideConfigKey("quarkus.desktop.packaging.tools.dpkg-deb", "missing/dpkg-deb")
            .assertBuildException(e -> PackagingTest.assertBuildFailed(e, "checkPackages"));

    /**
     * The GraalVM of the build, in the temporary directory (the build directory of the module) : a native-image that
     * fails, whose name is native-image.cmd on Windows. Quarkus only checks that it exists, until native-image runs.
     */
    private static Path graalvmHome() {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "quarkus-desktop-packaging-graalvm").toAbsolutePath();
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
