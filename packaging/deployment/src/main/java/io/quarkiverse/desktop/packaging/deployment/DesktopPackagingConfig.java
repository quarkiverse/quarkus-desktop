package io.quarkiverse.desktop.packaging.deployment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.TrimmedStringConverter;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithConverter;
import io.smallrye.config.WithDefault;

/**
 * Desktop Packaging build time configuration : the packages of the application, built after its jar (JVM mode) or its
 * native executable. The paths are relative to the directory of the project.
 */
@ConfigMapping(prefix = "quarkus.desktop.packaging")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DesktopPackagingConfig {

    /**
     * Whether the extension does anything : the packages, and the icon and version of the Windows native executable.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The packages to build : {@code directory} (the application image, which the other formats install), {@code msi},
     * {@code exe}, {@code msix} (Windows), {@code dmg}, {@code pkg} (macOS), {@code deb}, {@code rpm}, {@code appimage},
     * {@code flatpak}, {@code snap} (Linux). The formats of the other operating systems are skipped : the same list
     * builds the packages of each operating system, on a machine of that operating system. None by default.
     * <p>
     * A native build ({@code quarkus.native.enabled}) packages the native executable, a JVM build packages the jar with
     * a Java runtime ({@code jpackage} and {@code jlink} of the JDK). Each format needs its tool on the machine of the
     * build : the build fails when it is missing.
     */
    Optional<List<PackageFormat>> formats();

    /**
     * The name of the application, as its users see it : the name of the shortcuts, of the installed program, of the
     * application bundle on macOS. Defaults to {@code quarkus.application.name}. It names the files of the application
     * on Windows and macOS : it has none of {@code \ / : * ? " < > | % ;} on Windows, none of {@code / " %} on macOS,
     * and no control characters. When it is set, Desktop AWT and Desktop SWT give it to the application at run time
     * too, unless their own property sets another name : the name of the application menu of macOS for both, and for
     * SWT the identity of the application on Windows and the class of its windows on Linux.
     */
    Optional<String> name();

    /**
     * The version of the application. Defaults to {@code quarkus.application.version}. The formats that need numbers
     * (MSI, MSIX, macOS) take its leading numbers, without the {@code v} of a tag : {@code 1.2.3-SNAPSHOT} and
     * {@code v1.2.3} are {@code 1.2.3} for them. Windows compares them to upgrade the application, up to a limit : 255,
     * 255 and 65535 for the three numbers of an MSI package, 65535 for an MSIX package. The build of such a package
     * fails for a larger number, such as the year of {@code 2025.1.0}.
     */
    Optional<String> version();

    /**
     * The identifier of the application, a reverse domain name such as {@code org.acme.editor} : the bundle identifier
     * on macOS (without underscores : an element loses its leading ones, and the others are dashes), the application
     * ID of Flatpak (at least three elements, none of which starts with a digit), the identity of the MSIX package, the
     * source of the upgrade code of the Windows installers. Defaults to the group ID of the project followed by the
     * name of the application.
     */
    Optional<String> identifier();

    /**
     * The vendor of the application : the publisher on Windows, the maintainer of the Linux packages.
     */
    @WithDefault("Unknown")
    String vendor();

    /**
     * A one line description of the application. Defaults to its name.
     */
    Optional<String> description();

    /**
     * The copyright notice of the application.
     */
    Optional<String> copyright();

    /**
     * The home page of the application.
     */
    Optional<String> homepage();

    /**
     * The license of the application, as a text file : shown by the Windows setup program and by the macOS installer
     * package, installed with the Debian and RPM packages.
     */
    Optional<String> licenseFile();

    /**
     * The icon of the application, a square PNG file of 512 or 1024 pixels : the extension derives the icons of every
     * format from it ({@code .ico}, {@code .icns}, the PNG files of each size). Defaults to
     * {@code src/main/packaging/icon.png} when the file exists, and to a generic icon otherwise.
     */
    Optional<String> icon();

    /**
     * A directory of files to add to the application image : documents, data files. They are next to the executable,
     * in {@code Contents/Resources} on macOS, and at the root of the image in JVM mode on Linux, where the launcher is
     * in the {@code bin} directory. Defaults to {@code src/main/packaging/content} when the directory exists.
     */
    Optional<@WithConverter(TrimmedStringConverter.class) String> contentDirectory();

    /**
     * The directory of the packages, relative to the build directory ({@code target}, {@code build} with Gradle), or
     * absolute.
     */
    @WithDefault("packaging")
    String outputDirectory();

    /**
     * The paths of the packaging tools that are not on the {@code PATH}, by name : {@code wix}, {@code iscc},
     * {@code makeappx}, {@code signtool}, {@code hdiutil}, {@code pkgbuild}, {@code productbuild}, {@code codesign},
     * {@code xcrun}, {@code dpkg-deb}, {@code dpkg-shlibdeps}, {@code rpmbuild}, {@code appimagetool}, {@code flatpak},
     * {@code snapcraft}, {@code jpackage}.
     */
    @ConfigDocMapKey("tool")
    Map<String, String> tools();

    /**
     * The types of files that the application opens, by name ({@code document}) : the packages register the
     * application for their extensions, so that the file managers open the files with it. On Windows and Linux the
     * application is then started with the file as an argument, on macOS it gets the file as an event : Desktop AWT and
     * Desktop SWT fire an {@code OpenRequest} with it, in both cases.
     */
    @ConfigDocMapKey("type")
    Map<String, FileType> fileTypes();

    /**
     * The URL schemes of the application ({@code acme} for the URLs {@code acme://...}) : the packages register the
     * application for them, so that the browsers and the other applications open these URLs with it, as they open the
     * files of {@code file-types}.
     */
    Optional<List<@WithConverter(TrimmedStringConverter.class) String>> urlSchemes();

    /**
     * The packages of Windows.
     */
    Windows windows();

    /**
     * The packages of macOS.
     */
    Macos macos();

    /**
     * The packages of Linux.
     */
    Linux linux();

    /**
     * The application image of JVM mode.
     */
    Jvm jvm();

    /**
     * A type of files that the application opens.
     */
    interface FileType {

        /**
         * The extensions of the files, without their dot ({@code acme}).
         */
        List<@WithConverter(TrimmedStringConverter.class) String> extensions();

        /**
         * The MIME type of the files, which Linux knows the files by. Defaults to
         * {@code application/x-<package name>-<type>}.
         */
        Optional<String> mimeType();

        /**
         * The description of the type, which the file managers show. Defaults to the name of the application followed
         * by the name of the type.
         */
        Optional<String> description();

        /**
         * The icon of the files, a square PNG file of 256 pixels or more. Defaults to the icon of the application.
         */
        Optional<String> icon();
    }

    /**
     * The packages of Windows.
     */
    interface Windows {

        /**
         * Whether the native executable gets the icon of the application and its version information (the Details tab
         * of its properties : product name, version, vendor, copyright). They are resources linked in the executable
         * when it is built : the extension has to be in the application then, with or without packages. Native sources
         * ({@code quarkus.native.sources-only=true}) and native-image bundles ({@code quarkus.native.bundle.*}) do not
         * get them : they are linked from a file of the build directory. An application that links a resource file of its own
         * ({@code -H:NativeLinkerOption=app.res} in
         * {@code quarkus.native.additional-build-args}) keeps it, without them ; one linked in another way conflicts
         * with them (the link fails with {@code CVT1100: duplicate resource}) unless this is {@code false}. In JVM
         * mode, {@code jpackage} gives them to its launcher.
         */
        @WithDefault("true")
        boolean executableResources();

        /**
         * The upgrade code of the application, a GUID, with or without braces : the Windows installers of the next
         * versions upgrade the application that has the same code. Defaults to a GUID derived from the identifier of
         * the application : never change either once the application is released.
         */
        Optional<String> upgradeCode();

        /**
         * Whether the application is installed for the current user only, without administrator rights (in
         * {@code %LocalAppData%\Programs}), rather than for all users (in {@code Program Files}).
         */
        @WithDefault("false")
        boolean perUser();

        /**
         * The name of the installation directory. Defaults to the name of the application.
         */
        Optional<String> installDirectory();

        /**
         * Whether the installers add a shortcut to the Start menu.
         */
        @WithDefault("true")
        boolean startMenu();

        /**
         * The folder of the shortcut in the Start menu. None by default : the shortcut is in the programs folder.
         */
        Optional<String> startMenuGroup();

        /**
         * Whether the installers add a shortcut to the desktop : the MSI package always does, the setup program lets
         * the user choose.
         */
        @WithDefault("false")
        boolean desktopShortcut();

        /**
         * The MSIX package.
         */
        Msix msix();

        /**
         * The signature of the executables of the application image and of the Windows packages, with
         * {@code signtool} of the Windows SDK.
         */
        Sign sign();
    }

    /**
     * The MSIX package.
     */
    interface Msix {

        /**
         * The name of the identity of the package : letters, digits, dots and dashes. Defaults to the identifier of
         * the application.
         */
        Optional<String> identityName();

        /**
         * The publisher of the package, a distinguished name that has to be the subject of the certificate that signs
         * the package ({@code CN=Acme, O=Acme, C=US}). Defaults to {@code CN=} followed by the vendor.
         */
        Optional<String> publisher();

        /**
         * The name of the publisher, as the users see it. Defaults to the vendor.
         */
        Optional<String> publisherDisplayName();

        /**
         * The capabilities of the application : general capabilities ({@code internetClient}), restricted ones
         * ({@code allowElevation}), and the others with their kind ({@code rescap:packageQuery},
         * {@code uap:documentsLibrary}, {@code device:microphone}). A desktop application is a full trust application :
         * {@code runFullTrust} is always declared.
         */
        @WithDefault("runFullTrust")
        List<@WithConverter(TrimmedStringConverter.class) String> capabilities();

        /**
         * The minimum version of Windows that installs the package : four numbers, such as {@code 10.0.17763.0}
         * (Windows 10 1809) or {@code 10.0.22000.0} (Windows 11). The package is declared as tested on
         * {@code 10.0.26100.0} (Windows 11 24H2), or on the minimum version when it is later.
         */
        @WithDefault("10.0.17763.0")
        String minVersion();

        /**
         * The URL of the directory where the MSIX package is published ({@code https://acme.org/downloads}) : the build
         * then writes an App Installer file next to the package ({@code <name>.appinstaller}), which Windows installs the
         * package from, and with which it updates the application when that directory has a new version. Publish both
         * files in that directory, and give the users the URL of the App Installer file.
         */
        Optional<String> updateUrl();

        /**
         * How often Windows looks for a new version of an application installed from the App Installer file, in hours,
         * when it starts : {@code 0} at every start.
         */
        @WithDefault("24")
        int updateHours();
    }

    /**
     * The signature of the Windows executable and packages.
     */
    interface Sign {

        /**
         * The certificate file ({@code .pfx}) that signs the executables and the packages. Nothing is signed without
         * a certificate file, a subject name, a thumbprint or arguments. An unsigned MSIX package cannot be installed.
         */
        Optional<@WithConverter(TrimmedStringConverter.class) String> certificateFile();

        /**
         * The password of the certificate file.
         */
        Optional<String> certificatePassword();

        /**
         * The subject name of the signing certificate, in the certificate store.
         */
        Optional<String> subjectName();

        /**
         * The SHA-1 thumbprint of the signing certificate, in the certificate store.
         */
        Optional<String> thumbprint();

        /**
         * The URL of the RFC 3161 time stamp server.
         */
        Optional<String> timestampUrl();

        /**
         * More arguments of {@code signtool sign}, after the ones of the other properties : {@code /sm} (a certificate
         * of the machine store), {@code /csp} and {@code /kc} (a key of a cryptographic provider), {@code /dlib} and
         * {@code /dmdf} (a signing service such as Azure Trusted Signing). They sign on their own, without a
         * certificate file, a subject name or a thumbprint. The extension adds {@code /fd SHA256} unless they have
         * {@code /fd}, and with {@code timestamp-url} it adds {@code /tr} and, unless they have {@code /td},
         * {@code /td SHA256}. Write the paths absolute (the setup program is signed in the directory of Inno Setup) and
         * with {@code /} : in a list, {@code \} escapes the next character.
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> arguments();
    }

    /**
     * The packages of macOS.
     */
    interface Macos {

        /**
         * The category of the application ({@code LSApplicationCategoryType}) : its identifier, such as
         * {@code public.app-category.developer-tools}, or the last part of it, such as {@code developer-tools}.
         */
        @WithDefault("public.app-category.utilities")
        String category();

        /**
         * The minimum version of macOS that runs the application ({@code LSMinimumSystemVersion}). Defaults to the
         * minimum version that the native executable declares, and in JVM mode to the one that {@code jpackage} writes.
         */
        Optional<String> minimumSystemVersion();

        /**
         * More entries of the {@code Info.plist} of the application bundle : the usage descriptions of the privacy
         * permissions ({@code NSCameraUsageDescription}), for instance. The values are strings, except {@code true} and
         * {@code false}, which are Booleans. An entry replaces the one of the same key that the extension writes
         * ({@code NSHighResolutionCapable}, {@code NSSupportsAutomaticGraphicsSwitching}, {@code CFBundleName}...), or
         * that {@code jpackage} writes in JVM mode.
         */
        @ConfigDocMapKey("key")
        Map<String, String> infoPlist();

        /**
         * The directory where the installer package installs the application bundle.
         */
        @WithDefault("/Applications")
        String installLocation();

        /**
         * The signature of the application bundle and of the packages.
         */
        MacSign sign();

        /**
         * The notarization of the packages.
         */
        Notarize notarize();
    }

    /**
     * The signature of the macOS application bundle and packages.
     */
    interface MacSign {

        /**
         * Whether the application bundle and the packages are signed. When they are not, whatever the identities, the
         * bundle of a JVM application that {@code jpackage} signed ad hoc (on Apple silicon) is signed ad hoc again
         * after the changes of the extension, so that its signature stays valid.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * The identity that signs the application bundle and the disk image, a {@code Developer ID Application}
         * certificate of the keychain. By default, the signature is ad hoc ({@code -}) : the application runs on the
         * machine that built it, and Gatekeeper blocks it on the others. A certificate also signs the macOS libraries
         * inside the jars of a JVM application that are not signed with a certificate, which the notary service checks :
         * a signed jar ({@code META-INF/*.SF}), which cannot be changed, that has one fails the build when the packages
         * are notarized, and gets a warning otherwise.
         */
        @WithDefault("-")
        String identity();

        /**
         * The identity that signs the installer package, a {@code Developer ID Installer} certificate of the keychain,
         * unless {@code macos.sign.enabled} is {@code false}. The installer package is not signed by default : the
         * build warns about it when the application bundle is signed with a certificate.
         */
        Optional<String> installerIdentity();

        /**
         * The entitlements of the application, a property list file, in place of the default ones. With a certificate,
         * the hardened runtime only loads the libraries signed by Apple or with the identity of the application, unless
         * an entitlement allows the others. By default, a JVM application has the entitlements that the JVM needs
         * (just-in-time compilation, unsigned executable memory, libraries of other teams), and a native executable
         * signed with a certificate has the one of the libraries of other teams
         * ({@code com.apple.security.cs.disable-library-validation}), for the libraries that it loads from outside the
         * bundle, such as the ones that SWT extracts to the home directory of the user.
         */
        Optional<String> entitlements();

        /**
         * The keychain of the identities. Defaults to the keychains of the user.
         */
        Optional<String> keychain();
    }

    /**
     * The notarization of the macOS packages.
     */
    interface Notarize {

        /**
         * The keychain profile of the credentials of the notary service, stored with
         * {@code xcrun notarytool store-credentials}. When set, the disk image and the installer package are submitted
         * to the notary service of Apple, and the ticket is stapled to them : the build waits for the answer, and fails
         * with the log of the submission when the service does not accept a package. They have to be signed with a
         * Developer ID identity ({@code macos.sign.identity}, and {@code macos.sign.installer-identity} for the
         * installer package) : the build fails before they are built otherwise. Without a keychain profile, the build
         * warns that the packages of a bundle signed with a certificate are not notarized.
         */
        Optional<String> keychainProfile();

        /**
         * How long the build waits for the answer of the notary service, as the {@code --timeout} option of
         * {@code notarytool} takes it ({@code 30m}, {@code 2h}) : the build fails when the time is up. No limit by
         * default.
         */
        Optional<String> timeout();
    }

    /**
     * The packages of Linux.
     */
    interface Linux {

        /**
         * The name of the package and of the command of the application : lowercase letters, digits and dashes.
         * Defaults to the name of the application, in that form.
         */
        Optional<String> packageName();

        /**
         * The directory where the Debian and RPM packages install the application, in a directory named after the
         * package : an absolute directory other than {@code /}, whose names have letters, digits, dots, dashes,
         * underscores and plus signs.
         */
        @WithDefault("/opt")
        String installDirectory();

        /**
         * The categories of the desktop entry, which place the application in the menus.
         */
        @WithDefault("Utility")
        List<@WithConverter(TrimmedStringConverter.class) String> categories();

        /**
         * The window class of the application ({@code StartupWMClass} of the desktop entry) : desktops use it to
         * associate the windows of the application with its launcher. Defaults to the class that Desktop SWT gives the
         * windows of the application, none otherwise.
         */
        Optional<String> startupWmClass();

        /**
         * The release of the Debian and RPM packages : the version of the package itself, for a given version of the
         * application.
         */
        @WithDefault("1")
        String release();

        /**
         * The Debian package.
         */
        Deb deb();

        /**
         * The RPM package.
         */
        Rpm rpm();

        /**
         * The AppImage.
         */
        AppImage appimage();

        /**
         * The Flatpak bundle.
         */
        Flatpak flatpak();

        /**
         * The snap.
         */
        Snap snap();
    }

    /**
     * The Debian package.
     */
    interface Deb {

        /**
         * The maintainer of the package, as a name and an email address : {@code Jane Doe <jane@acme.org>}. Defaults
         * to the vendor.
         */
        Optional<String> maintainer();

        /**
         * The section of the package.
         */
        @WithDefault("utils")
        String section();

        /**
         * Whether the package depends on the packages of the shared libraries that the application is linked with,
         * found with {@code dpkg-shlibdeps} (the {@code dpkg-dev} package).
         */
        @WithDefault("true")
        boolean autoDepends();

        /**
         * More packages that the package depends on : the libraries that the application loads at run time
         * ({@code dlopen}), which the automatic dependencies miss.
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> depends();
    }

    /**
     * The RPM package.
     */
    interface Rpm {

        /**
         * The license of the application, as RPM names it : {@code Apache-2.0}, {@code GPL-3.0-or-later}...
         */
        @WithDefault("Unknown")
        String license();

        /**
         * The group of the package.
         */
        Optional<String> group();

        /**
         * Whether the package requires the shared libraries that the application is linked with, found by
         * {@code rpmbuild}.
         */
        @WithDefault("true")
        boolean autoRequires();

        /**
         * More capabilities that the package requires.
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> requires();
    }

    /**
     * The AppImage.
     */
    interface AppImage {

        /**
         * The runtime of the AppImage, for a build without access to GitHub or with a runtime of a known version : a
         * {@code runtime-<arch>} file of https://github.com/AppImage/type2-runtime/releases. By default,
         * {@code appimagetool} downloads the latest runtime at each build.
         */
        Optional<String> runtimeFile();

        /**
         * The update information of the AppImage, which the AppImage updaters (AppImageUpdate, AppImageLauncher) read
         * to download a new version : {@code gh-releases-zsync|<owner>|<repository>|latest|<name>-*-<arch>.AppImage.zsync}
         * for the releases of GitHub, or {@code zsync|<URL of the .zsync file>}. The build then writes the
         * {@code .zsync} file next to the AppImage (with {@code zsyncmake}), to publish with it.
         */
        Optional<String> updateInformation();
    }

    /**
     * The Flatpak bundle.
     */
    interface Flatpak {

        /**
         * The runtime of the application. The default one has GTK 3, which SWT needs. {@code org.gnome.Platform} also
         * has WebKitGTK, which the {@code Browser} widget of SWT uses : with
         * {@code quarkus.desktop.packaging.linux.flatpak.sdk=org.gnome.Sdk} and
         * {@code quarkus.desktop.packaging.linux.flatpak.runtime-version} set to a version of GNOME, such as {@code 50}.
         */
        @WithDefault("org.freedesktop.Platform")
        String runtime();

        /**
         * The version of the runtime and of its SDK : a branch of the runtime on Flathub, such as {@code 25.08} for
         * {@code org.freedesktop.Platform} or {@code 50} for {@code org.gnome.Platform}. A branch reaches its end of
         * life after one or two years, and gets no security fixes then : the version follows the releases of the
         * runtime.
         */
        @WithDefault("25.08")
        String runtimeVersion();

        /**
         * The SDK of the runtime, of the same version : {@code org.gnome.Sdk} for {@code org.gnome.Platform}.
         */
        @WithDefault("org.freedesktop.Sdk")
        String sdk();

        /**
         * The branch of the application.
         */
        @WithDefault("stable")
        String branch();

        /**
         * The permissions of the sandbox, as the options of {@code flatpak build-finish}.
         */
        @WithDefault("--share=ipc,--socket=x11,--socket=wayland,--socket=pulseaudio,--share=network,--device=dri,--filesystem=home")
        List<@WithConverter(TrimmedStringConverter.class) String> finishArgs();
    }

    /**
     * The snap.
     */
    interface Snap {

        /**
         * The base snap.
         */
        @WithDefault("core24")
        String base();

        /**
         * The confinement of the snap : {@code strict}, {@code classic} or {@code devmode}. A {@code classic} snap
         * has no {@code gnome} extension : set {@code quarkus.desktop.packaging.linux.snap.extensions} to empty.
         */
        @WithDefault("strict")
        String confinement();

        /**
         * The grade of the snap : {@code stable} or {@code devel}.
         */
        @WithDefault("stable")
        String grade();

        /**
         * The extensions of the application of the snap, empty for none. The {@code gnome} extension gives it the
         * libraries of a desktop application (X11, Wayland, GTK, fonts, themes) : it supports the {@code strict} and
         * {@code devmode} confinements, not {@code classic}.
         */
        @WithDefault("gnome")
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> extensions();

        /**
         * The interfaces that the application plugs.
         */
        @WithDefault("home,network,audio-playback,removable-media")
        List<@WithConverter(TrimmedStringConverter.class) String> plugs();

        /**
         * The packages of the Ubuntu archive to add to the snap.
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> stagePackages();

        /**
         * More arguments of {@code snapcraft pack} : {@code --destructive-mode} to build on the host rather than in
         * an LXD container, for instance.
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> buildArgs();
    }

    /**
     * The application image of JVM mode : the jar of the application with a Java runtime and a launcher, built with
     * {@code jpackage}.
     */
    interface Jvm {

        /**
         * The JDK whose {@code jpackage} builds the application image, JDK 21 or later : its {@code jlink} builds the
         * Java runtime of the application, which is then of that version. Defaults to the JDK of the build, rather than
         * the one of the {@code PATH} ; {@code quarkus.desktop.packaging.tools.jpackage} takes precedence over both.
         */
        Optional<String> jdk();

        /**
         * The modules of the Java runtime of the application ({@code --add-modules} of {@code jpackage}), to which the
         * extension adds the ones that Quarkus needs. By default, the runtime has the modules of Java SE and the ones of
         * the JDK that export an API, as an application on the class path may use any of them, and the modules that
         * provide their services : the locale data, the charsets that are not standard, the key stores of Windows...
         * A list of modules makes a smaller runtime, without these services unless it lists their modules :
         * {@code jdk.localedata} for the languages other than English, {@code jdk.charsets},
         * {@code jdk.crypto.mscapi}... With JDK 21, the extension adds {@code jdk.crypto.ec}, which TLS needs.
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> modules();

        /**
         * The options of {@code jlink}. Defaults to the ones of {@code jpackage} : no debug information, no header
         * files, no manual pages, no commands. On Linux, {@code jlink} strips the debug information of the native
         * libraries ({@code --strip-debug}) with {@code objcopy}, which has to be on the {@code PATH} (the
         * {@code binutils} package).
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> jlinkOptions();

        /**
         * A Java runtime to use rather than one built with {@code jlink} : {@code jpackage} takes it as it is, so
         * {@code quarkus.desktop.packaging.jvm.modules} and {@code quarkus.desktop.packaging.jvm.jlink-options} cannot
         * be set with it.
         */
        Optional<String> runtimeImage();

        /**
         * The options of the JVM of the application, separated by commas : {@code -Xms64m,-Xmx512m},
         * {@code -Dkey=value}... Each one is one argument of the JVM, white space and quotes included
         * ({@code -Dapp.title=Acme Editor}) : the build warns about one that holds several options
         * ({@code -Xms64m -Xmx512m}), which the JVM rejects. The long form of an option with its value after white
         * space ({@code --add-opens java.base/java.lang=ALL-UNNAMED}) is written with {@code =}. Write the paths with
         * {@code /}, which Java accepts on Windows ({@code -Djava.library.path=$APPDIR/native}) : in a list, {@code \}
         * escapes the next character ({@code C:\lib} is {@code C:lib}). An option with a comma or a backslash is set in
         * an indexed property, and the other options then too, as the indexed properties replace the list
         * ({@code quarkus.desktop.packaging.jvm.options[0]=-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3}).
         */
        Optional<List<@WithConverter(TrimmedStringConverter.class) String>> options();
    }
}
