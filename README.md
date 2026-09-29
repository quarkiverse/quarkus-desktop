# Quarkus Desktop

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.desktop/quarkus-desktop-parent?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.desktop/quarkus-desktop-parent)
[![Build](https://github.com/quarkiverse/quarkus-desktop/actions/workflows/build.yml/badge.svg)](https://github.com/quarkiverse/quarkus-desktop/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://opensource.org/licenses/Apache-2.0)

<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
[![All Contributors](https://img.shields.io/badge/all_contributors-1-orange.svg?style=flat-square)](#contributors-)
<!-- ALL-CONTRIBUTORS-BADGE:END -->

Quarkus extensions to build AWT and Swing desktop (GUI) applications, in JVM mode and as GraalVM native executables
(Windows x64, Linux x64 and arm64, and macOS on Apple silicon; JVM mode only on Windows arm64). Native executables for
macOS need the quarkus-awt of the Quarkus pull request
[Enable quarkus-awt on macOS](https://github.com/quarkusio/quarkus/pull/56979) (not in a Quarkus release yet: build
Quarkus from it) and GraalVM 25.1 or later.

| Extension | Coordinates | Description |
|---|---|---|
| Desktop AWT | `io.quarkiverse.desktop:quarkus-desktop-awt` | AWT windows, Java2D, fonts, images, printing, clipboard, drag and drop |
| Desktop Swing | `io.quarkiverse.desktop:quarkus-desktop-swing` | Swing components, the look and feels of the JDK (Metal, Nimbus, Synth, Windows, GTK, Aqua, Motif), text, printing (includes Desktop AWT) |

Please refer to the documentation available at https://docs.quarkiverse.io/quarkus-desktop/dev/index.html
(in this repository: [docs/modules/ROOT/pages](docs/modules/ROOT/pages)): installation, supported platforms, every
configuration property, native executables on Windows, Linux and macOS, HiDPI displays and Java2D pipelines, fonts and
languages, accessibility, the JavaBeans API (the bean properties of the AWT classes are registered by default, about
0.3 MB; those of the Swing classes are opt-in, 3 to 4 MB), exact reachability metadata, and the known limitations of
native executables.

## Platforms

| Platform | JVM mode | Native executable |
|---|---|---|
| Windows x64 | yes | yes (native build on Windows with Visual Studio) |
| Windows arm64 | yes | no (no GraalVM native image builder for Windows on arm64) |
| Linux x64 and arm64 | yes | yes (native build on Linux or in a container; X11 or XWayland at run time) |
| macOS on Apple silicon | yes | yes, with the quarkus-awt of the Quarkus pull request [Enable quarkus-awt on macOS](https://github.com/quarkusio/quarkus/pull/56979) and GraalVM 25.1 or later (verified on an Apple silicon Mac, see below) |

## Showcase

The [Quarkus Desktop showcase](https://github.com/Eng-Fouad/quarkus-desktop-showcase) exercises the AWT, Java2D and Swing
features of the JDK on 75 pages (about 3800 checks), and compares JVM mode and native executables pixel by pixel and
check by check (also with other Java2D pipelines, at the real display scale, and with exact reachability metadata). It is
the functional test bench of these extensions: run its cycle after changing a list of classes and resources.

## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="14.28%"><a href="https://fouad.io"><img src="https://avatars.githubusercontent.com/u/1194488?v=4?s=100" width="100px;" alt="Fouad Almalki"/><br /><sub><b>Fouad Almalki</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-desktop/commits?author=Eng-Fouad" title="Code">💻</a> <a href="#maintenance-Eng-Fouad" title="Maintenance">🚧</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!
