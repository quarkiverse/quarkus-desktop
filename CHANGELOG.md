# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

## 0.2.1 - 2026-10-06

- Publishes the changes of 0.2.0, which did not reach Maven Central (its javadoc jars were missing),
  with Quarkus 3.40.1.

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
