package io.quarkiverse.desktop.packaging.deployment.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;

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
        assertTrue(spec.contains("%files\n%defattr(-,root,root,-)\n/opt/acme-editor\n/usr/bin/acme-editor\n"), spec);
        assertTrue(spec.contains("/usr/share/icons/hicolor/*/apps/acme-editor.png\n"), spec);
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
        assertTrue(spec.endsWith("/usr/share/icons/hicolor/*/apps/acme-editor.png\n%dir /usr/share/licenses/acme-editor\n"
                + "%license /usr/share/licenses/acme-editor/LICENSE\n"), spec);
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
                "linux.flatpak.sdk", "org.gnome.Sdk", "linux.flatpak.runtime-version", "48"));
        assertEquals("org.acme.AcmeEditor", FlatpakPackager.applicationId(TestConfig.app(config)));
        assertEquals("""
                [Application]
                name=org.acme.AcmeEditor
                runtime=org.gnome.Platform/aarch64/48
                sdk=org.gnome.Sdk/aarch64/48
                """, FlatpakPackager.metadata("org.acme.AcmeEditor", config.linux().flatpak(), Arch.ARM64));
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
