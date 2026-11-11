package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.awt.deployment.DesktopTargetPlatformBuildItem.Platform;
import io.quarkiverse.desktop.awt.runtime.DesktopAwtConfig;
import io.quarkiverse.desktop.awt.runtime.graal.DesktopAwtFeature;
import io.quarkiverse.desktop.packaging.spi.PackagedApplicationNameBuildItem;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageSystemPropertyBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * The name of the application in the menu bar of macOS : the one of the configuration, else the name of the application
 * in its packages (Desktop Packaging extension), the one of its bundle, else {@code quarkus.application.name}. The
 * build steps of macOS native executables give it to the default value of {@code apple.awt.application.name} and to
 * the {@code Info.plist} : they run for a native build on a macOS host only, the tests call them.
 */
class MacApplicationNameTest {

    private static final ApplicationInfoBuildItem APPLICATION_INFO = new ApplicationInfoBuildItem(
            Optional.of("acme-editor"), Optional.of("1.2.3"));

    private static final String CONFIGURED = "quarkus.desktop.awt.macos.application-name";

    @Test
    void macApplicationName() {
        assertEquals("Acme Editor",
                DesktopAwtProcessor.macApplicationName(Optional.empty(), Optional.of("Acme Editor"), "acme-editor"));
        assertEquals("Custom",
                DesktopAwtProcessor.macApplicationName(Optional.of("Custom"), Optional.of("Acme Editor"), "acme-editor"));
        // without the Desktop Packaging extension, or without its name : as before
        assertEquals("acme-editor", DesktopAwtProcessor.macApplicationName(Optional.empty(), Optional.empty(),
                "acme-editor"));
        assertEquals("Custom", DesktopAwtProcessor.macApplicationName(Optional.of("Custom"), Optional.empty(),
                "acme-editor"));
    }

    /**
     * The default value of {@code apple.awt.application.name} in a macOS native executable.
     */
    @Test
    void nativeExecutableProperty() {
        assertEquals(Optional.of("Acme Editor"), builderProperty(Platform.MAC, Map.of(), Optional.of("Acme Editor")));
        assertEquals(Optional.of("Custom"), builderProperty(Platform.MAC, Map.of(CONFIGURED, "Custom"),
                Optional.of("Acme Editor")));
        assertEquals(Optional.of("acme-editor"), builderProperty(Platform.MAC, Map.of(), Optional.empty()));
        // the executables of the other platforms
        assertEquals(Optional.empty(), builderProperty(Platform.WINDOWS, Map.of(), Optional.of("Acme Editor")));
        assertEquals(Optional.empty(), builderProperty(Platform.LINUX, Map.of(), Optional.of("Acme Editor")));
    }

    /**
     * The name of the {@code Info.plist} of a macOS native executable ({@code quarkus.desktop.awt.macos.info-plist}).
     */
    @Test
    void infoPlist(@TempDir Path directory) throws IOException {
        String plist = infoPlist(directory, Map.of(), Optional.of("Acme Editor"));
        assertTrue(plist.contains("<key>CFBundleName</key>\n    <string>Acme Editor</string>"), plist);
        plist = infoPlist(directory, Map.of(CONFIGURED, "Custom"), Optional.of("Acme Editor"));
        assertTrue(plist.contains("<key>CFBundleName</key>\n    <string>Custom</string>"), plist);
        plist = infoPlist(directory, Map.of(), Optional.empty());
        assertTrue(plist.contains("<key>CFBundleName</key>\n    <string>acme-editor</string>"), plist);
    }

    /**
     * The default value of {@code apple.awt.application.name} that the build step gives the native executable.
     */
    private static Optional<String> builderProperty(Platform platform, Map<String, String> properties,
            Optional<String> packagedName) {
        List<NativeImageSystemPropertyBuildItem> builderProperties = new ArrayList<>();
        new DesktopAwtProcessor().runTimeDefaults(new DesktopTargetPlatformBuildItem(platform), config(properties),
                APPLICATION_INFO, packagedName.map(PackagedApplicationNameBuildItem::new), feature -> {
                }, builderProperties::add);
        return builderProperties.stream().filter(property -> property.getKey()
                .equals(DesktopAwtFeature.MAC_APPLICATION_NAME)).map(NativeImageSystemPropertyBuildItem::getValue)
                .findFirst();
    }

    /**
     * The {@code Info.plist} that the build step writes in the build directory for the native executable.
     */
    private static String infoPlist(Path directory, Map<String, String> properties, Optional<String> packagedName)
            throws IOException {
        Map<String, String> macos = new HashMap<>(properties);
        macos.put("quarkus.desktop.awt.macos.info-plist", "true");
        // the versions of the java launcher, not needed here
        macos.put("quarkus.desktop.awt.macos.jdk-build-version", "false");
        List<GeneratedResourceBuildItem> resources = new ArrayList<>();
        new DesktopAwtProcessor().macExecutable(new DesktopTargetPlatformBuildItem(Platform.MAC), config(macos), null,
                APPLICATION_INFO, packagedName.map(PackagedApplicationNameBuildItem::new),
                new OutputTargetBuildItem(directory, directory, "acme-editor", "acme-editor", false, new Properties(),
                        Optional.empty()),
                resources::add);
        assertEquals(List.of(MacExecutable.NATIVE_IMAGE_PROPERTIES),
                resources.stream().map(GeneratedResourceBuildItem::getName).toList());
        return Files.readString(directory.toAbsolutePath().resolve("quarkus-desktop-awt").resolve("Info.plist"),
                StandardCharsets.UTF_8);
    }

    /**
     * The build time configuration of the extension : the defaults, and the given properties.
     */
    private static DesktopAwtConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder().addDiscoveredConverters().withMapping(DesktopAwtConfig.class)
                .withSources(new PropertiesConfigSource(properties, "test", 100)).build()
                .getConfigMapping(DesktopAwtConfig.class);
    }
}
