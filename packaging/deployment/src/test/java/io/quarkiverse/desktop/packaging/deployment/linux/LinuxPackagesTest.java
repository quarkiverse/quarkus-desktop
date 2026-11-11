package io.quarkiverse.desktop.packaging.deployment.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.IconSet;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TargetOs;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * The files that the Linux packagers generate and the commands that they run : the desktop entry, the control file, the
 * spec file, the metadata of Flatpak and the project of Snapcraft.
 */
class LinuxPackagesTest {

    @Test
    void theDesktopEntry() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("description", "Edits\ndocuments", "linux.categories",
                "Office,TextEditor", "linux.startup-wm-class", "acme-editor"));
        String entry = LinuxDesktop.desktopEntry(TestConfig.app(config), config.linux(), "/opt/acme-editor/acme-editor",
                "acme-editor");
        assertEquals("""
                [Desktop Entry]
                Type=Application
                Name=Acme Editor
                Comment=Edits documents
                Exec=/opt/acme-editor/acme-editor
                Icon=acme-editor
                Terminal=false
                Categories=Office;TextEditor;
                StartupWMClass=acme-editor
                """, entry);
    }

    /**
     * No comment that repeats the name : the description defaults to the name.
     */
    @Test
    void theDesktopEntryWithoutDescription() {
        DesktopPackagingConfig config = TestConfig.defaults();
        assertEquals("""
                [Desktop Entry]
                Type=Application
                Name=Acme Editor
                Exec=/opt/acme-editor/acme-editor
                Icon=acme-editor
                Terminal=false
                Categories=Utility;
                """, LinuxDesktop.desktopEntry(TestConfig.app(config), config.linux(), "/opt/acme-editor/acme-editor",
                "acme-editor"));
        // desktop-file-validate compares them without case
        DesktopPackagingConfig lowercase = TestConfig.of(Map.of("description", "acme editor"));
        assertFalse(LinuxDesktop.desktopEntry(TestConfig.app(lowercase), lowercase.linux(), "acme-editor", "acme-editor")
                .contains("Comment="));
    }

    @Test
    void theInstallationDirectory() {
        assertEquals("/opt/acme-editor", LinuxDesktop.installPath("/opt", "acme-editor"));
        assertEquals("/opt/acme-editor", LinuxDesktop.installPath("opt/", "acme-editor"));
        assertEquals("/usr/local/lib/acme-editor", LinuxDesktop.installPath("/usr/local/lib", "acme-editor"));
        assertEquals("/opt/acme_corp-2.0+x/acme-editor", LinuxDesktop.installPath("/opt/acme_corp-2.0+x", "acme-editor"));
        // a path that the desktop entry, the spec file or the tree of the package would take for another one
        for (String directory : List.of("/", "", "/opt/Acme Corp", "/opt/100%", "/opt/../etc", "/opt/./x", "/opt//x",
                "/opt/a;b", "/opt/$HOME")) {
            PackagingException e = assertThrows(PackagingException.class,
                    () -> LinuxDesktop.installPath(directory, "acme-editor"));
            assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.linux.install-directory=" + directory + " :"),
                    e.getMessage());
        }
    }

    @Test
    void theControlFileOfTheDebianPackage() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme", "homepage", "https://acme.org",
                "description", "Edits documents", "linux.release", "2"));
        String control = DebPackager.control(TestConfig.app(config), config.linux(), Arch.X64, 1234,
                List.of("libc6 (>= 2.34)", "libx11-6"));
        assertEquals("""
                Package: acme-editor
                Version: 1.2.3~SNAPSHOT-2
                Section: utils
                Priority: optional
                Architecture: amd64
                Maintainer: Acme <acme-editor@localhost>
                Installed-Size: 1234
                Depends: libc6 (>= 2.34), libx11-6
                Homepage: https://acme.org
                Description: Edits documents
                """, control);

        DesktopPackagingConfig maintained = TestConfig.of(Map.of("linux.deb.maintainer", "Jane Doe <jane@acme.org>",
                "linux.deb.section", "editors"));
        String other = DebPackager.control(TestConfig.app(maintained), maintained.linux(), Arch.ARM64, 1, List.of());
        assertTrue(other.contains("Maintainer: Jane Doe <jane@acme.org>\n"), other);
        assertTrue(other.contains("Section: editors\n"), other);
        assertTrue(other.contains("Architecture: arm64\n"), other);
        assertFalse(other.contains("Depends"), other);
    }

    @Test
    void theDependenciesOfTheSharedLibraries() {
        assertEquals(List.of("libc6 (>= 2.34)", "libx11-6", "zlib1g (>= 1:1.1.4)"), DebPackager.parseShlibDepends("""
                dpkg-shlibdeps: warning: can't extract name and version from library name 'libjvm.so'
                shlibs:Depends=libc6 (>= 2.34), libx11-6, zlib1g (>= 1:1.1.4)
                """));
        assertEquals(List.of(), DebPackager.parseShlibDepends("shlibs:Depends=\n"));
        // no variable when the files are linked with no shared library : a statically linked executable
        assertEquals(List.of(), DebPackager.parseShlibDepends("""
                objdump: /app/bin/app: not a dynamic object
                dpkg-shlibdeps: warning: couldn't parse dynamic symbol definition: no symbols
                """));
        // the packages that the 64-bit time_t transition of Ubuntu 24.04 renamed, with their former names
        assertEquals(List.of("libasound2t64 (>= 1.0.16) | libasound2 (>= 1.0.16)", "libc6 (>= 2.34)",
                "libcurl3t64-gnutls | libcurl3-gnutls", "libx11-6"),
                DebPackager.parseShlibDepends(
                        "shlibs:Depends=libasound2t64 (>= 1.0.16), libc6 (>= 2.34), libcurl3t64-gnutls, libx11-6\n"));
    }

    /**
     * The package of a JVM application built on Ubuntu 24.04 installs on Ubuntu 22.04 and Debian 12, whose library
     * packages have the names before the 64-bit time_t transition.
     */
    @Test
    void theFormerNamesOfTheLibraryPackages() {
        assertEquals("libglib2.0-0t64 (>= 2.75.3) | libglib2.0-0 (>= 2.75.3)",
                DebPackager.withFormerNames("libglib2.0-0t64 (>= 2.75.3)"));
        assertEquals("libgtk-3-0t64 | libgtk-3-0", DebPackager.withFormerNames("libgtk-3-0t64"));
        // already there
        assertEquals("libasound2t64 (>= 1.0.16) | libasound2 (>= 1.0.16)",
                DebPackager.withFormerNames("libasound2t64 (>= 1.0.16) | libasound2 (>= 1.0.16)"));
        assertEquals("libasound2t64 | libasound2", DebPackager.withFormerNames("libasound2t64 | libasound2"));
        // not renamed : no t64 after a version
        for (String dependency : List.of("libc6 (>= 2.34)", "zlib1g (>= 1:1.2.2)", "libx11-6", "libxt64")) {
            assertEquals(dependency, DebPackager.withFormerNames(dependency));
        }
    }

    /**
     * One run of dpkg-shlibdeps for all the binaries, and when it fails (a library that the machine of the build does
     * not have), one run for each binary, then one for the binaries whose run succeeded.
     */
    @Test
    void theDependenciesOfTheBinariesThatDpkgShlibdepsFinds() {
        List<List<String>> runs = new ArrayList<>();
        // fails for a run with B, the merged dependencies of the files otherwise
        Function<List<String>, Optional<List<String>>> depends = files -> {
            runs.add(files);
            return files.contains("B") ? Optional.empty() : Optional.of(List.of("deps of " + String.join("+", files)));
        };
        List<String> missing = new ArrayList<>();
        assertEquals(List.of("deps of A+C"), DebPackager.shlibDepends(List.of("A", "C"), depends, missing));
        assertEquals(List.of(List.of("A", "C")), runs);
        assertEquals(List.of(), missing);

        runs.clear();
        assertEquals(List.of("deps of A+C"), DebPackager.shlibDepends(List.of("A", "B", "C"), depends, missing));
        assertEquals(List.of(List.of("A", "B", "C"), List.of("A"), List.of("B"), List.of("C"), List.of("A", "C")), runs);
        assertEquals(List.of("B"), missing);

        // the run of a single binary is not repeated
        runs.clear();
        missing.clear();
        assertEquals(List.of("deps of A"), DebPackager.shlibDepends(List.of("A", "B"), depends, missing));
        assertEquals(List.of(List.of("A", "B"), List.of("A"), List.of("B")), runs);
        assertEquals(List.of("B"), missing);
        runs.clear();
        missing.clear();
        assertEquals(List.of(), DebPackager.shlibDepends(List.of("B"), depends, missing));
        assertEquals(List.of(List.of("B")), runs);
        assertEquals(List.of("B"), missing);

        // the dependencies of each binary when the last run fails
        Function<List<String>, Optional<List<String>>> separately = files -> files.size() == 1
                ? Optional.of(List.of("libc6", "lib" + files.get(0)))
                : Optional.empty();
        missing.clear();
        assertEquals(List.of("libc6", "libA", "libC"), DebPackager.shlibDepends(List.of("A", "C"), separately, missing));
        assertEquals(List.of(), missing);
    }

    /**
     * xz, which every dpkg reads : dpkg-deb compresses with zstd on Ubuntu, which the dpkg of Debian 11 cannot read.
     */
    @Test
    void theCommandOfDpkgDeb() {
        Path root = Path.of("work", "root");
        Path deb = Path.of("acme-editor_1.2.3-1_amd64.deb");
        assertEquals(List.of("dpkg-deb", "-Zxz", "--root-owner-group", "--build", root.toString(), deb.toString()),
                DebPackager.buildCommand(Path.of("dpkg-deb"), root, deb));
    }

    @Test
    void theSpecFileOfTheRpmPackage() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("vendor", "Acme", "description", "Edits 100% of documents",
                "linux.rpm.license", "Apache-2.0", "linux.rpm.requires", "gtk3,libX11"));
        AppInfo app = TestConfig.app(config);
        String spec = RpmPackager.spec(app, config.linux(), Path.of("/work/root"), "/opt/acme-editor",
                List.of("libawt.so", "libjvm.so", "libswt-gtk-4970r1.so"));
        assertTrue(spec.contains("Name: acme-editor\nVersion: 1.2.3~SNAPSHOT\nRelease: 1\n"), spec);
        assertTrue(spec.contains("Summary: Edits 100%% of documents\n"), spec);
        assertTrue(spec.contains("License: Apache-2.0\n"), spec);
        assertTrue(spec.contains("Requires: gtk3\nRequires: libX11\n"), spec);
        // the libraries of the application are neither provided nor required
        assertTrue(spec.contains("%global __provides_exclude_from ^/opt/acme-editor/.*$\n"), spec);
        assertTrue(spec.contains("%global __requires_exclude ^(libawt[.]so|libjvm[.]so|libswt-gtk-4970r1[.]so)([(].*)?$\n"),
                spec);
        assertTrue(spec.contains("%files\n%defattr(-,root,root,-)\n/opt/acme-editor\n/usr/bin/acme-editor\n"
                + "/usr/share/applications/acme-editor.desktop\n/usr/share/icons/hicolor/16x16/apps/acme-editor.png\n"), spec);
        assertTrue(spec.contains("/usr/share/icons/hicolor/256x256/apps/acme-editor.png\n"
                + "/usr/share/icons/hicolor/512x512/apps/acme-editor.png\n"), spec);
        // no glob, which rpmbuild splits where the path of the build root has a space
        assertFalse(spec.substring(spec.indexOf("%files\n")).contains("*"), spec);
        assertFalse(spec.contains("AutoReq: no"), spec);
        // a %clean stage that does nothing : an empty one would not replace the default one
        assertTrue(spec.contains("\n%clean\n:\n\n%files\n"), spec);
        assertFalse(spec.contains("%license"), spec);
        assertEquals("acme-editor-1.2.3~SNAPSHOT-1.x86_64.rpm", RpmPackager.fileName(app, config.linux(), Arch.X64));

        DesktopPackagingConfig manual = TestConfig.of(Map.of("linux.rpm.auto-requires", "false"));
        String other = RpmPackager.spec(TestConfig.app(manual), manual.linux(), Path.of("/work/root"), "/opt/acme-editor",
                List.of());
        assertTrue(other.contains("AutoReq: no\n"), other);
        assertFalse(other.contains("__requires_exclude "), other);
    }

    /**
     * The license file is installed as the license of the package, in a directory that the package owns.
     */
    @Test
    void theLicenseOfTheRpmPackage() {
        DesktopPackagingConfig config = TestConfig.defaults();
        AppInfo app = new AppInfo("Acme Editor", "acme-editor", "org.acme.AcmeEditor", "1.2.3", "Acme", "Edits documents",
                Optional.empty(), Optional.empty(), Optional.of(Path.of("LICENSE")));
        String spec = RpmPackager.spec(app, config.linux(), Path.of("/work/root"), "/opt/acme-editor", List.of());
        assertTrue(spec.endsWith("/usr/share/icons/hicolor/512x512/apps/acme-editor.png\n"
                + "%dir /usr/share/licenses/acme-editor\n%license /usr/share/licenses/acme-editor/LICENSE\n"), spec);
    }

    /**
     * A library whose file name has its full version is required by its soname, the name of a link to it : the names of
     * the links are libraries of the application too.
     */
    @Test
    void theLibrariesOfTheRpmPackage(@TempDir Path directory) throws IOException {
        Path application = Files.createDirectories(directory.resolve("opt/acme-editor"));
        Path lib = Files.createDirectories(application.resolve("lib"));
        byte[] elf = { 0x7f, 'E', 'L', 'F', 2, 1, 1, 0 };
        Files.write(lib.resolve("libfoo.so.1.2.3"), elf);
        Files.write(lib.resolve("libbar.so"), elf);
        Files.write(lib.resolve("fake.so"), "not a library".getBytes(StandardCharsets.UTF_8));
        Files.write(lib.resolve("notes.so.txt"), "not a library".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("libsystem.so.3"), elf);
        link(lib.resolve("libfoo.so.1"), Path.of("libfoo.so.1.2.3"));
        link(lib.resolve("libfoo.so"), Path.of("libfoo.so.1"));
        // a link to nothing, and a link to a library of the system
        link(lib.resolve("libgone.so.2"), Path.of("libgone.so.2.0"));
        link(lib.resolve("libsystem.so.3"), directory.resolve("libsystem.so.3"));
        List<String> libraries = RpmPackager.libraries(application);
        assertEquals(List.of("libbar.so", "libfoo.so", "libfoo.so.1", "libfoo.so.1.2.3"), libraries);
        String spec = RpmPackager.spec(TestConfig.app(TestConfig.defaults()), TestConfig.defaults().linux(), directory,
                "/opt/acme-editor", libraries);
        assertTrue(spec.contains(
                "%global __requires_exclude ^(libbar[.]so|libfoo[.]so|libfoo[.]so[.]1|libfoo[.]so[.]1[.]2[.]3)([(].*)?$\n"),
                spec);
    }

    /**
     * Creates a symbolic link, or skips the test where links need a privilege (Windows).
     */
    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows, without the privilege of creating symbolic links
            Assumptions.abort("No symbolic links : " + e);
        }
    }

    /**
     * Without the %clean stage of rpmbuild, whose command does not quote the build root.
     */
    @Test
    void theCommandOfRpmbuild() {
        Path top = Path.of("work", "rpmbuild");
        Path spec = Path.of("work", "acme-editor.spec");
        assertEquals(List.of("rpmbuild", "-bb", "--noclean", "--define", "_topdir " + top, "--target", "aarch64",
                spec.toString()), RpmPackager.command(Path.of("rpmbuild"), top, Arch.ARM64, spec));
    }

    @Test
    void theEntryPointOfTheAppImage() {
        String appRun = AppImagePackager.appRun("acme-editor", "bin/acme-editor");
        assertTrue(appRun.startsWith("#!/bin/sh\n"), appRun);
        assertTrue(appRun.contains("exec \"$HERE/usr/lib/acme-editor/bin/acme-editor\" \"$@\"\n"), appRun);
    }

    /**
     * The version of the application, whatever the VERSION variable of the build, and a temporary directory of the
     * build for the extraction of appimagetool.
     */
    @Test
    void theEnvironmentOfAppimagetool() {
        Path tmp = Path.of("work", "tmp");
        assertEquals(Map.of("ARCH", "x86_64", "VERSION", "1.2.3-SNAPSHOT", "APPIMAGE_EXTRACT_AND_RUN", "1", "TMPDIR",
                tmp.toString()), AppImagePackager.environment("x86_64", "1.2.3-SNAPSHOT", tmp));
    }

    @Test
    void theCommandOfAppimagetool() {
        Path appDir = Path.of("work", "Acme-Editor.AppDir");
        Path appImage = Path.of("Acme-Editor-1.2.3-x86_64.AppImage");
        assertEquals(List.of("appimagetool", "--no-appstream", appDir.toString(), appImage.toString()),
                AppImagePackager.command(Path.of("appimagetool"), Optional.empty(), appDir, appImage));
        // a runtime of the build rather than the latest one, downloaded from GitHub
        Path runtime = Path.of("runtime-x86_64");
        List<String> command = AppImagePackager.command(Path.of("appimagetool"), Optional.of(runtime), appDir, appImage);
        assertEquals(List.of("appimagetool", "--no-appstream", "--runtime-file", runtime.toString(), appDir.toString(),
                appImage.toString()), command);
    }

    @Test
    void theFlatpakApplication() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("linux.flatpak.runtime", "org.gnome.Platform",
                "linux.flatpak.sdk", "org.gnome.Sdk", "linux.flatpak.runtime-version", "50"));
        assertEquals("org.acme.AcmeEditor", FlatpakPackager.applicationId(TestConfig.app(config)));
        assertEquals("""
                [Application]
                name=org.acme.AcmeEditor
                runtime=org.gnome.Platform/aarch64/50
                sdk=org.gnome.Sdk/aarch64/50
                """, FlatpakPackager.metadata("org.acme.AcmeEditor", config.linux().flatpak(), Arch.ARM64));
        // a branch of the runtime that Flathub supports (24.08 reached its end of life)
        assertEquals("""
                [Application]
                name=org.acme.AcmeEditor
                runtime=org.freedesktop.Platform/x86_64/25.08
                sdk=org.freedesktop.Sdk/x86_64/25.08
                """, FlatpakPackager.metadata("org.acme.AcmeEditor", TestConfig.defaults().linux().flatpak(), Arch.X64));
        // two elements, or a dash before the last element
        assertThrows(PackagingException.class,
                () -> FlatpakPackager.applicationId(TestConfig.app(TestConfig.of(Map.of("identifier", "acme.editor")))));
        assertThrows(PackagingException.class, () -> FlatpakPackager
                .applicationId(TestConfig.app(TestConfig.of(Map.of("identifier", "org.acme-corp.editor")))));
        // an element that starts with a digit, which the other formats accept
        assertThrows(PackagingException.class, () -> FlatpakPackager
                .applicationId(TestConfig.app(TestConfig.of(Map.of("identifier", "com.1password.editor")))));
        assertThrows(PackagingException.class, () -> FlatpakPackager
                .applicationId(TestConfig.app(TestConfig.of(Map.of("identifier", "org.acme.3DViewer")))));
        assertEquals("org._7zip.Editor", FlatpakPackager
                .applicationId(TestConfig.app(TestConfig.of(Map.of("identifier", "org._7zip.Editor")))));
        String metainfo = LinuxDesktop.metainfo(TestConfig.app(), "org.acme.AcmeEditor", "org.acme.AcmeEditor.desktop");
        assertTrue(metainfo.contains("<id>org.acme.AcmeEditor</id>"), metainfo);
        assertTrue(metainfo.contains("<summary>Edits &lt;documents&gt;</summary>"), metainfo);
        assertTrue(metainfo.contains("<developer id=\"org.acme\">\n    <name>Acme &amp; Sons</name>\n  </developer>\n"),
                metainfo);
        assertTrue(metainfo.contains("<launchable type=\"desktop-id\">org.acme.AcmeEditor.desktop</launchable>"), metainfo);
        // a release needs a date, which the build does not know
        assertFalse(metainfo.contains("<release"), metainfo);
    }

    /**
     * Without the sandbox of the icon validator, which cannot create its namespaces in a container.
     */
    @Test
    void theCommandOfTheFlatpakExport() {
        Path repository = Path.of("work", "repo");
        Path build = Path.of("work", "build");
        List<String> command = FlatpakPackager.exportCommand(Path.of("flatpak"), "aarch64", repository, build, "stable");
        assertEquals(List.of("flatpak", "build-export", "--disable-sandbox", "--arch=aarch64", repository.toString(),
                build.toString(), "stable"), command);
    }

    @Test
    void theSnapcraftProject() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("description", "Edits the user's documents", "homepage",
                "https://acme.org", "linux.snap.stage-packages", "libxtst6,libxi6"));
        AppInfo app = TestConfig.app(config);
        assertEquals("acme-editor", SnapPackager.snapName(app));
        String yaml = SnapPackager.snapcraftYaml(app, config.linux().snap(), Arch.X64, "acme-editor", "bin/acme-editor");
        assertTrue(yaml.contains("name: 'acme-editor'\ntitle: 'Acme Editor'\nversion: '1.2.3~SNAPSHOT'\n"), yaml);
        assertTrue(yaml.contains("summary: 'Edits the user''s documents'\n"), yaml);
        assertTrue(yaml.contains("base: core24\ngrade: stable\nconfinement: strict\n"), yaml);
        assertTrue(yaml.contains("platforms:\n  amd64:\n"), yaml);
        assertTrue(yaml.contains("apps:\n  'acme-editor':\n    command: lib/acme-editor/bin/acme-editor\n"
                + "    extensions: [gnome]\n    plugs:\n      - home\n"), yaml);
        assertTrue(yaml.contains("parts:\n  application:\n    plugin: dump\n    source: payload\n"
                + "    stage-packages:\n      - libxtst6\n      - libxi6\n"), yaml);
        // a name that is not a string for YAML 1.1 when it is not quoted : true
        String on = SnapPackager.snapcraftYaml(app, config.linux().snap(), Arch.X64, "on", "bin/on");
        assertTrue(on.contains("name: 'on'\n"), on);
        assertTrue(on.contains("apps:\n  'on':\n    command: lib/on/bin/on\n"), on);

        AppInfo dotted = TestConfig.app(TestConfig.of(Map.of("linux.package-name", "acme.editor")));
        assertThrows(PackagingException.class, () -> SnapPackager.snapName(dotted));
    }

    /**
     * A classic snap has no extension : the gnome extension supports the strict and devmode confinements only.
     */
    @Test
    void theExtensionsOfTheSnap() {
        AppInfo app = TestConfig.app(TestConfig.defaults());
        DesktopPackagingConfig classic = TestConfig.of(Map.of("linux.snap.confinement", "classic",
                "linux.snap.extensions", ""));
        String yaml = SnapPackager.snapcraftYaml(app, classic.linux().snap(), Arch.X64, "acme-editor", "bin/acme-editor");
        assertTrue(yaml.contains("confinement: classic\n"), yaml);
        assertTrue(yaml.contains("    command: lib/acme-editor/bin/acme-editor\n    plugs:\n"), yaml);
        assertFalse(yaml.contains("extensions:"), yaml);

        DesktopPackagingConfig gnome = TestConfig.of(Map.of("linux.snap.confinement", "classic"));
        PackagingException e = assertThrows(PackagingException.class, () -> SnapPackager.snapcraftYaml(app,
                gnome.linux().snap(), Arch.X64, "acme-editor", "bin/acme-editor"));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.linux.snap.confinement=classic :"), e.getMessage());
        assertTrue(e.getMessage().contains("set quarkus.desktop.packaging.linux.snap.extensions to an empty value"),
                e.getMessage());

        DesktopPackagingConfig kde = TestConfig.of(Map.of("linux.snap.extensions", "kde-neon-6"));
        assertTrue(SnapPackager.snapcraftYaml(app, kde.linux().snap(), Arch.X64, "acme-editor", "bin/acme-editor")
                .contains("    extensions: [kde-neon-6]\n"));
    }

    /**
     * The title has 2 to 40 characters, and the version at most 32, which end with a letter, a digit, + or ~.
     */
    @Test
    void theTitleAndTheVersionOfTheSnap() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("name",
                "The Acme Corporation Document Editor Professional Edition", "version",
                "1.0.0-SNAPSHOT-20261111.1234567.abc", "linux.package-name", "acme-editor"));
        String yaml = SnapPackager.snapcraftYaml(TestConfig.app(config), config.linux().snap(), Arch.X64, "acme-editor",
                "bin/acme-editor");
        assertTrue(yaml.contains("title: 'The Acme Corporation Document Editor Pro'\n"), yaml);
        // 1.0.0~SNAPSHOT.20261111.1234567.abc, cut after the period of .abc
        assertTrue(yaml.contains("version: '1.0.0~SNAPSHOT.20261111.1234567'\n"), yaml);

        // characters beyond the 16 bits of a char : not cut in the middle of one
        String clef = Character.toString(0x1D11E);
        String clefs = clef.repeat(41);
        DesktopPackagingConfig music = TestConfig.of(Map.of("name", clefs, "version", "1.0_",
                "linux.package-name", "acme-editor"));
        String other = SnapPackager.snapcraftYaml(TestConfig.app(music), music.linux().snap(), Arch.X64, "acme-editor",
                "bin/acme-editor");
        assertTrue(other.contains("title: '" + clef.repeat(40) + "'\n"), other);
        assertTrue(other.contains("version: '1.0'\n"), other);

        DesktopPackagingConfig x = TestConfig.of(Map.of("name", "X", "linux.package-name", "acme-editor"));
        String untitled = SnapPackager.snapcraftYaml(TestConfig.app(x), x.linux().snap(), Arch.X64, "acme-editor",
                "bin/acme-editor");
        assertFalse(untitled.contains("title:"), untitled);
    }

    /**
     * snap pack refuses the files of meta/gui that are not readable by everyone, whatever the umask of the build :
     * here, files of the project written before with the permissions of a umask of 077.
     */
    @Test
    void theFilesOfTheSnapcraftProject(@TempDir Path directory) throws IOException {
        Path project = directory.resolve("project");
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null,
                "No POSIX permissions");
        Path image = Files.createDirectories(directory.resolve("image/bin"));
        Files.writeString(image.resolve("acme-editor"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(image.resolve("acme-editor"), PosixFilePermissions.fromString("rwx------"));
        for (String file : List.of("snap/snapcraft.yaml", "snap/gui/acme-editor.desktop", "snap/gui/acme-editor.png")) {
            Files.createDirectories(project.resolve(file).getParent(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.createFile(project.resolve(file),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        DesktopPackagingConfig config = TestConfig.defaults();
        PackagingContext context = new PackagingContext(config, TestConfig.app(config), TargetOs.LINUX, () -> Arch.X64,
                new Tools(Map.of()), IconSet.generic(), directory, directory.resolve("packaging"));
        SnapPackager.writeProject(context, new ApplicationImage(directory.resolve("image"), "bin/acme-editor", true),
                project, "acme-editor");
        for (String file : List.of("snap/snapcraft.yaml", "snap/gui/acme-editor.desktop", "snap/gui/acme-editor.png")) {
            assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(project.resolve(file))),
                    file);
        }
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(project.resolve("snap/gui"))));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                project.resolve("payload/lib/acme-editor/bin/acme-editor"))));
    }

    /**
     * The LXD container of the build is deleted afterwards, unless Snapcraft builds on the host.
     */
    @Test
    void theBuildEnvironmentOfTheSnap() {
        assertTrue(SnapPackager.managed(Optional.empty()));
        assertTrue(SnapPackager.managed(Optional.of(List.of("--use-lxd"))));
        assertFalse(SnapPackager.managed(Optional.of(List.of("--destructive-mode"))));
        assertFalse(SnapPackager.managed(Optional.of(List.of("--verbosity=debug", "--destructive-mode"))));
    }
}
