package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.deployment.windows.WindowsResources;
import io.quarkiverse.desktop.packaging.spi.NativeExecutableFileBuildItem;
import io.quarkiverse.desktop.packaging.spi.PackagedJvmOptionBuildItem;

/**
 * The application images : the one of a native executable, built here, and what the one of JVM mode gives to
 * {@code jpackage}.
 */
class ApplicationImageTest {

    static PackagingContext context(Path project, TargetOs os, Map<String, String> properties) throws IOException {
        DesktopPackagingConfig config = TestConfig.of(properties);
        return Packaging.context(config, "Acme Editor", "1.2.3", "org.acme", project, project.resolve("target"), os,
                Arch::host);
    }

    /**
     * A build directory with a native executable, its libraries, and files that are not part of the application.
     */
    static Path nativeBuild(Path project) throws IOException {
        Path target = Files.createDirectories(project.resolve("target"));
        for (String file : List.of("acme-runner", "acme-runner.exe", "awt.dll", "libawt.so", "libfreetype.so.6",
                "libawt.dylib", "libswt.jnilib", "acme.jar", "acme-runner-build-output-stats.json")) {
            Files.writeString(target.resolve(file), file);
        }
        return target;
    }

    @Test
    void theImageOfAWindowsNativeExecutable(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Path extra = Files.writeString(project.resolve("extra.dat"), "extra");
        Files.createDirectories(project.resolve("src/main/packaging/content/docs"));
        Files.writeString(project.resolve("src/main/packaging/content/docs/manual.txt"), "manual");

        PackagingContext context = context(project, TargetOs.WINDOWS, Map.of());
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner.exe"),
                List.of(new NativeExecutableFileBuildItem(extra, "data")), List.of());
        assertEquals(project.resolve("target/packaging/image/Acme Editor"), image.root());
        assertEquals("Acme Editor.exe", image.launcher());
        assertTrue(image.nativeExecutable());
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("awt.dll"), Path.of("data", "extra.dat"),
                Path.of("docs", "manual.txt")), FileTrees.files(image.root()));
        assertEquals("acme-runner.exe", Files.readString(image.launcherPath()));
    }

    /**
     * A 64-bit Windows library with one section, which imports the given files : the import descriptors, then the
     * names.
     */
    static byte[] library(String... imports) {
        int names = 20 * (imports.length + 1);
        ByteBuffer buffer = ByteBuffer.allocate(0x400).order(ByteOrder.LITTLE_ENDIAN);
        // the DOS header, the signature, the file header, PE32+ and the import directory (the second data directory)
        buffer.putShort(0, (short) 0x5a4d).putInt(0x3c, 0x40);
        buffer.putInt(0x40, 0x00004550).putShort(0x44, (short) 0x8664).putShort(0x46, (short) 1).putShort(0x54, (short) 240);
        buffer.putShort(0x58, (short) 0x20b).putInt(0x58 + 112 + 8, 0x1000).putInt(0x58 + 112 + 12, names);
        // the section : virtual size, virtual address, size and offset in the file
        buffer.putInt(0x58 + 240 + 8, 0x200).putInt(0x58 + 240 + 12, 0x1000).putInt(0x58 + 240 + 16, 0x200)
                .putInt(0x58 + 240 + 20, 0x200);
        for (int i = 0; i < imports.length; i++) {
            buffer.putInt(0x200 + 20 * i + 12, 0x1000 + names + 32 * i);
            buffer.put(0x200 + names + 32 * i, imports[i].getBytes(StandardCharsets.US_ASCII));
        }
        return buffer.array();
    }

    /**
     * A JDK for Windows, with the Visual C++ runtime in its {@code bin} directory : {@code msvcp140.dll} imports the two
     * others, and {@code vcruntime140_1.dll} imports {@code vcruntime140.dll}.
     */
    static Path vcRuntimeJdk(Path home) throws IOException {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.write(bin.resolve("vcruntime140.dll"), library("KERNEL32.dll"));
        Files.write(bin.resolve("vcruntime140_1.dll"), library("VCRUNTIME140.dll"));
        Files.write(bin.resolve("msvcp140.dll"), library("KERNEL32.dll", "VCRUNTIME140.dll", "VCRUNTIME140_1.dll"));
        return home;
    }

    /**
     * native-image links every Windows executable with the Visual C++ runtime, which is not part of Windows : the image
     * gets the libraries of the runtime that its executable and its libraries import, from the first JDK that has
     * them, with the ones that they import. Each image is built in an output directory of its own : an antivirus may
     * still read the libraries of the image before, which a build in the same directory deletes.
     */
    @Test
    void theVisualCppRuntimeOfAWindowsNativeExecutable(@TempDir Path project) throws IOException {
        Path target = Files.createDirectories(project.resolve("target"));
        Path executable = Files.write(target.resolve("acme-runner.exe"), library("KERNEL32.dll", "VCRUNTIME140.dll"));
        Path jdk = vcRuntimeJdk(project.resolve("jdk"));
        List<Path> homes = List.of(project.resolve("graalvm"), jdk);
        PackagingContext context = context(project, TargetOs.WINDOWS, Map.of());

        ApplicationImage image = NativeApplicationImage.build(context, executable, List.of(), homes);
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("vcruntime140.dll")), FileTrees.files(image.root()));
        assertArrayEquals(Files.readAllBytes(jdk.resolve("bin/vcruntime140.dll")),
                Files.readAllBytes(image.root().resolve("vcruntime140.dll")));

        // a library of the JDK that imports msvcp140.dll, which imports vcruntime140_1.dll
        Files.write(target.resolve("awt.dll"), library("MSVCP140.dll"));
        image = NativeApplicationImage.build(context(project, TargetOs.WINDOWS, Map.of("output-directory", "packaging-2")),
                executable, List.of(), homes);
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("awt.dll"), Path.of("msvcp140.dll"),
                Path.of("vcruntime140.dll"), Path.of("vcruntime140_1.dll")), FileTrees.files(image.root()));
        assertEquals(List.of(), NativeApplicationImage.missingVcRuntime(image.root()));

        // no JDK has them : a warning
        image = NativeApplicationImage.build(context(project, TargetOs.WINDOWS, Map.of("output-directory", "packaging-3")),
                executable, List.of(), List.of());
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("awt.dll")), FileTrees.files(image.root()));
        assertEquals(List.of("msvcp140.dll", "vcruntime140.dll"), NativeApplicationImage.missingVcRuntime(image.root()));
    }

    /**
     * A library of the Visual C++ runtime that the image has, in any case, stays as it is : the copy of Desktop AWT,
     * the one of the content directory.
     */
    @Test
    void theVisualCppRuntimeOfTheImageStays(@TempDir Path project) throws IOException {
        Path target = Files.createDirectories(project.resolve("target"));
        Path executable = Files.write(target.resolve("acme-runner.exe"),
                library("VCRUNTIME140.dll", "VCRUNTIME140_1.dll"));
        Files.writeString(target.resolve("VCRUNTIME140.dll"), "awt");
        Files.createDirectories(project.resolve("src/main/packaging/content"));
        Files.writeString(project.resolve("src/main/packaging/content/vcruntime140_1.dll"), "content");
        PackagingContext context = context(project, TargetOs.WINDOWS, Map.of());
        ApplicationImage image = NativeApplicationImage.build(context, executable, List.of(),
                List.of(vcRuntimeJdk(project.resolve("jdk"))));
        assertEquals(List.of(Path.of("Acme Editor.exe"), Path.of("VCRUNTIME140.dll"), Path.of("vcruntime140_1.dll")),
                FileTrees.files(image.root()));
        assertEquals("awt", Files.readString(image.root().resolve("VCRUNTIME140.dll")));
        assertEquals("content", Files.readString(image.root().resolve("vcruntime140_1.dll")));
    }

    @Test
    void theImageOfALinuxNativeExecutable(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        PackagingContext context = context(project, TargetOs.LINUX, Map.of("linux.package-name", "editor"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"), List.of(),
                List.of());
        assertEquals(project.resolve("target/packaging/image/editor"), image.root());
        assertEquals("editor", image.launcher());
        assertEquals(List.of(Path.of("editor"), Path.of("libawt.so"), Path.of("libfreetype.so.6")),
                FileTrees.files(image.root()));
        // a second build replaces the image
        Files.delete(target.resolve("libawt.so"));
        assertEquals(List.of(Path.of("editor"), Path.of("libfreetype.so.6")),
                FileTrees.files(NativeApplicationImage.build(context, target.resolve("acme-runner"), List.of(), List.of())
                        .root()));
    }

    @Test
    void theBundleOfAMacNativeExecutable(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Files.createDirectories(project.resolve("content"));
        Files.writeString(project.resolve("content/manual.txt"), "manual");
        // not signed : codesign is a tool of macOS
        PackagingContext context = context(project, TargetOs.MACOS, Map.of("macos.sign.enabled", "false",
                "content-directory", "content"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"), List.of(),
                List.of());
        assertEquals(project.resolve("target/packaging/image/Acme Editor.app"), image.root());
        assertEquals("Contents/MacOS/Acme Editor", image.launcher());
        assertEquals(List.of(Path.of("Contents", "Info.plist"), Path.of("Contents", "MacOS", "Acme Editor"),
                Path.of("Contents", "MacOS", "libawt.dylib"), Path.of("Contents", "MacOS", "libswt.jnilib"),
                Path.of("Contents", "PkgInfo"), Path.of("Contents", "Resources", "Acme-Editor.icns"),
                Path.of("Contents", "Resources", "manual.txt")), FileTrees.files(image.root()));
        String plist = Files.readString(image.root().resolve("Contents/Info.plist"));
        assertTrue(plist.contains("<string>org.acme.AcmeEditor</string>"), plist);
        assertTrue(plist.contains("<string>Acme-Editor.icns</string>"), plist);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Creating a symbolic link needs a privilege on Windows")
    void aFileOfTheExecutableThatIsNotCodeIsAResourceOfTheMacBundle(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Path extra = Files.writeString(project.resolve("extra.dat"), "extra");
        Path icu = Files.writeString(project.resolve("icudtl.dat"), "icu");
        // a Mach-O file : the magic number of the 64-bit ones
        Path library = Files.write(project.resolve("libnative.dylib"), new byte[] { (byte) 0xcf, (byte) 0xfa, (byte) 0xed,
                (byte) 0xfe, 0, 0, 0, 0 });
        // a file put in the resources by the producer itself
        Path notes = Files.writeString(project.resolve("notes.txt"), "notes");
        PackagingContext context = context(project, TargetOs.MACOS, Map.of("macos.sign.enabled", "false"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"),
                List.of(new NativeExecutableFileBuildItem(extra, "data"), new NativeExecutableFileBuildItem(icu),
                        new NativeExecutableFileBuildItem(library, "lib"),
                        new NativeExecutableFileBuildItem(notes, "../Resources")),
                List.of());
        Path contents = image.root().resolve("Contents");
        assertEquals(List.of(Path.of("Contents", "Info.plist"), Path.of("Contents", "MacOS", "Acme Editor"),
                Path.of("Contents", "MacOS", "data", "extra.dat"), Path.of("Contents", "MacOS", "icudtl.dat"),
                Path.of("Contents", "MacOS", "lib", "libnative.dylib"), Path.of("Contents", "MacOS", "libawt.dylib"),
                Path.of("Contents", "MacOS", "libswt.jnilib"), Path.of("Contents", "PkgInfo"),
                Path.of("Contents", "Resources", "Acme-Editor.icns"), Path.of("Contents", "Resources", "data", "extra.dat"),
                Path.of("Contents", "Resources", "icudtl.dat"), Path.of("Contents", "Resources", "notes.txt")),
                FileTrees.files(image.root()));
        assertEquals("extra", Files.readString(contents.resolve("Resources/data/extra.dat")));
        assertEquals(Path.of("../../Resources/data/extra.dat"),
                Files.readSymbolicLink(contents.resolve("MacOS/data/extra.dat")));
        assertEquals("extra", Files.readString(contents.resolve("MacOS/data/extra.dat")));
        assertEquals(Path.of("../Resources/icudtl.dat"), Files.readSymbolicLink(contents.resolve("MacOS/icudtl.dat")));
        assertEquals("icu", Files.readString(contents.resolve("MacOS/icudtl.dat")));
        // code stays next to the executable, and a file that is already a resource has no link
        assertTrue(Files.isRegularFile(contents.resolve("MacOS/lib/libnative.dylib"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(contents.resolve("Resources/notes.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * A file of the executable that is a symbolic link goes where the file that it links to would go, as a copy of that
     * file under the name of the link : a resource of the bundle when it is not code, next to the executable when it is.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Creating a symbolic link needs a privilege on Windows")
    void aLinkedFileOfTheExecutableGoesWhereItsTargetWould(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        Path toolkit = Files.createDirectories(project.resolve("toolkit"));
        Files.writeString(toolkit.resolve("icudtl.dat"), "icu");
        Files.write(toolkit.resolve("libnative.dylib"), new byte[] { (byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe,
                0, 0, 0, 0 });
        Path icu = Files.createSymbolicLink(target.resolve("icudtl.dat"), Path.of("../toolkit/icudtl.dat"));
        Path renamed = Files.createSymbolicLink(target.resolve("renamed.dat"), Path.of("icudtl.dat"));
        Path library = Files.createSymbolicLink(Files.createDirectories(project.resolve("ext")).resolve("libnative.dylib"),
                Path.of("../toolkit/libnative.dylib"));
        PackagingContext context = context(project, TargetOs.MACOS, Map.of("macos.sign.enabled", "false"));
        ApplicationImage image = NativeApplicationImage.build(context, target.resolve("acme-runner"),
                List.of(new NativeExecutableFileBuildItem(icu), new NativeExecutableFileBuildItem(renamed, "data"),
                        new NativeExecutableFileBuildItem(library, "lib")),
                List.of());
        Path contents = image.root().resolve("Contents");
        assertTrue(Files.isRegularFile(contents.resolve("Resources/icudtl.dat"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("icu", Files.readString(contents.resolve("Resources/icudtl.dat")));
        assertEquals(Path.of("../Resources/icudtl.dat"), Files.readSymbolicLink(contents.resolve("MacOS/icudtl.dat")));
        assertTrue(Files.isRegularFile(contents.resolve("Resources/data/renamed.dat"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("icu", Files.readString(contents.resolve("Resources/data/renamed.dat")));
        assertEquals(Path.of("../../Resources/data/renamed.dat"),
                Files.readSymbolicLink(contents.resolve("MacOS/data/renamed.dat")));
        assertTrue(Files.isRegularFile(contents.resolve("MacOS/lib/libnative.dylib"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(FileTrees.isMachO(contents.resolve("MacOS/lib/libnative.dylib")));
        assertFalse(Files.exists(contents.resolve("Resources/lib/libnative.dylib"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void theLinkOfAFileThatIsNotCodeInAMacBundle() {
        assertEquals("../Resources/extra.dat", NativeApplicationImage.macosResourceLink("", "extra.dat"));
        assertEquals("../../Resources/data/extra.dat", NativeApplicationImage.macosResourceLink("data", "extra.dat"));
        assertEquals("../../../Resources/a/b/extra.dat", NativeApplicationImage.macosResourceLink("a/b", "extra.dat"));
    }

    @Test
    void aMissingFileOfTheExecutableFailsTheBuild(@TempDir Path project) throws IOException {
        Path target = nativeBuild(project);
        PackagingContext context = context(project, TargetOs.LINUX, Map.of());
        assertThrows(PackagingException.class, () -> NativeApplicationImage.build(context, target.resolve("acme-runner"),
                List.of(new NativeExecutableFileBuildItem(project.resolve("missing.so"))), List.of()));
        PackagingContext missingContent = context(project, TargetOs.LINUX, Map.of("content-directory", "missing"));
        assertThrows(PackagingException.class,
                () -> NativeApplicationImage.build(missingContent, target.resolve("acme-runner"), List.of(), List.of()));
    }

    /**
     * The libraries that the executable loads only in some cases (the GTK 4 and OpenGL libraries of SWT) : relative to
     * the root of the image, for the Linux packages, which do not depend on the libraries that they are linked with.
     */
    @Test
    void theOptionalLibrariesOfTheImage(@TempDir Path project) throws IOException {
        Path target = project.resolve("target");
        List<NativeExecutableFileBuildItem> files = List.of(
                new NativeExecutableFileBuildItem(target.resolve("libswt-gtk-4974r10.so")),
                new NativeExecutableFileBuildItem(target.resolve("libswt-pi4-gtk-4974r10.so"), "", true),
                new NativeExecutableFileBuildItem(target.resolve("libswt-glx-gtk-4974r10.so"), "lib", true),
                new NativeExecutableFileBuildItem(target.resolve("data.txt"), "data"));
        assertFalse(files.get(0).isOptional());
        assertFalse(files.get(3).isOptional());
        assertEquals(Set.of("libswt-pi4-gtk-4974r10.so", "lib/libswt-glx-gtk-4974r10.so"),
                NativeApplicationImage.optionalLibraries(TargetOs.LINUX, files));
        assertEquals(Set.of("Contents/MacOS/libswt-pi4-gtk-4974r10.so", "Contents/MacOS/lib/libswt-glx-gtk-4974r10.so"),
                NativeApplicationImage.optionalLibraries(TargetOs.MACOS, files));
        assertEquals(Set.of(), NativeApplicationImage.optionalLibraries(TargetOs.LINUX, List.of(files.get(0))));

        // the context of the packages has them, and the name that the application gives itself at run time
        PackagingContext context = context(project, TargetOs.LINUX, Map.of());
        assertEquals(Set.of(), context.optionalLibraries());
        assertEquals(Optional.empty(), context.app().applicationId());
        PackagingContext image = context.withOptionalLibraries(NativeApplicationImage.optionalLibraries(TargetOs.LINUX,
                files)).withApplicationId(Optional.of("Acme Editor"));
        assertEquals(List.of("lib/libswt-glx-gtk-4974r10.so", "libswt-pi4-gtk-4974r10.so"),
                List.copyOf(image.optionalLibraries()));
        assertEquals(Optional.of("Acme Editor"), image.app().applicationId());
        assertEquals(context.app(), image.app().withApplicationId(Optional.empty()));
        assertEquals(context.arch(), image.arch());
        assertEquals(context.outputDirectory(), image.outputDirectory());
    }

    @Test
    void theSharedLibrariesOfEachOperatingSystem() {
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.WINDOWS, "AWT.DLL"));
        assertFalse(NativeApplicationImage.isSharedLibrary(TargetOs.WINDOWS, "libawt.so"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.LINUX, "libawt.so"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.LINUX, "libz.so.1.2.13"));
        assertFalse(NativeApplicationImage.isSharedLibrary(TargetOs.LINUX, "notes.solution"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.MACOS, "libawt.dylib"));
        assertTrue(NativeApplicationImage.isSharedLibrary(TargetOs.MACOS, "libswt-cocoa-4970r1.jnilib"));
    }

    @Test
    void theModulesOfTheJavaRuntimeIncludeTheOnesOfQuarkus() {
        assertEquals(List.of("java.desktop", "java.logging", "java.naming", "jdk.jfr", "jdk.unsupported", "jdk.zipfs"),
                JvmApplicationImage.modules(List.of("java.desktop", "java.logging"), 25));
        // the elliptic curve cryptography of JDK 21, which TLS needs : java.base has it from JDK 22
        assertEquals(List.of("java.desktop", "java.net.http", "java.logging", "java.naming", "jdk.jfr", "jdk.unsupported",
                "jdk.zipfs", "jdk.crypto.ec"), JvmApplicationImage.modules(List.of("java.desktop", "java.net.http"), 21));
        assertEquals(List.of("jdk.crypto.ec", "java.logging", "java.naming", "jdk.jfr", "jdk.unsupported", "jdk.zipfs"),
                JvmApplicationImage.modules(List.of("jdk.crypto.ec"), 21));
        assertEquals(List.of("java.desktop", "java.logging", "java.naming", "jdk.jfr", "jdk.unsupported", "jdk.zipfs"),
                JvmApplicationImage.modules(List.of("java.desktop"), 22));
        assertEquals(List.of("java.desktop", "java.logging", "java.naming", "jdk.jfr", "jdk.unsupported", "jdk.zipfs"),
                JvmApplicationImage.modules(List.of("java.desktop"), -1));
    }

    /**
     * Without modules in the configuration, the default modules of {@code jpackage}, with the providers of services
     * that the one of JDK 25 leaves out (the locale data, the charsets, the key stores of Windows...).
     */
    @Test
    void theDefaultModulesOfTheJavaRuntimeHaveTheProvidersOfServices() {
        String release = String.join("\n", "IMPLEMENTOR=\"Oracle Corporation\"", "JAVA_VERSION=\"25\"",
                "MODULES=\"java.base java.logging java.naming jdk.charsets jdk.crypto.cryptoki jdk.jfr jdk.localedata"
                        + " jdk.naming.dns jdk.naming.rmi jdk.unsupported jdk.zipfs\"",
                "OS_NAME=\"Linux\"", "");
        assertEquals(List.of("java.base", "java.logging", "java.naming", "jdk.charsets", "jdk.crypto.cryptoki", "jdk.jfr",
                "jdk.localedata", "jdk.naming.dns", "jdk.naming.rmi", "jdk.unsupported", "jdk.zipfs"),
                List.copyOf(JvmApplicationImage.releaseModules(release)));
        assertEquals(25, JvmApplicationImage.releaseVersion(release));
        assertEquals(21, JvmApplicationImage.releaseVersion("JAVA_VERSION=\"21.0.2\"\r\nMODULES=\"java.base\"\r\n"));
        assertEquals(Set.of(), JvmApplicationImage.releaseModules("JAVA_VERSION=\"25\"\n"));
        assertEquals(-1, JvmApplicationImage.releaseVersion(""));

        // without the default root modules of the JDK, ALL-DEFAULT last, which the jpackage of JDK 21 to 25 expands
        // there only ; jdk.crypto.mscapi only in the JDK of Windows
        assertEquals(List.of("java.logging", "java.naming", "jdk.jfr", "jdk.unsupported", "jdk.zipfs", "jdk.localedata",
                "jdk.charsets", "jdk.crypto.cryptoki", "jdk.naming.dns", "jdk.naming.rmi", "ALL-DEFAULT"),
                JvmApplicationImage.defaultModules(JvmApplicationImage.releaseModules(release), List.of()));
        assertEquals(List.of("java.logging", "jdk.charsets", "jdk.crypto.mscapi", "ALL-DEFAULT"),
                JvmApplicationImage.defaultModules(Set.of("java.base", "java.logging", "jdk.charsets", "jdk.crypto.mscapi"),
                        List.of()));
        // with them, no ALL-DEFAULT, which the jpackage of JDK 26 and later ignores
        assertEquals(List.of("java.desktop", "java.logging", "jdk.charsets"), JvmApplicationImage
                .defaultModules(Set.of("java.base", "java.logging", "jdk.charsets"), List.of("java.desktop", "java.logging")));

        // the default root modules : the ones that export a package without qualification, not the incubator ones
        assertEquals(List.of("java.desktop", "jdk.jfr"), JvmApplicationImage.defaultRoots(List.of(
                ModuleDescriptor.newModule("jdk.jfr").exports("jdk.jfr").build(),
                ModuleDescriptor.newModule("java.desktop").exports("java.awt").build(),
                ModuleDescriptor.newModule("jdk.internal.vm.ci").exports(Set.of(), "jdk.vm.ci", Set.of("jdk.graal"))
                        .build(),
                ModuleDescriptor.newModule("jdk.localedata").build(),
                ModuleDescriptor.newModule("jdk.incubator.vector").exports("jdk.incubator.vector").build())));
        // the JDK of the build, which has Java SE
        List<String> roots = JvmApplicationImage.defaultRoots(Path.of(System.getProperty("java.home")));
        assertTrue(roots.containsAll(List.of("java.base", "java.desktop", "java.sql", "jdk.jfr")), roots.toString());
        assertFalse(roots.contains("jdk.localedata"), roots.toString());
    }

    /**
     * The default Java runtime, as the {@code jpackage} of the JDK of the build builds it from the modules that the
     * extension gives it : the modules of Java SE and of the JDK that export an API (the default root modules, which
     * the extension reads from the JDK : the {@code jpackage} of JDK 26 and later ignores {@code ALL-DEFAULT}), with
     * the ones of Quarkus and the providers of services. In the build directory rather than in a temporary directory : an
     * antivirus may still be reading the new
     * executables when the test deletes them.
     */
    @Test
    void theDefaultJavaRuntimeHasTheProvidersOfServices() throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path jpackage = javaHome.resolve("bin").resolve(TargetOs.host() == TargetOs.WINDOWS ? "jpackage.exe" : "jpackage");
        assumeTrue(Files.isRegularFile(jpackage), "The JDK of the build has no jpackage : " + javaHome);
        assumeTrue(Files.isRegularFile(javaHome.resolve("release")), "The JDK of the build has no release file : "
                + javaHome);
        Path project = Files.createDirectories(Path.of("target", "default-java-runtime").toAbsolutePath());
        // an uber-jar : jpackage reads the name of its main class, and does not load it
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.acme.Main");
        Path jar = project.resolve("acme-runner.jar");
        new JarOutputStream(Files.newOutputStream(jar), manifest).close();
        // the options of jlink do not change the modules : without --strip-debug, which needs objcopy on Linux. A bundle
        // that is not signed, without codesign of a certificate
        PackagingContext context = context(project, TargetOs.host(), Map.of("jvm.jlink-options",
                "--strip-java-debug-attributes,--strip-native-commands,--no-man-pages,--no-header-files",
                "macos.sign.enabled", "false"));

        ApplicationImage image = JvmApplicationImage.build(context, jar, true, Optional.empty(), List.of());
        Path runtime = switch (TargetOs.host()) {
            case WINDOWS -> image.root().resolve("runtime");
            case MACOS -> image.root().resolve("Contents/runtime/Contents/Home");
            case LINUX -> image.root().resolve("lib/runtime");
        };
        Set<String> modules = JvmApplicationImage.releaseModules(Files.readString(runtime.resolve("release")));
        List<String> expected = new ArrayList<>(List.of("java.desktop", "java.sql", "jdk.localedata", "jdk.charsets",
                "jdk.crypto.cryptoki", "jdk.naming.dns", "java.logging", "java.naming", "jdk.jfr", "jdk.unsupported",
                "jdk.zipfs"));
        if (TargetOs.host() == TargetOs.WINDOWS) {
            expected.add("jdk.crypto.mscapi");
        }
        for (String module : expected) {
            assertTrue(modules.contains(module), module + " is not in the default Java runtime : " + modules);
        }
        try {
            FileTrees.delete(image.root());
        } catch (IOException e) {
            // an antivirus that is reading a new executable : the next build deletes the image
        }
    }

    /**
     * The options of the JVM : the ones of the extensions for the operating system of the application, then the ones of
     * the configuration.
     */
    @Test
    void theOptionsOfTheJvmOfTheExtensionsAndOfTheConfiguration() {
        List<PackagedJvmOptionBuildItem> extensions = List.of(
                new PackagedJvmOptionBuildItem("--enable-native-access=ALL-UNNAMED"),
                new PackagedJvmOptionBuildItem("-XstartOnFirstThread", io.smallrye.common.os.OS.MAC));
        Optional<List<String>> configured = Optional.of(List.of("-Xmx64m"));
        assertEquals(List.of("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread", "-Xmx64m"),
                JvmApplicationImage.javaOptions(extensions, TargetOs.MACOS, configured));
        assertEquals(List.of("--enable-native-access=ALL-UNNAMED", "-Xmx64m"),
                JvmApplicationImage.javaOptions(extensions, TargetOs.LINUX, configured));
        assertEquals(List.of("--enable-native-access=ALL-UNNAMED", "-Xmx64m"),
                JvmApplicationImage.javaOptions(extensions, TargetOs.WINDOWS, configured));
        assertEquals(List.of("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread"),
                JvmApplicationImage.javaOptions(extensions, TargetOs.MACOS, Optional.empty()));
    }

    /**
     * An option of the JVM is one argument of the JVM, which {@code jpackage} would split at white space and unquote.
     */
    @Test
    void anOptionOfTheJvmIsOneArgument() {
        assertEquals("-Xmx64m", JvmApplicationImage.javaOption("-Xmx64m"));
        assertEquals("-Xmx64m", JvmApplicationImage.javaOption(" -Xmx64m "));
        assertEquals("-Dlib=C:\\lib\\", JvmApplicationImage.javaOption("-Dlib=C:\\lib\\"));
        assertEquals("'-Dtitle=Acme Editor'", JvmApplicationImage.javaOption("-Dtitle=Acme Editor"));
        assertEquals("'-Downer=Bob\\'s'", JvmApplicationImage.javaOption("-Downer=Bob's"));
        assertEquals("'-Dq=say \\\"hi\\\" a\\\\'b'", JvmApplicationImage.javaOption("-Dq=say \"hi\" a\\'b"));
        // a backslash before the closing quote would escape it
        assertEquals("'-Dhome=C:\\Program Files\\Acme'\\", JvmApplicationImage.javaOption("-Dhome=C:\\Program Files\\Acme\\"));
        // the launcher takes the long form of an option as one argument with = only
        assertEquals("--add-opens=java.base/java.lang=ALL-UNNAMED",
                JvmApplicationImage.javaOption("--add-opens java.base/java.lang=ALL-UNNAMED"));
        assertEquals("'--module-path=C:\\Program Files\\Acme'",
                JvmApplicationImage.javaOption("--module-path  C:\\Program Files\\Acme"));
        assertEquals("--enable-native-access=ALL-UNNAMED",
                JvmApplicationImage.javaOption("--enable-native-access=ALL-UNNAMED"));
    }

    /**
     * The options of the JVM as the list of the configuration gives them : a comma separates them and a backslash
     * escapes the next character, so that a path is written with {@code /}. An indexed property keeps its commas and
     * its backslashes, and the indexed properties replace the list.
     */
    @Test
    void theOptionsOfTheJvmOfTheConfiguration() {
        assertEquals(List.of("-Djava.library.path=$APPDIR/native", "-Dq=a,b"), TestConfig.of(Map.of("jvm.options",
                "-Djava.library.path=$APPDIR/native,-Dq=a\\,b")).jvm().options().orElseThrow());
        // the rule of the lists, which the documentation of the property gives
        assertEquals(List.of("-Dp=C:lib"), TestConfig.of(Map.of("jvm.options", "-Dp=C:\\lib")).jvm().options()
                .orElseThrow());
        List<String> indexed = TestConfig.of(Map.of("jvm.options", "-Xmx64m", "jvm.options[0]",
                "-Dhome=C:\\Program Files\\Acme\\", "jvm.options[1]", "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"))
                .jvm().options().orElseThrow();
        assertEquals(List.of("-Dhome=C:\\Program Files\\Acme\\", "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"),
                indexed);
        // one argument of the JVM each, as jpackage takes it
        assertEquals("'-Dhome=C:\\Program Files\\Acme'\\", JvmApplicationImage.javaOption(indexed.get(0)));
        assertEquals("-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3", JvmApplicationImage.javaOption(indexed.get(1)));
    }

    /**
     * An option of the configuration that holds several options of the JVM, separated by white space, is one argument
     * of the JVM all the same : a warning, which shows how to separate them.
     */
    @Test
    void severalOptionsInOneAreWarned() {
        String warning = JvmApplicationImage.severalOptionsWarning(Optional.of(List.of("-Xmx512m", "-Xms64m -Xmx512m")))
                .orElseThrow();
        assertTrue(warning.startsWith("quarkus.desktop.packaging.jvm.options : "), warning);
        assertTrue(warning.contains("the JVM gets \"-Xms64m -Xmx512m\" as one option,"), warning);
        assertTrue(warning.endsWith("Separate the options with commas : -Xms64m,-Xmx512m"), warning);
        warning = JvmApplicationImage.severalOptionsWarning(Optional.of(List.of(
                "--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED",
                " -Dfoo=bar\t-Dbaz=qux "))).orElseThrow();
        assertTrue(warning.contains("\" and \"-Dfoo=bar\t-Dbaz=qux\" as one option each,"), warning);
        assertTrue(warning.endsWith(": --add-opens java.base/java.lang=ALL-UNNAMED,--add-opens java.base/java.io=ALL-UNNAMED"
                + " and -Dfoo=bar,-Dbaz=qux"), warning);

        // one option each : with white space in its value, or a dash that does not start an option
        assertEquals(Optional.empty(), JvmApplicationImage.severalOptionsWarning(Optional.empty()));
        for (String option : List.of("-Xmx512m", " -Xmx512m ", "-Dapp.title=Acme Editor", "-XX:OnOutOfMemoryError=kill -9 %p",
                "-Dhome=C:\\Program Files\\Acme\\", "--add-opens java.base/java.lang=ALL-UNNAMED",
                "--module-path  C:\\Program Files\\Acme", "-Dtitle=Acme - Editor")) {
            assertEquals(Optional.empty(), JvmApplicationImage.severalOptionsWarning(Optional.of(List.of(option))),
                    option);
        }
    }

    /**
     * The arguments of {@code jpackage} in a file, in UTF-8 : each one in double quotes, which keep its white space, its
     * single quotes, its first {@code #} or {@code @} and its emptiness.
     */
    @Test
    void theArgumentFileOfJpackage() {
        assertEquals(String.join("\n", "\"--name\"", "\"Редактор\"", "\"--vendor\"", "\"@stake \\\"Acme\\\" Spółka\"",
                "\"--input\"", "\"C:\\\\a b\\\\\"", "\"--description\"", "\"a\\nb\\r\\tc\\f\"", "\"--copyright\"",
                "\"#1 'Acme'\"", "\"\"", ""),
                JvmApplicationImage.argumentFile(List.of("--name", "Редактор", "--vendor", "@stake \"Acme\" Spółka",
                        "--input", "C:\\a b\\", "--description", "a\nb\r\tc\f", "--copyright", "#1 'Acme'", "")));
    }

    @Test
    void theLauncherOfAFastJarHasTheRunnerJarAsItsClassPath() {
        String configuration = String.join("\n", "[Application]", "app.mainjar=$APPDIR\\quarkus-run.jar",
                "app.classpath=$APPDIR\\app\\acme.jar", "app.classpath=$APPDIR\\lib\\main\\io.quarkus.quarkus-core.jar", "",
                "[JavaOptions]", "java-options=-Djpackage.app-version=1.2.3", "");
        assertEquals(String.join(System.lineSeparator(), "[Application]", "app.mainjar=$APPDIR\\quarkus-run.jar", "",
                "[JavaOptions]", "java-options=-Djpackage.app-version=1.2.3", ""),
                JvmApplicationImage.runnerJarOnly(configuration));
        // the jpackage of JDK 17 puts the main jar on the class path : without it, the launcher would have no class path
        assertThrows(PackagingException.class, () -> JvmApplicationImage.runnerJarOnly(String.join("\n", "[Application]",
                "app.classpath=$APPDIR\\quarkus-run.jar", "app.mainclass=io.quarkus.bootstrap.runner.QuarkusEntryPoint",
                "app.classpath=$APPDIR\\app\\acme.jar", "")));
    }

    /**
     * A JDK with a {@code jpackage} file, without the {@code .exe} extension on Windows too : the name that the test
     * looks for.
     *
     * @return the path of {@code jpackage}
     */
    static Path jdk(Path home) throws IOException {
        return Files.writeString(Files.createDirectories(home.resolve("bin")).resolve("jpackage"), "");
    }

    @Test
    void theJpackageOfTheJdkComesBeforeTheOneOfThePath(@TempDir Path directory) throws IOException {
        Path configured = jdk(directory.resolve("jdk-21"));
        Path build = jdk(directory.resolve("jdk-25"));
        Path other = jdk(directory.resolve("jdk-17"));
        Path jre = Files.createDirectories(directory.resolve("jre/bin")).getParent();
        Supplier<Optional<Path>> path = () -> Optional.of(other);

        // the configured JDK, the JDK of the build, then the PATH when the JDK of the build has no jpackage
        assertEquals(configured, JvmApplicationImage.jpackage(Optional.empty(), Optional.of(directory.resolve("jdk-21")),
                directory.resolve("jdk-25"), "jpackage", path));
        assertEquals(build, JvmApplicationImage.jpackage(Optional.empty(), Optional.empty(), directory.resolve("jdk-25"),
                "jpackage", path));
        assertEquals(other, JvmApplicationImage.jpackage(Optional.empty(), Optional.empty(), jre, "jpackage", path));
        assertThrows(PackagingException.class,
                () -> JvmApplicationImage.jpackage(Optional.empty(), Optional.empty(), jre, "jpackage", Optional::empty));
        // a configured JDK without jpackage fails the build, rather than leaving it to the PATH
        PackagingException e = assertThrows(PackagingException.class, () -> JvmApplicationImage.jpackage(Optional.empty(),
                Optional.of(jre), directory.resolve("jdk-25"), "jpackage", path));
        assertTrue(e.getMessage().contains("quarkus.desktop.packaging.jvm.jdk"), e.getMessage());

        // quarkus.desktop.packaging.tools.jpackage comes first
        Optional<Path> tool = new Tools(Map.of("jpackage", other.toString())).configured("jpackage");
        assertEquals(Optional.of(other), tool);
        assertEquals(other, JvmApplicationImage.jpackage(tool, Optional.of(directory.resolve("jdk-21")),
                directory.resolve("jdk-25"), "jpackage", path));
        assertEquals(Optional.empty(), new Tools(Map.of()).configured("jpackage"));
        assertEquals(Optional.empty(), new Tools(Map.of("jpackage", " ")).configured("jpackage"));
        assertThrows(PackagingException.class,
                () -> new Tools(Map.of("jpackage", jre.resolve("bin/jpackage").toString())).configured("jpackage"));
    }

    @Test
    void theJpackageOfJdk21OrLater() {
        assertEquals(17, JvmApplicationImage.featureVersion("17.0.10\r\n"));
        assertEquals(21, JvmApplicationImage.featureVersion("21.0.2\n"));
        assertEquals(25, JvmApplicationImage.featureVersion("25"));
        assertEquals(26, JvmApplicationImage.featureVersion("26-ea"));
        assertEquals(25, JvmApplicationImage.featureVersion("Picked up JAVA_TOOL_OPTIONS: -Xmx1g\n25.0.4.1\n"));
        assertEquals(-1, JvmApplicationImage.featureVersion("jpackage"));
        assertEquals(-1, JvmApplicationImage.featureVersion(""));

        Path jpackage = Path.of("jdk-17", "bin", "jpackage");
        PackagingException e = assertThrows(PackagingException.class,
                () -> JvmApplicationImage.checkVersion(jpackage, "17.0.10"));
        assertTrue(e.getMessage().contains("quarkus.desktop.packaging.jvm.jdk"), e.getMessage());
        JvmApplicationImage.checkVersion(jpackage, "21.0.2");
        JvmApplicationImage.checkVersion(jpackage, "25");
        // a version that cannot be read is left to jpackage
        JvmApplicationImage.checkVersion(jpackage, "");
    }

    /**
     * A version that starts with zero, which {@code jpackage} refuses on macOS : the bundle and the application get it
     * all the same.
     */
    @Test
    void theVersionOfAMacApplicationThatStartsWithZero() {
        assertEquals("1.9.1", JvmApplicationImage.jpackageVersion(TargetOs.MACOS, "0.9.1"));
        assertEquals("1.2.3", JvmApplicationImage.jpackageVersion(TargetOs.MACOS, "1.2.3"));
        assertEquals("0.9.1", JvmApplicationImage.jpackageVersion(TargetOs.LINUX, "0.9.1"));
        assertEquals(Optional.of("-Djpackage.app-version=0.9.1"),
                JvmApplicationImage.versionOption(TargetOs.MACOS, "0.9.1"));
        assertEquals(Optional.empty(), JvmApplicationImage.versionOption(TargetOs.MACOS, "1.2.3"));
        assertEquals(Optional.empty(), JvmApplicationImage.versionOption(TargetOs.WINDOWS, "0.9.1"));
    }

    @Test
    void aJavaRuntimeIsBuiltWithItsModulesOrGiven() {
        Path project = Path.of("project");
        String jdk21 = "JAVA_VERSION=\"21.0.2\"\nMODULES=\"java.base java.logging jdk.crypto.ec jdk.localedata\"\n";
        assertEquals(List.of("--add-modules", "java.desktop,java.logging,java.naming,jdk.jfr,jdk.unsupported,jdk.zipfs",
                "--jlink-options", "--strip-debug --no-man-pages"),
                JvmApplicationImage.runtimeArguments(Optional.of(List.of("java.desktop")),
                        Optional.of(List.of("--strip-debug", "--no-man-pages")), Optional.empty(), project, "", List::of));
        assertEquals(List.of("--add-modules",
                "java.desktop,java.logging,java.naming,jdk.jfr,jdk.unsupported,jdk.zipfs,jdk.crypto.ec"),
                JvmApplicationImage.runtimeArguments(Optional.of(List.of("java.desktop")), Optional.empty(),
                        Optional.empty(), project, jdk21, List::of));
        // the default modules of jpackage, with the ones of Quarkus and the providers of services that the JDK has
        assertEquals(List.of("--add-modules", "java.logging,jdk.localedata,ALL-DEFAULT"), JvmApplicationImage
                .runtimeArguments(Optional.empty(), Optional.empty(), Optional.empty(), project, jdk21, List::of));
        assertEquals(List.of("--add-modules", "java.logging,jdk.localedata,ALL-DEFAULT", "--jlink-options",
                "--strip-debug"),
                JvmApplicationImage.runtimeArguments(Optional.of(List.of()),
                        Optional.of(List.of("--strip-debug")), Optional.empty(), project, jdk21, List::of));
        // without the release file of the JDK, the default modules of jpackage only
        assertEquals(List.of(), JvmApplicationImage.runtimeArguments(Optional.empty(), Optional.empty(), Optional.empty(),
                project, "", List::of));
        // jpackage rejects modules with a given runtime
        assertEquals(List.of("--runtime-image", project.resolve("runtime").toString()), JvmApplicationImage
                .runtimeArguments(Optional.of(List.of()), Optional.of(List.of()), Optional.of("runtime"), project, jdk21,
                        List::of));

        // jpackage takes the given runtime as it is
        PackagingException modules = assertThrows(PackagingException.class,
                () -> JvmApplicationImage.runtimeArguments(Optional.of(List.of("java.desktop")), Optional.empty(),
                        Optional.of("runtime"), project, jdk21, List::of));
        assertTrue(modules.getMessage().contains("quarkus.desktop.packaging.jvm.runtime-image"), modules.getMessage());
        assertTrue(modules.getMessage().contains("quarkus.desktop.packaging.jvm.modules"), modules.getMessage());
        assertFalse(modules.getMessage().contains("jlink-options"), modules.getMessage());
        PackagingException options = assertThrows(PackagingException.class,
                () -> JvmApplicationImage.runtimeArguments(Optional.empty(), Optional.of(List.of("--strip-debug")),
                        Optional.of("runtime"), project, jdk21, List::of));
        assertTrue(options.getMessage().contains("quarkus.desktop.packaging.jvm.jlink-options"), options.getMessage());
        assertFalse(options.getMessage().contains("jvm.modules"), options.getMessage());
    }

    /**
     * The options of {@code jlink} that strip the native libraries with the {@code objcopy} of the {@code PATH}, on
     * Linux.
     */
    @Test
    void theOptionsOfJlinkThatNeedObjcopy() {
        // the default options of jpackage have --strip-debug
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.empty(), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.of(List.of()), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.of(List.of("--strip-debug", "--no-man-pages")), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(Optional.of(List.of("--no-man-pages -G")), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-native-debug-symbols=exclude-debuginfo-files")), false));
        assertTrue(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-native-debug-symbols", "keep-debuginfo-files")), false));
        assertFalse(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-java-debug-attributes", "--no-header-files")), false));
        // the objcopy of the options
        assertFalse(JvmApplicationImage.stripsNativeSymbols(
                Optional.of(List.of("--strip-native-debug-symbols", "objcopy=/opt/binutils/bin/objcopy")), false));
        // a given runtime : no jlink
        assertFalse(JvmApplicationImage.stripsNativeSymbols(Optional.empty(), true));
    }

    /**
     * The name of the menu bar that {@code jpackage} writes in the bundle of a JVM application, at most 16 characters,
     * is replaced by the whole name of the application, as in the bundle of a native executable.
     */
    @Test
    void theBundleOfAJvmApplicationHasTheWholeName() {
        String plist = String.join("\n", "<plist version=\"1.0\">", " <dict>", "  <key>CFBundleExecutable</key>",
                "  <string>Acme Document Editor</string>", "  <key>CFBundleName</key>", "  <string>Acme Document Ed</string>",
                "  <key>CFBundleDisplayName</key>", "  <string>Acme Document Ed</string>", " </dict>", "</plist>", "");
        assertEquals(String.join("\n", "<plist version=\"1.0\">", " <dict>", "  <key>CFBundleExecutable</key>",
                "  <string>Acme Document Editor</string>", "  <key>CFBundleName</key>",
                "  <string>Acme Document Editor</string>", "  <key>CFBundleDisplayName</key>",
                "  <string>Acme Document Ed</string>", " </dict>", "</plist>", ""),
                JvmApplicationImage.withBundleName(plist, "Acme Document Editor"));
        assertTrue(JvmApplicationImage.withBundleName(plist, "Acme & Sons <Document Editor>")
                .contains("<key>CFBundleName</key>\n  <string>Acme &amp; Sons &lt;Document Editor&gt;</string>"));
        String withoutName = plist.replace("CFBundleName", "CFBundleSignature");
        assertEquals(withoutName, JvmApplicationImage.withBundleName(withoutName, "Acme Document Editor"));
    }

    @Test
    void theInputOfJpackageHasNoStartupArchive(@TempDir Path directory) throws IOException {
        Path uberJar = Files.writeString(directory.resolve("acme-runner.jar"), "uber-jar");
        Path uberJarInput = JvmApplicationImage.input(Files.createDirectories(directory.resolve("uber-jar")), uberJar, true,
                Optional.empty());
        assertEquals(List.of(Path.of("acme-runner.jar")), FileTrees.files(uberJarInput));

        Path application = directory.resolve("quarkus-app");
        Path jar = FileTrees.write(application.resolve("quarkus-run.jar"), "fast-jar");
        FileTrees.write(application.resolve("lib/boot/acme.jar"), "acme");
        Path work = Files.createDirectories(directory.resolve("work"));
        // without archive : the directory of the fast-jar
        assertEquals(application.toAbsolutePath(), JvmApplicationImage.input(work, jar, false, Optional.empty()));
        // with an archive : a copy of the directory without it, which the build keeps
        Path archive = Files.writeString(application.resolve("app-cds.jsa"), "archive");
        Path input = JvmApplicationImage.input(work, jar, false, Optional.of(archive));
        assertEquals(work.resolve("input"), input);
        assertEquals(List.of(Path.of("lib", "boot", "acme.jar"), Path.of("quarkus-run.jar")), FileTrees.files(input));
        assertTrue(Files.isRegularFile(archive));
        // the shared classes cache of OpenJ9 : a directory
        Files.delete(archive);
        Path cache = Files.createDirectories(application.resolve("app-scc"));
        Files.writeString(cache.resolve("cache"), "cache");
        assertEquals(List.of(Path.of("lib", "boot", "acme.jar"), Path.of("quarkus-run.jar")),
                FileTrees.files(JvmApplicationImage.input(work, jar, false, Optional.of(cache))));
    }

    @Test
    void theOptionsOfTheWindowsExecutable() {
        assertEquals("Args = -H:NativeLinkerOption=C:\\\\caf\\u00E9\\\\application.res\n", DesktopPackagingProcessor
                .nativeImageProperties(List.of("-H:NativeLinkerOption=C:\\caf\u00e9\\application.res")));
    }

    @Test
    void theResourcesOfTheWindowsExecutable(@TempDir Path directory) throws IOException {
        // the only white space of the paths is the one that the test gives them
        assumeFalse(hasWhiteSpace(directory), directory.toString());
        byte[] resources = WindowsResources.res(IconSet.generic(), TestConfig.app(), "Acme Editor.exe");
        Path temporary = directory.resolve("tmp");

        // in the build directory
        Path file = DesktopPackagingProcessor.writeResources(directory.resolve("build"), temporary, resources)
                .orElseThrow();
        assertEquals(directory.resolve("build").toAbsolutePath().resolve("quarkus-desktop-packaging")
                .resolve("application.res"), file);
        assertArrayEquals(resources, Files.readAllBytes(file));

        // native-image splits its options on white space : a new directory of the temporary directory at each build
        Path first = DesktopPackagingProcessor.writeResources(directory.resolve("build dir"), temporary, resources)
                .orElseThrow();
        Path second = DesktopPackagingProcessor.writeResources(directory.resolve("build dir"), temporary, resources)
                .orElseThrow();
        for (Path temporaryFile : List.of(first, second)) {
            assertEquals("application.res", temporaryFile.getFileName().toString());
            assertEquals(temporary.toAbsolutePath(), temporaryFile.getParent().getParent());
            assertTrue(temporaryFile.getParent().getFileName().toString().startsWith("quarkus-desktop-packaging-"),
                    temporaryFile.toString());
            assertFalse(hasWhiteSpace(temporaryFile), temporaryFile.toString());
            assertArrayEquals(resources, Files.readAllBytes(temporaryFile));
        }
        assertNotEquals(first.getParent(), second.getParent());
        assertFalse(Files.exists(directory.resolve("build dir")));

        // no directory without white space
        assertEquals(Optional.empty(), DesktopPackagingProcessor.writeResources(directory.resolve("build dir"),
                directory.resolve("tmp dir"), resources));
        assertFalse(Files.exists(directory.resolve("tmp dir")));
        assertFalse(Files.exists(directory.resolve("build dir")));
    }

    /**
     * Whether a path has white space, which native-image splits its options on.
     */
    private static boolean hasWhiteSpace(Path path) {
        return path.toString().chars().anyMatch(Character::isWhitespace);
    }
}
