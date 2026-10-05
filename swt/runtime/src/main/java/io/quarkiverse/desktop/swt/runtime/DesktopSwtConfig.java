package io.quarkiverse.desktop.swt.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Desktop SWT build time configuration : the native executables. Every property of this configuration applies to native
 * executables only and is fixed when the native executable is built.
 */
@ConfigMapping(prefix = "quarkus.desktop.swt")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DesktopSwtConfig {

    /**
     * Where the native executable finds the native libraries of SWT ({@code swt-win32-<version>.dll},
     * {@code libswt-gtk-<version>.so}, {@code libswt-cocoa-<version>.jnilib}...).
     * <p>
     * {@code embedded} : the libraries are resources of the native executable, a single file. SWT extracts them on the
     * first run, as it does in JVM mode, into {@code ~/.swt/lib/<os>/<arch>} (or into {@code swt.library.path} when this
     * system property is set). {@code next-to-executable} : the libraries are copied next to the native executable when
     * it is built, and the executable loads them from its directory : nothing is written to the home directory, and the
     * libraries can be signed with the executable (a macOS application bundle). They are then distributed with the
     * executable.
     */
    @WithDefault("embedded")
    NativeLibraries nativeLibraries();

    /**
     * The native executables for Windows.
     */
    Windows windows();

    /**
     * The native executables for Windows.
     */
    interface Windows {

        /**
         * The subsystem of the native executable : {@code console} (the default of native executables), or
         * {@code windows} for a user interface application without a console window, as with {@code javaw}. A
         * {@code windows} executable has no standard output and error streams when it is not started from a console :
         * log to a file ({@code quarkus.log.file.enabled}).
         */
        @WithDefault("console")
        Subsystem subsystem();
    }

    /**
     * The native executables for macOS (a native build on a macOS host, not a container build).
     */
    Macos macos();

    /**
     * The native executables for macOS (a native build on a macOS host, not a container build).
     */
    interface Macos {

        /**
         * Whether the native executable declares the minimum macOS version and the SDK version of the {@code java}
         * launcher of the JDK that builds it (its {@code LC_BUILD_VERSION} load command), as a JVM application does.
         * <p>
         * macOS does not start an executable on a version older than its minimum version, and AppKit chooses the look
         * of the windows and controls (the height of the title bars, the size of the controls, the background color of
         * the widgets for instance) from its SDK version : the executable starts on the macOS versions that the JDK
         * supports, and looks as in JVM mode. When disabled, the linker writes the version of the SDK of the Xcode
         * tools as both : the executable then only starts on that macOS version and later, and gets the look of that
         * version. With the Desktop AWT extension, {@code quarkus.desktop.awt.macos.jdk-build-version} applies
         * instead : that extension writes the versions.
         */
        @WithDefault("true")
        boolean jdkBuildVersion();
    }

    /**
     * Where the native executable finds the native libraries of SWT.
     */
    enum NativeLibraries {
        /**
         * Resources of the native executable, extracted by SWT on the first run.
         */
        EMBEDDED,
        /**
         * Copied next to the native executable when it is built.
         */
        NEXT_TO_EXECUTABLE
    }

    /**
     * The Windows subsystem of the native executable.
     */
    enum Subsystem {
        /**
         * A console application : Windows opens a console window when it is not started from one.
         */
        CONSOLE,
        /**
         * A graphical application, without a console window.
         */
        WINDOWS
    }
}
