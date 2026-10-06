package io.quarkiverse.desktop.awt.deployment;

/**
 * Capabilities provided by the Quarkus Desktop extensions (declared in the {@code pom.xml} of their runtime modules).
 * <p>
 * Other extensions can test them with {@code io.quarkus.deployment.Capabilities#isPresent(String)} without depending on
 * Quarkus Desktop.
 */
public final class DesktopCapabilities {

    /**
     * Provided by {@code io.quarkiverse.desktop:quarkus-desktop-awt}.
     */
    public static final String AWT = "io.quarkiverse.desktop.awt";

    /**
     * Provided by {@code io.quarkiverse.desktop:quarkus-desktop-swing}, which requires {@link #AWT}.
     */
    public static final String SWING = "io.quarkiverse.desktop.swing";

    /**
     * Provided by {@code io.quarkiverse.desktop:quarkus-desktop-swt}, which does not require {@link #AWT}.
     */
    public static final String SWT = "io.quarkiverse.desktop.swt";

    private DesktopCapabilities() {
        // Constants
    }
}
