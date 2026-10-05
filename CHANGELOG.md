# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

### Added

- Desktop SWT extension: SWT applications in JVM mode and as native executables on Windows x64 and
  Linux x64 (macOS with `-XstartOnFirstThread` in JVM mode, and native executables running the event
  loop on the first thread, not verified on a Mac yet).
- SWT application model: `SwtStartupEvent`, `SwtLifecycle`, `UiThread`, `UiThreadExecutor`,
  `@RunOnUiThread`, `WidgetBeans`, `QuitRequest` (the quit requests of the `Display`, which an
  observer can cancel), exit on last shell closed (`quarkus.desktop.swt.exit-on-last-shell-closed`),
  the application name given to SWT (`quarkus.desktop.swt.application-name`), the uncaught
  exceptions of the user interface thread in the Quarkus log, and build checks for widget beans and
  `@RunOnUiThread` methods.
- SWT shutdown: the user interface stops before the `ShutdownEvent` observers, whatever stops the
  application (its shells are disposed, the tasks already queued run, then the `Display` is
  disposed). The tasks queued for a user interface that never runs, or that stopped, are rejected.
- Dev mode for SWT applications on Windows and Linux (not verified yet).
- SWT native executables: the JNI metadata computed from the SWT jar of the application, the run
  time initialization of SWT and of the classes whose static initializer uses it, the native
  libraries of SWT embedded in the executable or copied next to it
  (`quarkus.desktop.swt.native-libraries`), the Windows GUI subsystem, a check of the SWT jar of the
  target platform, and support for `--exact-reachability-metadata`.
- An SWT variant of the showcase (`-Dswt`).

### Changed

- The Desktop AWT extension does not park the first thread of macOS native executables when the
  application has an SWT user interface: SWT runs its event loop there (not verified yet).

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
