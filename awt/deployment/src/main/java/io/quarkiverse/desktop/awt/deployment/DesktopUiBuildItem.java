package io.quarkiverse.desktop.awt.deployment;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Produced when the application has a user interface : it observes {@code io.quarkiverse.desktop.awt.DesktopStartupEvent}.
 * The extensions then start the user interface when the application starts ; without it, they never start the AWT
 * toolkit.
 */
public final class DesktopUiBuildItem extends SimpleBuildItem {
}
