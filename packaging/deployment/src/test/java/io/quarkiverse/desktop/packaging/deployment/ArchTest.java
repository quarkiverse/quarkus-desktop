package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchTest {

    @Test
    void theArchitecturesOfTheJvm() {
        assertEquals(Arch.X64, Arch.of("amd64"));
        assertEquals(Arch.X64, Arch.of("AMD64"));
        assertEquals(Arch.X64, Arch.of("x86_64"));
        assertEquals(Arch.ARM64, Arch.of("aarch64"));
        assertEquals(Arch.ARM64, Arch.of("arm64"));
    }

    /**
     * The packages are not labelled x64 for another architecture, which jpackage runs on in JVM mode.
     */
    @Test
    void theOtherArchitecturesAreRejected() {
        for (String osArch : List.of("arm", "riscv64", "ppc64le", "s390x", "x86", "")) {
            PackagingException e = assertThrows(PackagingException.class, () -> Arch.of(osArch), osArch);
            assertTrue(e.getMessage().contains("not for " + osArch + " :"), e.getMessage());
        }
    }

    /**
     * The application image is built on any architecture : only the packagers ask for it.
     */
    @Test
    void theArchitectureIsTheOneOfThePackagers(@TempDir Path project) throws IOException {
        String osArch = System.getProperty("os.arch");
        System.setProperty("os.arch", "riscv64");
        try {
            PackagingContext context = Packaging.context(TestConfig.defaults(), "Acme Editor", "1.0", "org.acme", project,
                    project, TargetOs.LINUX);
            assertThrows(PackagingException.class, context::arch);
        } finally {
            System.setProperty("os.arch", osArch);
        }
    }
}
