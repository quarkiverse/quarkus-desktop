package io.quarkiverse.desktop.packaging.deployment.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.Arch;
import io.quarkiverse.desktop.packaging.deployment.DesktopPackagingConfig;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.TestConfig;

/**
 * The files that the Linux packagers generate : the desktop entry, the control file, the spec file, the metadata of
 * Flatpak and the project of Snapcraft.
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
        assertNull(DebPackager.parseShlibDepends("dpkg-shlibdeps: error: cannot read\n"));
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
        assertEquals("acme-editor-1.2.3~SNAPSHOT-1.x86_64.rpm", RpmPackager.fileName(app, config.linux(), Arch.X64));

        DesktopPackagingConfig manual = TestConfig.of(Map.of("linux.rpm.auto-requires", "false"));
        String other = RpmPackager.spec(TestConfig.app(manual), manual.linux(), Path.of("/work/root"), "/opt/acme-editor",
                List.of());
        assertTrue(other.contains("AutoReq: no\n"), other);
        assertFalse(other.contains("__requires_exclude "), other);
    }

    @Test
    void theEntryPointOfTheAppImage() {
        String appRun = AppImagePackager.appRun("acme-editor", "bin/acme-editor");
        assertTrue(appRun.startsWith("#!/bin/sh\n"), appRun);
        assertTrue(appRun.contains("exec \"$HERE/usr/lib/acme-editor/bin/acme-editor\" \"$@\"\n"), appRun);
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
        String metainfo = LinuxDesktop.metainfo(TestConfig.app(), "org.acme.AcmeEditor", "org.acme.AcmeEditor.desktop");
        assertTrue(metainfo.contains("<id>org.acme.AcmeEditor</id>"), metainfo);
        assertTrue(metainfo.contains("<summary>Edits &lt;documents&gt;</summary>"), metainfo);
        assertTrue(metainfo.contains("<launchable type=\"desktop-id\">org.acme.AcmeEditor.desktop</launchable>"), metainfo);
    }

    @Test
    void theSnapcraftProject() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("description", "Edits the user's documents", "homepage",
                "https://acme.org", "linux.snap.stage-packages", "libxtst6,libxi6"));
        AppInfo app = TestConfig.app(config);
        assertEquals("acme-editor", SnapPackager.snapName(app));
        String yaml = SnapPackager.snapcraftYaml(app, config.linux().snap(), Arch.X64, "acme-editor", "bin/acme-editor");
        assertTrue(yaml.contains("name: acme-editor\ntitle: 'Acme Editor'\nversion: '1.2.3~SNAPSHOT'\n"), yaml);
        assertTrue(yaml.contains("summary: 'Edits the user''s documents'\n"), yaml);
        assertTrue(yaml.contains("base: core24\ngrade: stable\nconfinement: strict\n"), yaml);
        assertTrue(yaml.contains("platforms:\n  amd64:\n"), yaml);
        assertTrue(yaml.contains("apps:\n  acme-editor:\n    command: lib/acme-editor/bin/acme-editor\n"
                + "    extensions: [gnome]\n    plugs:\n      - home\n"), yaml);
        assertTrue(yaml.contains("parts:\n  application:\n    plugin: dump\n    source: payload\n"
                + "    stage-packages:\n      - libxtst6\n      - libxi6\n"), yaml);

        AppInfo dotted = TestConfig.app(TestConfig.of(Map.of("linux.package-name", "acme.editor")));
        assertThrows(PackagingException.class, () -> SnapPackager.snapName(dotted));
    }
}
