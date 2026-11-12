package io.quarkiverse.desktop.packaging.deployment;

import java.nio.file.Path;
import java.util.Map;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * The configuration of the extension, for the tests : the defaults, and the given properties without their
 * {@code quarkus.desktop.packaging.} prefix.
 */
public final class TestConfig {

    private TestConfig() {
    }

    public static DesktopPackagingConfig of(Map<String, String> properties) {
        Map<String, String> prefixed = new java.util.HashMap<>();
        properties.forEach((key, value) -> prefixed.put("quarkus.desktop.packaging." + key, value));
        return new SmallRyeConfigBuilder().withMapping(DesktopPackagingConfig.class)
                .withSources(new PropertiesConfigSource(prefixed, "test", 100)).build()
                .getConfigMapping(DesktopPackagingConfig.class);
    }

    public static DesktopPackagingConfig defaults() {
        return of(Map.of());
    }

    /**
     * An application named {@code Acme Editor}, of the {@code org.acme} project, in version {@code 1.2.3-SNAPSHOT}.
     */
    public static AppInfo app(DesktopPackagingConfig config) {
        return AppInfo.of(config, "Acme Editor", "1.2.3-SNAPSHOT", "org.acme", Path.of("."));
    }

    public static AppInfo app() {
        return app(of(Map.of("vendor", "Acme & Sons", "description", "Edits <documents>",
                "homepage", "https://acme.org/editor", "copyright", "Copyright (c) Acme")));
    }
}
