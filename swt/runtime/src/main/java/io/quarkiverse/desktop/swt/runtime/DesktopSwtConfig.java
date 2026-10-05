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
