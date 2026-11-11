package io.quarkiverse.desktop.awt.runtime.graal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;
import org.graalvm.nativeimage.hosted.RuntimeSystemProperties;

import io.quarkiverse.desktop.awt.runtime.DesktopAwtSupport;
import io.quarkiverse.desktop.awt.runtime.macos.MacMainThread;

/**
 * Sets the default value of system properties in the native executable : the ones the JVM sets for AWT, and the ones
 * configured by the extension. Embeds the files of the JDK running the native build that the native executable needs.
 * <p>
 * The Quarkus build passes the settings to the native image builder as builder JVM system properties (a
 * {@code NativeImageSystemPropertyBuildItem} only reaches the builder JVM). A value registered here is the default value
 * in the native executable : a {@code -D} command line option of the native executable still overrides it.
 */
public final class DesktopAwtFeature implements Feature {

    /**
     * Builder system property : {@code true} to make the native executable DPI aware on Windows.
     */
    public static final String DPI_AWARE = "io.quarkiverse.desktop.awt.dpi-aware";

    /**
     * Builder system property : {@code true} to ignore the assistive technologies configured by the user (Java Access
     * Bridge not included in the native executable).
     */
    public static final String IGNORE_ASSISTIVE_TECHNOLOGIES = "io.quarkiverse.desktop.awt.ignore-assistive-technologies";

    /**
     * Builder system property : the name of the application in the macOS menu bar and Dock
     * ({@code apple.awt.application.name}).
     */
    public static final String MAC_APPLICATION_NAME = "io.quarkiverse.desktop.awt.macos.application-name";

    @Override
    public String getDescription() {
        return "Quarkus Desktop AWT run time system property defaults";
    }

    /**
     * Registers the defaults. {@link RuntimeSystemProperties} cannot be used before this phase (its support singleton
     * is registered during the setup of the native image builder), and the Quarkus generated feature registers its own
     * defaults in this phase too.
     */
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // Set by the JVM (UnicodeLittle on little endian platforms), not by native executables : sun.awt.FontDescriptor
        // reads it to encode the text of the native AWT components (buttons, labels, lists, menus...) in UTF-16, and
        // defaults to big endian, which garbles every native component text on Windows
        String unicodeEncoding = System.getProperty("sun.io.unicode.encoding");
        if (unicodeEncoding != null) {
            RuntimeSystemProperties.register("sun.io.unicode.encoding", unicodeEncoding);
        }
        if (Boolean.getBoolean(DPI_AWARE)) {
            // Read by sun.java2d.windows.WindowsFlags : the java launcher makes the JVM DPI aware through the
            // sun.java.launcher property, which a native executable does not have
            RuntimeSystemProperties.register("sun.java2d.dpiaware", "true");
        }
        if (Boolean.getBoolean(IGNORE_ASSISTIVE_TECHNOLOGIES)) {
            // Read by java.awt.Toolkit before the assistive_technologies of ~/.accessibility.properties : an empty value
            // loads no assistive technology
            RuntimeSystemProperties.register("javax.accessibility.assistive_technologies", "");
        }
        // The configuration of the thread that runs the application on macOS (read by MacMainThread at run time)
        for (String property : List.of(MacMainThread.STACK_SIZE_PROPERTY, MacMainThread.EXIT_HALT_TIMEOUT_PROPERTY)) {
            String value = System.getProperty(property);
            if (value != null) {
                RuntimeSystemProperties.register(property, value);
            }
        }
        if (Platform.includedIn(Platform.DARWIN.class)) {
            macos();
        }
    }

    private static void macos() {
        // The Metal shader library, read from java.home/lib by sun.java2d.metal.MTLGraphicsConfig : without it, Java2D
        // silently falls back to OpenGL. The one of the JDK running the builder, whose libraries GraalVM copies next to
        // the executable (the library varies between JDK builds). Extracted at startup by DesktopAwtSupport.
        Path shaders = Path.of(System.getProperty("java.home"), "lib", "shaders.metallib");
        if (Files.isRegularFile(shaders)) {
            try {
                RuntimeResourceAccess.addResource(DesktopAwtFeature.class.getModule(), DesktopAwtSupport.METAL_SHADERS,
                        Files.readAllBytes(shaders));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            System.err.println("[quarkus-desktop] " + shaders + " not found : Java2D uses OpenGL instead of Metal in the"
                    + " native executable");
        }
        // Set by the java launcher (to the simple name of the main class) : the name of the application in the menu bar
        String name = System.getProperty(MAC_APPLICATION_NAME);
        if (name != null && !name.isBlank()) {
            RuntimeSystemProperties.register("apple.awt.application.name", name);
        }
    }
}
