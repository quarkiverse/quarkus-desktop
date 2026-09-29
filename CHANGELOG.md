# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
