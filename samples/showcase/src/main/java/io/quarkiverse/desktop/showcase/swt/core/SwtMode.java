package io.quarkiverse.desktop.showcase.swt.core;

import java.util.Locale;

import org.eclipse.swt.SWT;

import io.quarkus.runtime.ImageMode;

/**
 * Global state of an SWT showcase run, and the operating system : the SWT counterpart of {@code core/ShowcaseMode} and
 * {@code core/Platforms}.
 */
public final class SwtMode {

    /**
     * The operating systems of SWT : its platforms {@code cocoa}, {@code win32} and {@code gtk}.
     */
    public enum Os {
        MAC,
        WINDOWS,
        LINUX
    }

    private static volatile boolean snapshot;

    private SwtMode() {
    }

    /**
     * {@code true} when the pages are rendered to be compared (snapshot mode) : the pages must then be deterministic,
     * and no shell steals the focus of the user's application.
     */
    public static boolean snapshot() {
        return snapshot;
    }

    static void enableSnapshot() {
        snapshot = true;
    }

    /**
     * {@code JVM} or {@code NATIVE}.
     */
    public static String runtime() {
        return ImageMode.current().isNativeImage() ? "NATIVE" : "JVM";
    }

    /**
     * The operating system, from the SWT platform ({@code os.name} for an unknown platform).
     */
    public static Os os() {
        return switch (SWT.getPlatform()) {
            case "cocoa" -> Os.MAC;
            case "win32" -> Os.WINDOWS;
            case "gtk" -> Os.LINUX;
            default -> {
                String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                yield name.startsWith("mac") ? Os.MAC : name.startsWith("windows") ? Os.WINDOWS : Os.LINUX;
            }
        };
    }

    public static boolean isMac() {
        return os() == Os.MAC;
    }

    public static boolean isWindows() {
        return os() == Os.WINDOWS;
    }

    public static boolean isLinux() {
        return os() == Os.LINUX;
    }

    /**
     * The value for the current operating system.
     */
    public static <T> T pick(T mac, T windows, T linux) {
        return switch (os()) {
            case MAC -> mac;
            case WINDOWS -> windows;
            case LINUX -> linux;
        };
    }
}
