package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The name of the application in the menu bar of macOS : the one of the configuration, else the name of the application
 * in its packages (Desktop Packaging extension), the one of its bundle, else {@code quarkus.application.name}.
 */
class MacApplicationNameTest {

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
}
