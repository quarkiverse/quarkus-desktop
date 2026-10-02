# Quarkus Desktop

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.desktop/quarkus-desktop-parent?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.desktop/quarkus-desktop-parent) <!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
[![All Contributors](https://img.shields.io/badge/all_contributors-2-orange.svg?style=flat-square)](#contributors-)
<!-- ALL-CONTRIBUTORS-BADGE:END -->

Quarkus extensions to build AWT and Swing desktop (GUI) applications, in JVM mode and as GraalVM native executables
(Windows x64, Linux x64 and arm64, and macOS on Apple silicon; JVM mode only on Windows arm64). Native executables for
macOS need Quarkus 4.0 or later, whose quarkus-awt supports macOS
([Enable quarkus-awt on macOS](https://github.com/quarkusio/quarkus/pull/56979); not released yet: until then, a Quarkus
`999-SNAPSHOT` built from `main`), and GraalVM 25.1 or later.

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

## Installation

Add one of the extensions to your Quarkus application: `quarkus-desktop-swing` for Swing applications (it includes
`quarkus-desktop-awt`), or `quarkus-desktop-awt` for AWT only. With Maven, add the following dependency to your
`pom.xml`:

```xml
<dependency>
    <groupId>io.quarkiverse.desktop</groupId>
    <artifactId>quarkus-desktop-swing</artifactId>
    <version>${quarkus-desktop.version}</version>
</dependency>
```

With Gradle, add to your `build.gradle`:

```groovy
implementation("io.quarkiverse.desktop:quarkus-desktop-swing:${quarkusDesktopVersion}")
```

or `build.gradle.kts`:

```kotlin
implementation("io.quarkiverse.desktop:quarkus-desktop-swing:$quarkusDesktopVersion")
```

Replace the version placeholder with the latest release from
[Maven Central](https://central.sonatype.com/artifact/io.quarkiverse.desktop/quarkus-desktop-swing).

## Application model and CDI

Windows are CDI beans: a `@Singleton` window observes `DesktopStartupEvent`, fired on the event dispatch thread once the
application started (after the look and feel is set), and gets `@Inject` and `@ConfigProperty` like any bean. No
`@QuarkusMain` is needed, and closing the last window exits the application through Quarkus (`ShutdownEvent` and
`@PreDestroy` run).

```java
@ApplicationScoped
public class Library {

    public List<String> titles() {
        return List.of("Dune", "Emma", "Ulysses");
    }
}

@Singleton // components: @Singleton or @Dependent, never a normal scope
public class MainWindow extends JFrame {

    @Inject
    Library library;

    @ConfigProperty(name = "app.title", defaultValue = "Library")
    String title;

    void open(@Observes DesktopStartupEvent event) { // on the event dispatch thread
        setTitle(title);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); // the last window closed exits the application
        add(new JScrollPane(new JList<>(library.titles().toArray(String[]::new))));
        pack();
        setVisible(true);
    }
}
```

Background work runs on a Quarkus executor and comes back to the event dispatch thread with `EdtExecutor` (also with
Mutiny `emitOn(edt)` and asynchronous CDI events); `@RunOnEdt` runs a method on the event dispatch thread whatever
thread calls it:

```java
@Singleton
public class BooksWindow extends JFrame {

    @Inject
    BookRepository repository;

    @Inject
    ManagedExecutor workers;

    @Inject
    EdtExecutor edt;

    private final DefaultListModel<String> books = new DefaultListModel<>();

    void open(@Observes DesktopStartupEvent event) {
        add(new JScrollPane(new JList<>(books)));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setVisible(true);
        CompletableFuture.supplyAsync(repository::titles, workers) // off the event dispatch thread
                .thenAcceptAsync(books::addAll, edt); // back on it
    }
}

@ApplicationScoped
public class StatusPresenter {

    @Inject
    Instance<StatusBar> statusBar; // a @Singleton component, resolved on the event dispatch thread

    @RunOnEdt
    public void show(String text) { // callable from any thread
        statusBar.get().setText(text);
    }
}

@ApplicationScoped
public class Clock {

    @Inject
    StatusPresenter status;

    @Scheduled(every = "10s") // a scheduler thread
    void tick() {
        status.show("Checked at " + LocalTime.now().withNano(0));
    }
}
```

On macOS, the application menu, Finder and Dock events of `java.awt.Desktop` are CDI events, and Cmd-Q is a
`QuitRequest` that an observer can cancel:

```java
@Singleton
public class ApplicationMenu {

    @Inject
    Instance<AboutDialog> about;

    @Inject
    Documents documents;

    void about(@Observes AboutEvent event) { // the About item of the application menu
        WindowBeans.get(about).setVisible(true); // a @Dependent dialog, destroyed when it closes
    }

    void open(@Observes OpenFilesEvent event) { // files opened from the Finder or dropped on the Dock icon
        event.getFiles().forEach(documents::open);
    }

    void quit(@Observes QuitRequest request) { // Cmd-Q
        if (documents.hasUnsavedChanges()) {
            request.cancel();
        }
    }
}
```

The build fails for the component beans that cannot work (a normal-scoped `JFrame` or `JPanel` bean: its client proxy
overrides final methods of Swing) and warns about the risky ones. The page
[Application model and CDI](https://docs.quarkiverse.io/quarkus-desktop/dev/cdi.html)
([docs/modules/ROOT/pages/cdi.adoc](docs/modules/ROOT/pages/cdi.adoc)) covers the rest: a `@QuarkusMain` that starts
the user interface itself (`quarkus.desktop.awt.startup-event.mode=manual`), `Edt.call`, windows that ask before
closing, tray applications (`quarkus.desktop.awt.exit-on-last-window-closed=false`), dev mode, tests and Quarkus FX.

## Platforms

| Platform | JVM mode | Native executable |
|---|---|---|
| Windows x64 | yes | yes (native build on Windows with Visual Studio) |
| Windows arm64 | yes | no (no GraalVM native image builder for Windows on arm64) |
| Linux x64 and arm64 | yes | yes (native build on Linux or in a container; X11 or XWayland at run time) |
| macOS on Apple silicon | yes | yes, with Quarkus 4.0 or later and GraalVM 25.1 or later (verified with the showcase on an Apple silicon Mac) |

## Showcase

The [Quarkus Desktop showcase](samples/showcase) exercises the AWT, Java2D and Swing
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
      <td align="center" valign="top" width="14.28%"><a href="https://poolborges.github.io/"><img src="https://avatars.githubusercontent.com/u/1090723?v=4?s=100" width="100px;" alt="Paulo Borges"/><br /><sub><b>Paulo Borges</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-desktop/commits?author=poolborges" title="Code">💻</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!
