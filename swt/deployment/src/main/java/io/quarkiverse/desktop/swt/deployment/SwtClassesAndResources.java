package io.quarkiverse.desktop.swt.deployment;

/**
 * What SWT needs in a native executable, besides what {@link SwtNativeAccess} computes from the SWT jar (the struct
 * classes whose fields the native code of SWT reads and writes, and the callback methods it calls) : the run time
 * initialization of SWT, the classes, resources and JDK members that SWT looks up by name.
 * <p>
 * <b>Lists.</b> Same conventions as the {@code AwtClassesAndResources} of the Desktop AWT extension : each kind of
 * registration has a list for all platforms ({@code KIND}) and one list per platform ({@code WINDOWS_KIND},
 * {@code LINUX_KIND}, {@code MAC_KIND}) : a native executable gets the common list and the list of the platform it is
 * built for. A platform list only holds what that platform needs alone. Within a list, entries are grouped by area, with
 * a short comment per group, and sorted within a group. A list is a package-private {@code static String[]} field named
 * {@code [PLATFORM_]KIND} : tools read these fields reflectively. Any other constant of this class is a
 * {@code static final String} (or not a {@code String[]}), which these tools ignore.
 * <p>
 * <b>Target platform.</b> Windows or macOS when the build host is Windows or macOS and the build is not a container
 * build, Linux otherwise : the SWT jar of the class path must be the one of the target platform. The {@code MAC_} lists
 * are grouped in a section of their own, at the end of the class.
 * <p>
 * <b>Kinds and entry formats.</b> Class names are binary names
 * ({@code org.eclipse.swt.browser.Edge$HandleCoreWebView2SwtHost}).
 * Parameter types are binary names, primitive type names, or either followed by {@code []} for arrays; {@code ()} means
 * no parameters; a constructor is named {@code <init>}.
 * <ul>
 * <li>{@code RUNTIME_INITIALIZED_PACKAGES} : packages whose classes are initialized at run time (sub packages
 * included).</li>
 * <li>{@code REFLECTIVE_CONSTRUCTORS} : classes registered for reflection with their constructors (also used for
 * classes that are only looked up by name).</li>
 * <li>{@code NEGATIVE_CLASS_LOOKUPS} : class names that SWT looks up and expects not to find, for
 * {@code --exact-reachability-metadata}.</li>
 * <li>{@code JNI_RUNTIME_ACCESS_CLASSES} : classes reached from native code, with all their constructors, methods and
 * fields.</li>
 * <li>{@code JNI_RUNTIME_ACCESS_METHODS} : single methods or constructors reached from native code,
 * {@code "fqcn#name(paramType,...)"}.</li>
 * <li>{@code RESOURCE_BUNDLES} : resource bundle base names. The locales of a bundle included in the native executable
 * are those of the application ({@code quarkus.locales}).</li>
 * <li>{@code RESOURCE_GLOBS} : resources of the SWT jar included in the executable, as glob patterns.</li>
 * <li>{@code CLASS_PATH_SERVICES} : services that SWT loads with the {@code ServiceLoader}, whose providers are on the
 * class path (optional SWT fragments).</li>
 * </ul>
 * <b>Sources.</b> The SWT 3.132 and 3.135 sources (Java and C) of Windows, Linux (GTK) and macOS (Cocoa) : the lookups
 * of {@code Class.forName}, {@code getResource}, {@code ResourceBundle} and {@code ServiceLoader}, and the
 * {@code FindClass}, {@code GetMethodID} and {@code GetStaticMethodID} calls of the native code that are not struct
 * fields or callbacks ; the tracing agent run with the checks of the SWT integration tests.
 */
public final class SwtClassesAndResources {

    private SwtClassesAndResources() {
        // Constants
    }

    // ----------------------------------------------------------------------------------------- run time initialization

    static String[] RUNTIME_INITIALIZED_PACKAGES = {
            // the static initializers of SWT load its native libraries and call them (the sizes of the structs, the
            // selectors of Cocoa, the version of the operating system), or read system properties (swt.autoScale...)
            "org.eclipse.swt",
    };

    static String[] WINDOWS_RUNTIME_INITIALIZED_PACKAGES = {
    };

    static String[] LINUX_RUNTIME_INITIALIZED_PACKAGES = {
    };

    // ---------------------------------------------------------------------------------------------------- reflection

    static String[] REFLECTIVE_CONSTRUCTORS = {
            // Device.<clinit> : Class.forName, whose static initializer installs the device finder of the resources
            // created without a device (new Color(null, ...) on the user interface thread)
            "org.eclipse.swt.widgets.Display",
    };

    static String[] WINDOWS_REFLECTIVE_CONSTRUCTORS = {
    };

    static String[] LINUX_REFLECTIVE_CONSTRUCTORS = {
    };

    // ------------------------------------------------------------------------------------- exact reachability metadata

    static String[] NEGATIVE_CLASS_LOOKUPS = {
            // Browser : the initializer of an application (a class that does not exist by default)
            "org.eclipse.swt.browser.BrowserInitializer",
    };

    static String[] WINDOWS_NEGATIVE_CLASS_LOOKUPS = {
            // Browser (SWT.IE) : the Java plug-in of Internet Explorer
            "com.sun.deploy.services.Service",
            "com.sun.javaws.Globals",
            "sun.plugin2.main.server.IExplorerPlugin",
    };

    static String[] LINUX_NEGATIVE_CLASS_LOOKUPS = {
    };

    // ---------------------------------------------------------------------------------------------------------- JNI

    static String[] JNI_RUNTIME_ACCESS_CLASSES = {
    };

    static String[] WINDOWS_JNI_RUNTIME_ACCESS_CLASSES = {
    };

    static String[] LINUX_JNI_RUNTIME_ACCESS_CLASSES = {
            // accessibility : the ATK interfaces of the widgets call the static atk* methods (FindClass and
            // GetStaticMethodID of os_custom.c)
            "org.eclipse.swt.accessibility.AccessibleObject",
    };

    static String[] JNI_RUNTIME_ACCESS_METHODS = {
            // callback.c : the exceptions of the callbacks are chained
            "java.lang.Throwable#addSuppressed(java.lang.Throwable)",
            // swt.c : ThrowNew when a native allocation fails
            "java.lang.OutOfMemoryError#<init>(java.lang.String)",
    };

    static String[] WINDOWS_JNI_RUNTIME_ACCESS_METHODS = {
    };

    static String[] LINUX_JNI_RUNTIME_ACCESS_METHODS = {
    };

    // ------------------------------------------------------------------------------------------------ resource bundles

    static String[] RESOURCE_BUNDLES = {
            // the messages of SWTException and SWTError, and of some dialogs
            "org.eclipse.swt.internal.SWTMessages",
    };

    static String[] WINDOWS_RESOURCE_BUNDLES = {
    };

    static String[] LINUX_RESOURCE_BUNDLES = {
    };

    // ------------------------------------------------------------------------------------------------------ resources

    static String[] RESOURCE_GLOBS = {
            // Library.isLoadable : whether SWT runs from a jar whose manifest names another platform (the platform of
            // the SWT jar is checked at build time instead)
            "org/eclipse/swt/internal/Library.class",
    };

    static String[] WINDOWS_RESOURCE_GLOBS = {
    };

    static String[] LINUX_RESOURCE_GLOBS = {
            // Device.overrideThemeValues : the style sheets of SWT for the GTK themes, read when the Display is
            // created (it fails without them)
            "org/eclipse/swt/internal/gtk/*.css",
    };

    // --------------------------------------------------------------------------------------------- service providers

    static String[] CLASS_PATH_SERVICES = {
            // the SVG images of the optional org.eclipse.swt.svg fragment (SVGFileFormat.<clinit>)
            "org.eclipse.swt.internal.image.SVGRasterizer",
    };

    static String[] WINDOWS_CLASS_PATH_SERVICES = {
    };

    static String[] LINUX_CLASS_PATH_SERVICES = {
    };

    // -------------------------------------------------------------------------------------------------------- macOS

    static String[] MAC_RUNTIME_INITIALIZED_PACKAGES = {
    };

    static String[] MAC_REFLECTIVE_CONSTRUCTORS = {
    };

    static String[] MAC_NEGATIVE_CLASS_LOOKUPS = {
    };

    static String[] MAC_JNI_RUNTIME_ACCESS_CLASSES = {
    };

    static String[] MAC_JNI_RUNTIME_ACCESS_METHODS = {
    };

    static String[] MAC_RESOURCE_BUNDLES = {
    };

    static String[] MAC_RESOURCE_GLOBS = {
    };

    static String[] MAC_CLASS_PATH_SERVICES = {
    };
}
