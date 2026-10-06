package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

/**
 * The SWT jar of another architecture in JVM mode : the user interface checks the manifest of the SWT jar before it
 * creates the {@code Display}, logs the error with its hint, and {@link SwtUi#run()} returns. Without the check, SWT
 * would exit the JVM ({@code System.exit(1)}) while the {@code Display} is created, and with it the JVM running this
 * test class (one JVM per test class, see the {@code pom.xml}). The {@code Display} is never created : needs no display.
 */
class SwtUiOtherArchitectureTest {

    @Test
    void theUserInterfaceDoesNotStart() {
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String otherArch = SwtUi.swtArch(osArch).equals("x86_64") ? "aarch64" : "x86_64";
        // the SWT jar of the tests is the one of the build host : SWT would exit on the other architecture
        assertThrows(UnsatisfiedLinkError.class, () -> SwtUi.checkSwtJar(osName, otherArch));
        AtomicBoolean fired = new AtomicBoolean();
        SwtUi ui = new SwtUi();
        ui.startupEvent = new SwtUiStopTest.Observer<>(event -> fired.set(true));
        // a @QuarkusTest : the failure is only logged
        ui.configure(LaunchMode.TEST, false, false, Optional.empty(), Optional.empty());

        // as on a JVM of the other architecture, which SWT reads when it loads its native libraries
        System.setProperty("os.arch", otherArch);
        try {
            ui.run();
        } finally {
            System.setProperty("os.arch", osArch);
        }

        assertFalse(fired.get(), "SwtStartupEvent fired");
        // stopped : the user interface does not start again
        assertFalse(ui.isReady());
    }
}
