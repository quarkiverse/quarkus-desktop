package io.quarkiverse.desktop.packaging.deployment;

import java.util.List;

import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.Version;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The tests of an application whose native build is enabled package nothing, and check no package : the application
 * starts without the tools of its formats (the ones of {@link NativeBuildCheckTest}, whose build fails). The test
 * instance is a bean of ArC, which the extension does not depend on : the application of the test does.
 */
@Singleton
class NativeBuildCheckInTestsTest {

    /**
     * The application, with a format of each operating system, whose tool is missing.
     */
    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .setForcedDependencies(List.of(Dependency.of("io.quarkus", "quarkus-arc", Version.getVersion())))
            .overrideConfigKey("quarkus.arc.unremovable-types", NativeBuildCheckInTestsTest.class.getName())
            .overrideConfigKey("quarkus.native.enabled", "true")
            .overrideConfigKey("quarkus.desktop.packaging.formats", "msi,dmg,deb")
            .overrideConfigKey("quarkus.desktop.packaging.tools.wix", "missing/wix")
            .overrideConfigKey("quarkus.desktop.packaging.tools.hdiutil", "missing/hdiutil")
            .overrideConfigKey("quarkus.desktop.packaging.tools.dpkg-deb", "missing/dpkg-deb");

    /**
     * The application started.
     */
    @Test
    void theApplicationStarts() {
        // the build checks no package
    }
}
