# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

### Added

- Desktop Packaging (`quarkus-desktop-packaging`), a new extension that builds the installers of a desktop application
  at the end of the build, whatever its user interface toolkit: MSI (WiX), EXE (Inno Setup) and MSIX on Windows, DMG and
  PKG on macOS, deb, RPM, AppImage, Flatpak and Snap on Linux (`quarkus.desktop.packaging.formats`). It packages the
  native executable or, in JVM mode, the jar with a Java runtime (`jpackage`), derives the icons of every format from
  one PNG file, gives the Windows native executable its icon and version information, and signs the Windows and macOS
  packages (with notarization on macOS).
  The `quarkus-desktop-packaging-spi` artifact lets other extensions add the files of the native executable
  (`NativeExecutableFileBuildItem`, optional for a library that the executable loads only in some cases: the Debian and
  RPM packages do not depend on the libraries that it is linked with) and the options of the JVM
  (`PackagedJvmOptionBuildItem`), give the name that the application gives itself at run time
  (`ApplicationIdBuildItem`: the AppUserModelID of the shortcuts of the MSI package and of the setup program, the
  default `StartupWMClass` of the desktop entry of the Linux packages), and read the name of the packages
  (`PackagedApplicationNameBuildItem`). Desktop AWT and Desktop SWT declare the libraries that they copy next to the
  native executable, and Desktop SWT the name that it gives to SWT.
- File types and URL schemes of the packages (`quarkus.desktop.packaging.file-types.<type>.*`,
  `quarkus.desktop.packaging.url-schemes`): every format registers the application for them (the registry keys of the
  MSI package and the setup program, the extensions of the MSIX package, the `Info.plist` of the macOS bundle, the
  `MimeType` of the desktop entry and a shared-mime-info file on Linux). Desktop AWT and Desktop SWT fire an
  `OpenRequest` with the files and the URLs that the application is opened with, from its arguments on Windows and Linux
  and from the events of macOS, and with `single-instance` a second launch gives them to the running instance
  (`OpenedTypesBuildItem` of the SPI gives the toolkits the extensions, the schemes and the identifier).
- Automatic updates of the MSIX package (an App Installer file, `quarkus.desktop.packaging.windows.msix.update-url`) and
  of the AppImage (its update information and `.zsync` file, `quarkus.desktop.packaging.linux.appimage.update-information`).
- Desktop SWT on Windows arm64 in JVM mode (GraalVM has no native image builder for Windows on arm64: an x64
  executable, built with an x64 GraalVM, runs there under emulation).

### Changed

- Quarkus 4: the extensions are built with Quarkus 4.0.0.CR1 and need Quarkus 4.0.0.Beta3 or later (4.0.0.Beta2 was
  the first Quarkus 4 release with the services API) and Java 21 or later; their native executables need GraalVM 25.0.4
  or later, the minimum of Quarkus 4.0.0.Beta3. Their build steps register services (`ServiceRegistrar`) instead of
  recording bytecode: the run time code of the former recorders is in `DesktopAwtSupport`, `DesktopLifecycleSupport`,
  `DesktopSwingSupport` and `DesktopSwtSupport`.
- Desktop SWT depends on SWT 3.135.0 (3.132.0 before, the last SWT release that runs on Java 17).
- Desktop AWT and Desktop SWT, with the Desktop Packaging extension: when `quarkus.desktop.packaging.name` is set, the
  application takes that name by default, rather than `quarkus.application.name`. Desktop AWT gives it to the menu bar
  and the `Info.plist` of a macOS native executable (`quarkus.desktop.awt.macos.application-name`). Desktop SWT gives it
  to SWT (`quarkus.desktop.swt.application-name`), in dev mode and tests too: `Display.getAppName()`, the application
  menu of macOS, the AppUserModelID of the process on Windows, the `WM_CLASS` of the windows on Linux, and the folder
  of the data of the Edge browser on Windows (`%LOCALAPPDATA%\<name>`, with the cookies and the local storage, unless
  `org.eclipse.swt.browser.EdgeDataDir` is set) change with it. Set these properties to keep the former name.
- Desktop SWT: in a strictly confined snap, which cannot write `~/.swt`, SWT extracts its native libraries into
  `$SNAP_USER_COMMON/.swt/lib/<os>/<arch>` (`swt.library.path`, unless it is set), with or without the Desktop
  Packaging extension.

### Removed

- `DesktopAwtRuntimeInitBuildItem`: a service that uses AWT when the application starts is ordered after the service
  `DesktopAwtServices.RUNTIME_HOME` instead.

### Fixed

- The Desktop SWT documentation said that the main of Quarkus FX replaces the one of the extension: Quarkus rejected the
  two overridable producers (`ChainBuildException`). Quarkus FX, from the version that supports SWT, leaves the main to
  the extension and embeds JavaFX with `FXCanvas` (see Using JavaFX with SWT).

## 0.2.1 - 2026-10-06

- Publishes the changes of 0.2.0, which did not reach Maven Central (its javadoc jars were missing).

## 0.2.0 - 2026-10-06

### Added

- Desktop SWT extension: SWT applications in JVM mode and as native executables on Windows x64,
  Linux x64 and arm64, and macOS on Apple silicon (`-XstartOnFirstThread` in JVM mode).
- SWT application model: `SwtStartupEvent`, `SwtLifecycle`, `UiThread`, `UiThreadExecutor`,
  `@RunOnUiThread`, `WidgetBeans` and `QuitRequest`, exit on last shell closed, and the uncaught
  exceptions of the user interface thread in the Quarkus log. The user interface stops before the
  `ShutdownEvent` observers.
- SWT native executables: JNI metadata computed from the SWT jar, native libraries embedded or next
  to the executable, the Windows GUI subsystem, the macOS build version of the JDK, and
  `--exact-reachability-metadata`.
- Build and startup checks: widget beans, `@RunOnUiThread` methods, and the SWT jar of the platform.
- Dev mode for SWT applications on Windows and Linux.
- An SWT variant of the showcase (`-Dswt`).

### Changed

- Desktop AWT: on macOS, the first thread of a native executable is not parked when the Desktop SWT
  extension provides the main, and native builds take the JDK files from the JDK whose
  `native-image` Quarkus runs (also from the `PATH`).

## 0.1.0 - 2026-09-29

### Added

- Desktop AWT and Desktop Swing extensions: AWT and Swing applications in JVM mode and as native
  executables on Windows x64, Linux x64 and arm64, and macOS on Apple silicon (JVM mode only on
  Windows arm64).
- The look and feels of the JDK, with `quarkus.desktop.swing.look-and-feel` to set one at startup.
- Native executable options for Windows (DPI awareness, GUI subsystem, Visual C++ runtime) and
  macOS (Cocoa main thread, application name, Info.plist).
- Support for `--exact-reachability-metadata` and the JavaBeans API.
- CDI integration: `DesktopStartupEvent`, `DesktopLifecycle`, exit on last window closed,
  `EdtExecutor`, `Edt`, `@RunOnEdt`, `WindowBeans`, the macOS application events and
  `QuitRequest`, and build checks for component beans.
