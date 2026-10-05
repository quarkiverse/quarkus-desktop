package io.quarkiverse.desktop.swt.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * The SWT jar of the application : the jar of a platform ({@code org.eclipse.platform:org.eclipse.swt.<ws>.<os>.<arch>}),
 * with its native libraries. Not produced when the application has no SWT jar (a Gradle build, which does not apply the
 * profiles of the {@code org.eclipse.swt} pom, without the dependency on the jar of the platform).
 */
public final class SwtPlatformBuildItem extends SimpleBuildItem {

    /**
     * The windowing systems of SWT.
     */
    public enum WindowingSystem {
        WIN32,
        GTK,
        COCOA
    }

    private final ResolvedDependency jar;
    private final WindowingSystem windowingSystem;
    private final String os;
    private final String arch;

    /**
     * @param os {@code SWT-OS} of the manifest of the jar : {@code win32}, {@code linux}, {@code macosx}
     * @param arch {@code SWT-Arch} of the manifest of the jar : {@code x86_64}, {@code aarch64}...
     */
    public SwtPlatformBuildItem(ResolvedDependency jar, WindowingSystem windowingSystem, String os, String arch) {
        this.jar = jar;
        this.windowingSystem = windowingSystem;
        this.os = os;
        this.arch = arch;
    }

    /**
     * The SWT jar of the platform.
     */
    public ResolvedDependency getJar() {
        return jar;
    }

    public WindowingSystem getWindowingSystem() {
        return windowingSystem;
    }

    public String getOs() {
        return os;
    }

    public String getArch() {
        return arch;
    }

    public boolean isWindows() {
        return windowingSystem == WindowingSystem.WIN32;
    }

    public boolean isLinux() {
        return windowingSystem == WindowingSystem.GTK;
    }

    public boolean isMac() {
        return windowingSystem == WindowingSystem.COCOA;
    }

    /**
     * The value for the platform of the jar.
     */
    public <T> T select(T windows, T linux, T mac) {
        return switch (windowingSystem) {
            case WIN32 -> windows;
            case GTK -> linux;
            case COCOA -> mac;
        };
    }

    /**
     * The common entries followed by those of the platform of the jar.
     */
    public String[] withPlatform(String[] common, String[] windows, String[] linux, String[] mac) {
        String[] platform = select(windows, linux, mac);
        String[] all = new String[common.length + platform.length];
        System.arraycopy(common, 0, all, 0, common.length);
        System.arraycopy(platform, 0, all, common.length, platform.length);
        return all;
    }

    @Override
    public String toString() {
        return jar.toCompactCoords() + " (" + windowingSystem.name().toLowerCase() + ", " + os + ", " + arch + ")";
    }
}
