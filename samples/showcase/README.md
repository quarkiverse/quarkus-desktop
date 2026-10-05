# Quarkus Desktop Showcase

An AWT, Java2D and Swing application of 75 pages (about 3800 checks), built with [Quarkus](https://quarkus.io) and
quarkus-desktop. It checks that a native executable renders exactly the same user interface as the JVM, and finds what
the native configuration of quarkus-desktop misses.

The pages cover AWT components and windows, Java2D, text and fonts, images, Swing components, look and feels, data
transfer and desktop integration, printing, accessibility, JavaBeans and sound. Each page is a CDI bean in
`src/main/java/io/quarkiverse/desktop/showcase/pages/<category>`.

This directory is a Maven project of its own, not a module of the extension build.

## Requirements

- JDK 25 for JVM mode and the tools, GraalVM for JDK 25 for native executables (`GRAALVM_HOME`), and the
  [Quarkus native prerequisites](https://quarkus.io/guides/building-native-image).
- quarkus-desktop `999-SNAPSHOT` in the local Maven repository: run `./mvnw install -DskipTests` at the root of this
  repository (`../..`) first.
- Windows arm64: JVM mode only (GraalVM has no native image builder there).
- macOS native executables of the AWT and Swing variants: Quarkus 4.0 or later (until its release, a Quarkus
  `999-SNAPSHOT` built from `main`, used with `-Dquarkus.platform.version=999-SNAPSHOT`) and GraalVM 25.1 or later (the
  SWT variant does not need Quarkus 4.0). The Robot pages (`-Dshowcase.robot=true`) need the Screen Recording and
  Accessibility permissions for the terminal.

## Run

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

./mvnw package -Dnative
./target/quarkus-desktop-showcase-1.0.0-SNAPSHOT-runner
```

- `-Dawt-only` builds the AWT-only variant into `target/awt-only/`: an AWT main window and the AWT pages only, with
  `quarkus-desktop-awt` alone.
- `-Dswt` builds the SWT variant into `target/swt/` (see [SWT variant](#swt-variant)).
- `-Dshowcase.pages=overview-environment,j2d-` (page ids, a trailing `-` is a prefix) and `-Dshowcase.categories=java2d,text`
  select pages.

## Compare JVM and native rendering

In snapshot mode, the application renders every page to a PNG file, writes the checks and the environment of every page
to `report.json`, then exits. `tools/Cycle.java` runs a whole comparison: the JVM build and snapshots, the native build
and snapshots, and the comparison. Run it with the `java` of GraalVM:

```bash
$GRAALVM_HOME/bin/java tools/Cycle.java mine
```

The verdict is the first line of `comparison/logs-mine/compare.txt` (`MATCH` or `MISMATCH`), with the details in
`comparison/diff-mine/summary.txt` and `index.html`. Every page must be identical, except the expected differences of
`overview-native-limits`. Useful options:

- `--awt-only`: the AWT-only variant;
- `--swt`: the SWT variant;
- `--exact`: a native build with `--exact-reachability-metadata`, reporting every access missing from the metadata;
- `--trace`: a JVM run under the GraalVM tracing agent, and `tools/MetadataDiff.java` to compare its metadata with
  quarkus-desktop;
- `--jvm-only`: two JVM runs (no native image builder);
- `--pages=...`, `--hidpi` (the real display scale), `--pipeline=gdi|opengl|x11` (another Java2D pipeline).

`tools/Snapshot.java` and `tools/Compare.java` run the single steps.

## SWT variant

`-Dswt` builds a third variant into `target/swt/`: an SWT main window and 16 SWT pages (688 checks on Windows: the
count depends on the platform), with `quarkus-desktop-swt` alone (Eclipse SWT, no AWT or Swing extension). Only
`src/main/java/io/quarkiverse/desktop/showcase/swt` (and the toolkit neutral `core/Check.java` and `core/Json.java`) is
compiled, and only `application.properties` and the SWT assets of `src/main/resources/showcase/swt` are packaged. Its
pages are CDI beans implementing `swt/core/SwtPage`, in `swt/pages/<category>`; quarkus-desktop-swt runs them on the
main thread, the thread of the SWT `Display`.

| Category | Pages |
|---|---|
| Overview | `swt-environment`, `swt-native-limits` |
| Widgets | `swt-buttons`, `swt-inputs`, `swt-lists-tables-trees`, `swt-containers` |
| Custom Widgets | `swt-custom-widgets` |
| Layouts | `swt-layouts` |
| Graphics | `swt-graphics-shapes`, `swt-graphics-advanced` |
| Text & Fonts | `swt-text-fonts` |
| Images | `swt-images` |
| Data Transfer & Desktop | `swt-data-transfer`, `swt-desktop` |
| Printing | `swt-printing` |
| Accessibility | `swt-accessibility` |

```bash
./mvnw package -Dswt
java -jar target/swt/quarkus-app/quarkus-run.jar

$GRAALVM_HOME/bin/java tools/Cycle.java mine-swt --swt
```

Its snapshot mode writes the same `report.json` and images as the other variants, compared by the same tools: every
page must be identical, except the expected differences of `swt-native-limits`. With `--swt`, the tools pin
`-Dswt.autoScale=100` (instead of `-Dsun.java2d.uiScale=1`; `--hidpi` keeps the zoom of the monitor) and add
`-XstartOnFirstThread` to the JVM runs on macOS. `--swt` is not compatible with `--awt-only`, `--pipeline` and
`--trace`.

The cycles of the SWT variant match, with `--exact` too, on Windows 11, on macOS 27.0 (Apple silicon), and on Linux x64
in Docker (the image above, `docker/linux`), with no failed check. Where SWT behaves differently on a platform
(`Text.DELIMITER` and `Path.contains` with `SWT.FILL_EVEN_ODD` on Cocoa, `Transform.shear` on Cocoa and GTK, the client
area of a `Group` or a `TabFolder`, the rows of a virtual `Table` and the toolbars that cannot wrap on GTK, the glyph
metrics of Core Text...), the checks of the pages expect the value of that platform, with a comment naming the cause.
On Linux, whether a new shell takes the focus depends on the window manager: that check is informational there.

On Windows, the snapshots copy the pixels from the windows (`GC.copyArea`) instead of `Control.print`, whose
`PrintWindow` now and then misses parts of a window; a window holds the pixels of its parts on the screen only, so a
page taller than the screen is captured by tiles. Every image is taken once three renders in a row give the same
pixels. During a snapshot run, the clicks and the mouse wheel over its windows are ignored.

## Linux in Docker

`docker/linux` is a Linux environment with GraalVM, a virtual display, a window manager and a system tray:

```bash
docker build -t quarkus-desktop-showcase-linux docker/linux
docker volume create quarkus-desktop-linux-m2
docker run --rm --init -v "$PWD/../..":/quarkus-desktop -w /quarkus-desktop \
    -v quarkus-desktop-linux-m2:/root/.m2 quarkus-desktop-showcase-linux ./mvnw -B install -DskipTests
docker run --rm --init -v "$PWD":/showcase -v quarkus-desktop-linux-m2:/root/.m2 quarkus-desktop-showcase-linux \
    java tools/Cycle.java linux
```

## Third-party assets

The fonts, sound banks and other test assets that were not created for this project are listed with their license in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
