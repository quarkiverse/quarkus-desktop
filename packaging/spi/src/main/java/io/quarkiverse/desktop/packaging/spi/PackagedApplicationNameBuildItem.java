package io.quarkiverse.desktop.packaging.spi;

import java.util.Objects;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The name of the application in its packages, when the configuration of the Desktop Packaging extension gives one
 * ({@code quarkus.desktop.packaging.name}) : the extensions of the user interface toolkits name the application after
 * it by default, rather than after {@code quarkus.application.name}, so that its menus have the name of its bundle and
 * of its shortcuts (the application menu of macOS). The Desktop Packaging extension produces it when it is enabled and
 * the name is set.
 */
public final class PackagedApplicationNameBuildItem extends SimpleBuildItem {

    private final String name;

    /**
     * @param name the name of the application in its packages
     */
    public PackagedApplicationNameBuildItem(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * The name of the application in its packages.
     */
    public String getName() {
        return name;
    }
}
