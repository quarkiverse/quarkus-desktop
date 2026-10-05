package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.paths.PathTree;

/**
 * The classes whose static initializer uses SWT, directly or through the methods it calls : initialized at run time in
 * native executables. The SWT classes of these samples ({@code RGB}, {@code Point}) load no native library : the test
 * needs no display.
 */
class SwtStaticInitializerScannerTest {

    static class ConstantRgb {
        static final RGB ACCENT = new RGB(0, 150, 201);

        static String name() {
            return "accent";
        }
    }

    static class ConstantPoint {
        static final Point MINIMUM_SIZE = new Point(640, 480);
    }

    static class InstanceUseOnly {
        static final String NAME = "instance";

        RGB color() {
            return new RGB(255, 0, 0);
        }
    }

    static class NoSwt {
        static final List<String> NAMES = List.of("a", "b");
    }

    static class Helper {
        static List<Object> colors() {
            return List.of(new RGB(0, 0, 255));
        }
    }

    static class ViaHelper {
        static final List<Object> COLORS = Helper.colors();
    }

    /**
     * No SWT type in the descriptor of its field : only its static initializer uses SWT.
     */
    static class Defaults {
        static final Object BACKGROUND = new RGB(238, 238, 238);
    }

    static class ViaStaticField {
        static final Object BACKGROUND = Defaults.BACKGROUND;
    }

    static class Holder {
        final Object rgb;

        Holder(int value) {
            this.rgb = new RGB(value, value, value);
        }
    }

    static class ViaConstructor {
        static final List<Holder> HOLDERS = List.of(new Holder(255));
    }

    enum Palette {
        PRIMARY(0, 150, 201);

        final Object rgb;

        Palette(int red, int green, int blue) {
            this.rgb = new RGB(red, green, blue);
        }
    }

    interface Sizes {
        Point DEFAULT = new Point(800, 600);
    }

    static class SubclassOfSwtUser extends ConstantRgb {
    }

    static class TriggersSwtUserInitialization {
        static final String NAME = ConstantRgb.name();
    }

    // A library that is not in the index : its classes are read from its jars

    static class LibraryFactory {
        static Object create() {
            return new RGB(51, 102, 153);
        }
    }

    static class LibraryDefaults {
        static final Object BACKGROUND = new Point(10, 10);
    }

    static class LibraryName {
        static final String NAME = "library";
    }

    /**
     * No SWT type in its class file : only through a class of another jar.
     */
    static class LibraryTheme {
        static final Object DEFAULT = LibraryFactory.create();
    }

    @TempDir
    Path directory;

    @Test
    void detectsClassesCreatingSwtObjectsInStaticInitializers() throws IOException {
        Index index = Index.of(ConstantRgb.class, ConstantPoint.class, InstanceUseOnly.class, NoSwt.class,
                Helper.class, ViaHelper.class, Defaults.class, ViaStaticField.class, Holder.class, ViaConstructor.class,
                Palette.class, Sizes.class, SubclassOfSwtUser.class, TriggersSwtUserInitialization.class);

        Set<String> classes = SwtStaticInitializerScanner.scan(index.getKnownClasses(), getClass().getClassLoader());

        assertEquals(Set.of(
                ConstantRgb.class.getName(),
                ConstantPoint.class.getName(),
                ViaHelper.class.getName(),
                Defaults.class.getName(),
                ViaStaticField.class.getName(),
                ViaConstructor.class.getName(),
                Palette.class.getName(),
                Sizes.class.getName(),
                SubclassOfSwtUser.class.getName(),
                TriggersSwtUserInitialization.class.getName()), classes);
    }

    @Test
    void scansTheLibrariesOutsideTheIndex() throws IOException {
        Path first = jar("first.jar", LibraryTheme.class, LibraryName.class);
        Path second = jar("second.jar", LibraryFactory.class, LibraryDefaults.class);

        List<PathTree> trees = List.of(PathTree.ofArchive(first), PathTree.ofArchive(second));
        Set<String> classes = SwtStaticInitializerScanner.scan(SwtStaticInitializerScanner.classFiles(trees, List.of(),
                getClass().getClassLoader()));

        assertEquals(Set.of(LibraryTheme.class.getName(), LibraryDefaults.class.getName()), classes);
    }

    @Test
    void nothingToInitializeAtRunTime() throws IOException {
        Index index = Index.of(InstanceUseOnly.class, NoSwt.class, Helper.class, Holder.class);

        assertEquals(Set.of(), SwtStaticInitializerScanner.scan(index.getKnownClasses(), getClass().getClassLoader()));
    }

    @Test
    void classNames() {
        assertEquals("com/example/Palette", SwtStaticInitializerScanner.className("com/example/Palette.class"));
        assertEquals("com/example/Palette$1", SwtStaticInitializerScanner.className("com/example/Palette$1.class"));
        // multi-release jars : the class that a versioned class file versions
        assertEquals("com/example/Palette",
                SwtStaticInitializerScanner.className("META-INF/versions/17/com/example/Palette.class"));
        assertEquals("Palette", SwtStaticInitializerScanner.className("Palette.class"));
        for (String other : List.of("module-info.class", "META-INF/versions/9/module-info.class",
                "com/example/package-info.class", "META-INF/Other.class", "com/example/palette.properties")) {
            assertNull(SwtStaticInitializerScanner.className(other), other);
        }
    }

    @Test
    void swtPackageInClassFiles() {
        assertTrue(containsSwtPackage("()Lorg/eclipse/swt/graphics/RGB;"));
        assertTrue(containsSwtPackage("org/eclipse/swt/"));
        assertFalse(containsSwtPackage("Lorg/eclipse/swtbot/Bot;"));
        assertFalse(containsSwtPackage("org/eclipse/sw"));
        assertFalse(containsSwtPackage(""));
    }

    private static boolean containsSwtPackage(String content) {
        return SwtStaticInitializerScanner.containsSwtPackage(content.getBytes(StandardCharsets.US_ASCII));
    }

    private Path jar(String name, Class<?>... classes) throws IOException {
        Path jar = directory.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> type : classes) {
                String entry = type.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(entry));
                try (InputStream in = type.getClassLoader().getResourceAsStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * The packages that the extension initializes at run time are SWT packages for the scanner : a class using them in
     * its static initializer would otherwise be initialized at build time, and fail the native build.
     */
    @Test
    void swtPackagesCoverTheRunTimeInitializedPackages() {
        List<String> packages = new ArrayList<>();
        for (String[] list : List.of(SwtClassesAndResources.RUNTIME_INITIALIZED_PACKAGES,
                SwtClassesAndResources.WINDOWS_RUNTIME_INITIALIZED_PACKAGES,
                SwtClassesAndResources.LINUX_RUNTIME_INITIALIZED_PACKAGES,
                SwtClassesAndResources.MAC_RUNTIME_INITIALIZED_PACKAGES)) {
            packages.addAll(List.of(list));
        }
        List<String> uncovered = packages.stream().map(p -> p.replace('.', '/') + "/")
                .filter(p -> SwtStaticInitializerScanner.SWT_PACKAGES.stream().noneMatch(p::startsWith)).toList();
        assertEquals(List.of(), uncovered);
    }
}
