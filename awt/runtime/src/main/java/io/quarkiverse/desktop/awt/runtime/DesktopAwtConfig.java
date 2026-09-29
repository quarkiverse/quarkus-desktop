package io.quarkiverse.desktop.awt.runtime;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Desktop AWT build time configuration.
 * <p>
 * Every property of this configuration applies to native executables only and is fixed when the native executable is
 * built : JVM mode applications use the JDK launcher and runtime, which already behave as a desktop application expects.
 * The run time properties ({@link DesktopAwtRuntimeConfig}) apply to both.
 */
@ConfigMapping(prefix = "quarkus.desktop.awt")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DesktopAwtConfig {

    /**
     * Native executables built for Windows.
     */
    Windows windows();

    /**
     * The JavaBeans API in native executables.
     */
    JavaBeans javaBeans();

    /**
     * Whether the native build registers what the JDK desktop code looks up and may not find, for native executables
     * built with {@code --exact-reachability-metadata} (GraalVM : a lookup that is not registered then fails with a
     * missing registration error instead of answering "not found", even when "not found" is the expected answer).
     * <p>
     * The registered lookups are those of the JDK expected to fail (the {@code BeanInfo}, {@code Customizer},
     * {@code PersistenceDelegate} and {@code Editor} classes that the JavaBeans API probes for the JDK classes that the
     * extensions register for it, the region names that Nimbus probes, the {@code .properties} files next to the resource
     * bundles of the JDK, the {@code META-INF/services} files of the desktop services, the {@code processInputMethodEvent}
     * methods that the text components of the JDK do not declare...), the types whose members the JavaBeans API queries,
     * and the var handles of the native memory accesses of Java2D and fonts. They make a native executable about 0.3 MB
     * larger, and are useless without exact reachability metadata. The methods that AWT and Swing look up in the classes
     * of the application and of its libraries ({@code coalesceEvents} in the components...) are registered for every
     * native executable, declared or not.
     * <p>
     * By default, they are registered when {@code quarkus.native.additional-build-args} or
     * {@code quarkus.native.additional-build-args-append} contains {@code --exact-reachability-metadata} (or
     * {@code -H:ThrowMissingRegistrationErrors}). Set this property when the option is given another way.
     */
    Optional<Boolean> exactReachabilityMetadata();

    /**
     * The JavaBeans API ({@code java.beans}) in native executables.
     * <p>
     * The JavaBeans API reads classes with reflection. Native executables always support its core : the property
     * editors of the JDK ({@code PropertyEditorManager}), the bean info of {@code java.awt.Component}, {@code XMLEncoder}
     * and {@code XMLDecoder} with the persistence delegates of the JDK, the JDK collections, dates and
     * {@code java.lang} values, {@code EventHandler} with property change events. The application registers its own
     * classes, for instance with {@code @RegisterForReflection}.
     */
    interface JavaBeans {

        /**
         * Whether the JDK AWT classes support the JavaBeans API in native executables : their public constructors,
         * methods and fields are registered for reflection, so that the {@code Introspector} finds their bean properties
         * and event sets, and that {@code XMLEncoder}, {@code XMLDecoder}, {@code Statement}, {@code Expression},
         * {@code EventHandler} and {@code Beans.instantiate} work with them, as in JVM mode.
         * <p>
         * The classes are the AWT components and menu components, the layouts ({@code GridBagConstraints} included),
         * the values of their properties ({@code Color}, {@code Font}, {@code Insets}, {@code Point},
         * {@code Rectangle}, {@code Cursor}, {@code MenuShortcut}...), the AWT events, listeners and adapters. For
         * instance {@code Introspector.getBeanInfo(Button.class)} finds the {@code label} property,
         * {@code XMLEncoder} writes a {@code Panel} with its layout and components, and
         * {@code EventHandler.create(ActionListener.class, target, "text", "source.label")} reads the label of the
         * source of an {@code ActionEvent}.
         * <p>
         * It makes a native executable about 0.3 MB larger. When disabled, the {@code Introspector} finds no bean
         * property of these classes (other than those that the extensions register for their own needs) and
         * {@code XMLEncoder} cannot write them. The same property of the Desktop Swing extension,
         * {@code quarkus.desktop.swing.java-beans.jdk-classes} (disabled by default), registers the Swing classes, and
         * the AWT classes that they extend whatever the value of this property.
         */
        @WithDefault("true")
        boolean jdkClasses();
    }

    /**
     * Native executables built for Windows (a native build on a Windows host, not a container build).
     */
    interface Windows {

        /**
         * Whether the native executable is DPI aware, as a JVM started by the {@code java} launcher is.
         * <p>
         * When enabled, the {@code sun.java2d.dpiaware} system property defaults to {@code true} in the native executable
         * (a {@code -Dsun.java2d.dpiaware=false} command line option still overrides it), and the application manifest
         * (see {@code quarkus.desktop.awt.windows.manifest}) declares per monitor DPI awareness. Windows then lets the
         * application scale itself to the display scale factor (crisp text and images). When disabled, Windows stretches
         * the windows of the application as bitmaps (blurry) on displays with a scale factor above 100 %.
         */
        @WithDefault("true")
        boolean dpiAware();

        /**
         * Whether to embed an application manifest in the native executable, as the {@code java} launcher has one.
         * <p>
         * The manifest selects the version 6 of the Windows common controls (the visual styles of the native AWT
         * components and dialogs; without it they look like Windows 2000 controls), declares the DPI awareness of the
         * application (see {@code quarkus.desktop.awt.windows.dpi-aware}) and the supported Windows versions. It is
         * embedded by the linker, through {@code -H:NativeLinkerOption} options that the extension adds to the native
         * build.
         */
        @WithDefault("true")
        boolean manifest();

        /**
         * The Windows subsystem of the native executable.
         * <p>
         * {@code console}, the default, is the subsystem of the {@code java} launcher : started from Explorer, the
         * application gets a console window, which shows its log. {@code windows} is the subsystem of the {@code javaw}
         * launcher : no console window (the standard output and error streams of the application are lost unless they
         * are redirected, so configure a log file).
         */
        @WithDefault("console")
        Subsystem subsystem();

        /**
         * Whether to copy the Microsoft Visual C++ runtime libraries ({@code msvcp140.dll}, {@code vcruntime140.dll} and
         * {@code vcruntime140_1.dll}) of the GraalVM used for the native build next to the native executable, when the
         * native executable uses the AWT libraries.
         * <p>
         * The JDK AWT library {@code awt.dll} needs {@code msvcp140.dll}, which a Windows installation does not always
         * have (it comes with the Visual C++ Redistributable). With a local copy, the native executable and its libraries
         * can be distributed as they are, as the JDK does.
         */
        @WithDefault("true")
        boolean copyVcRuntime();

        /**
         * Whether the native executable supports the Java Access Bridge, which screen readers such as JAWS or NVDA use to
         * access the user interface of Java applications.
         * <p>
         * Users enable the Java Access Bridge with {@code jabswitch -enable} (or in the Windows accessibility settings),
         * which configures the {@code assistive_technologies} of every Java application in
         * {@code %USERPROFILE%\.accessibility.properties}. When enabled, the Java Access Bridge is included in the native
         * executable, as it is in the JDK ({@code javaaccessbridge.dll} and {@code jawt.dll} are copied next to the native
         * executable), and it is loaded when the user enabled it. When disabled, the
         * {@code javax.accessibility.assistive_technologies} system property defaults to an empty value in the native
         * executable, so that the native executable ignores the user setting, instead of failing to start with a
         * {@code java.awt.AWTError: Could not load or activate service provider}.
         */
        @WithDefault("true")
        boolean accessBridge();

        /**
         * The logical font configuration (the physical fonts behind the {@code Dialog}, {@code SansSerif},
         * {@code Serif}, {@code Monospaced} and {@code DialogInput} logical fonts, used by AWT components and by default
         * in Swing).
         * <p>
         * {@code jdk}, the default, is the font configuration of the JDK used for the native build (its
         * {@code lib/fontconfig.properties.src} file, embedded in the native executable and extracted to the temporary
         * directory at startup) : the logical fonts display the same scripts as in JVM mode (Arabic, Hebrew, Chinese,
         * Japanese, Korean, Thai, Indic scripts...), and all the charsets of the JDK are included in the native
         * executable, since the font configuration uses many of them. {@code minimal} is the minimal font configuration
         * of the Quarkus AWT extension (Latin scripts only) : a slightly smaller native executable, with the standard
         * charsets only (fonts whose names or character maps use a legacy encoding such as Shift_JIS or GBK, and RTF
         * text in other charsets, are then not read as in JVM mode). On Linux and macOS, all the charsets are always
         * included.
         */
        @WithDefault("jdk")
        FontConfiguration fontConfiguration();
    }

    /**
     * Native executables built for macOS (a native build on a macOS host, not a container build).
     */
    Macos macos();

    /**
     * Native executables built for macOS (a native build on a macOS host, not a container build).
     */
    interface Macos {

        /**
         * Whether the first thread of the process runs the Cocoa event loop, as with the {@code java} launcher, the
         * Quarkus application running on a new thread named {@code main}.
         * <p>
         * AppKit, which AWT, Swing and JavaFX use on macOS, only runs on the first thread of the process : without this,
         * the first window of an AWT or Swing application never shows. Disable it only with the Quarkus FX launcher,
         * which then runs JavaFX on the first thread itself (AWT then runs embedded in JavaFX), or with a GraalVM version
         * that keeps the first thread in the Cocoa event loop itself.
         */
        @WithDefault("true")
        boolean parkMainThread();

        /**
         * The stack size of the thread that runs the Quarkus application (the first thread of a macOS process has 8 MiB,
         * the other threads 512 KiB by default).
         */
        @WithDefault("8M")
        MemorySize mainThreadStackSize();

        /**
         * How long {@code System.exit} may take once the application has stopped before the process is halted, {@code 0}
         * to wait for ever. A safety net against an exit that never completes while AppKit runs on the first thread.
         */
        @WithDefault("10s")
        Duration exitHaltTimeout();

        /**
         * The name of the application in the menu bar and the Dock : the default value of the
         * {@code apple.awt.application.name} system property in the native executable (the {@code java} launcher sets it
         * to the simple name of the main class). The Quarkus application name ({@code quarkus.application.name}) by
         * default.
         */
        Optional<String> applicationName();

        /**
         * Whether to embed an information property list ({@code Info.plist}) in the native executable, as the {@code java}
         * launcher has one : bundle identifier, name and versions of the application, high resolution capability, and the
         * description of the microphone use (Java Sound capture) that macOS shows when it asks the user for the
         * permission.
         */
        @WithDefault("false")
        boolean infoPlist();

        /**
         * Whether the native executable declares the minimum macOS version and the SDK version of the {@code java}
         * launcher of the JDK that builds it (its {@code LC_BUILD_VERSION} load command), as a JVM application does.
         * <p>
         * macOS does not start an executable on a version older than its minimum version, and AppKit chooses the look of
         * the windows (the height of the title bars for instance) and its compatibility behaviors from its SDK version.
         * When disabled, the linker writes the version of the SDK of the Xcode tools as both : the executable then only
         * starts on that macOS version and later, and gets the look of that version.
         */
        @WithDefault("true")
        boolean jdkBuildVersion();
    }

    /**
     * Windows subsystem of a native executable.
     */
    enum Subsystem {
        /**
         * Console application (the subsystem of {@code java.exe}).
         */
        CONSOLE,
        /**
         * Graphical application without console (the subsystem of {@code javaw.exe}).
         */
        WINDOWS
    }

    /**
     * Logical font configuration of a native executable.
     */
    enum FontConfiguration {
        /**
         * The font configuration of the JDK used for the native build.
         */
        JDK,
        /**
         * The minimal font configuration of the Quarkus AWT extension.
         */
        MINIMAL
    }
}
