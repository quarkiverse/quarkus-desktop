package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The configuration of the extension, as the build reads it.
 */
class DesktopPackagingConfigTest {

    /**
     * The items of a list lose the white space around them, as the formats do : a list written with a space after
     * each comma gives the tools the arguments and the names that they know ({@code --socket=x11}, not
     * {@code " --socket=x11"}). The white space inside an item stays.
     */
    @Test
    void theItemsOfAListAreTrimmed() {
        DesktopPackagingConfig config = TestConfig.of(Map.ofEntries(Map.entry("formats", "deb, rpm"),
                Map.entry("windows.msix.capabilities", "internetClient, runFullTrust"),
                Map.entry("windows.sign.arguments", "/sm, /d, Acme Editor "),
                Map.entry("linux.categories", "Utility, Development"),
                Map.entry("linux.deb.depends", "libgtk-3-0, libnotify4"),
                Map.entry("linux.rpm.requires", "gtk3, libnotify"),
                Map.entry("linux.flatpak.finish-args", "--share=ipc, --socket=x11"),
                Map.entry("linux.snap.extensions", "gnome, kde-neon-6"),
                Map.entry("linux.snap.plugs", "home, network"),
                Map.entry("linux.snap.stage-packages", "libxtst6, libxi6"),
                Map.entry("linux.snap.build-args", "--verbosity=debug, --destructive-mode"),
                Map.entry("jvm.modules", "java.desktop, java.net.http"),
                Map.entry("jvm.jlink-options", "--strip-debug, --no-man-pages"),
                Map.entry("jvm.options", "-Xmx512m, -Dapp.title=Acme Editor")));
        assertEquals(List.of(PackageFormat.DEB, PackageFormat.RPM), config.formats().orElseThrow());
        assertEquals(List.of("internetClient", "runFullTrust"), config.windows().msix().capabilities());
        assertEquals(List.of("/sm", "/d", "Acme Editor"), config.windows().sign().arguments().orElseThrow());
        assertEquals(List.of("Utility", "Development"), config.linux().categories());
        assertEquals(List.of("libgtk-3-0", "libnotify4"), config.linux().deb().depends().orElseThrow());
        assertEquals(List.of("gtk3", "libnotify"), config.linux().rpm().requires().orElseThrow());
        assertEquals(List.of("--share=ipc", "--socket=x11"), config.linux().flatpak().finishArgs());
        assertEquals(List.of("gnome", "kde-neon-6"), config.linux().snap().extensions().orElseThrow());
        assertEquals(List.of("home", "network"), config.linux().snap().plugs());
        assertEquals(List.of("libxtst6", "libxi6"), config.linux().snap().stagePackages().orElseThrow());
        assertEquals(List.of("--verbosity=debug", "--destructive-mode"),
                config.linux().snap().buildArgs().orElseThrow());
        assertEquals(List.of("java.desktop", "java.net.http"), config.jvm().modules().orElseThrow());
        assertEquals(List.of("--strip-debug", "--no-man-pages"), config.jvm().jlinkOptions().orElseThrow());
        assertEquals(List.of("-Xmx512m", "-Dapp.title=Acme Editor"), config.jvm().options().orElseThrow());
    }

    /**
     * An empty value is a list without items, and an empty item is no item : the snap has no extension.
     */
    @Test
    void theEmptyItemsOfAList() {
        assertEquals(Optional.empty(), TestConfig.of(Map.of("linux.snap.extensions", "")).linux().snap().extensions());
        assertEquals(List.of("home", "network"),
                TestConfig.of(Map.of("linux.snap.plugs", "home,,network")).linux().snap().plugs());
        assertEquals(List.of("home", "network"),
                TestConfig.of(Map.of("linux.snap.plugs", "home, ,network")).linux().snap().plugs());
    }
}
