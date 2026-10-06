package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The struct classes and the callbacks that {@link SwtNativeAccess} computes from the SWT jar of the test class path
 * (the SWT jar of the build host, which Maven adds to {@code org.eclipse.platform:org.eclipse.swt}).
 */
class SwtNativeAccessTest {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of("boolean", boolean.class, "byte", byte.class,
            "char", char.class, "short", short.class, "int", int.class, "long", long.class, "float", float.class,
            "double", double.class);

    private static SwtNativeAccess access;

    private static Set<String> callbacks;

    @BeforeAll
    static void scan() {
        access = SwtNativeAccess.scan(classFiles(swtJar()));
        callbacks = new TreeSet<>();
        access.callbacks().forEach(method -> callbacks.add(method.toString()));
    }

    @Test
    void callbackDescriptors() {
        assertTrue(SwtNativeAccess.isCallbackDescriptor("(JJJJ)J"));
        // the array based callbacks (COMObject)
        assertTrue(SwtNativeAccess.isCallbackDescriptor("([J)J"));
        assertTrue(SwtNativeAccess.isCallbackDescriptor("(JDDJ)V"));
        assertTrue(SwtNativeAccess.isCallbackDescriptor("(IJJ)I"));
        // no argument, a reference argument or result
        assertFalse(SwtNativeAccess.isCallbackDescriptor("()V"));
        assertFalse(SwtNativeAccess.isCallbackDescriptor("()J"));
        assertFalse(SwtNativeAccess.isCallbackDescriptor("(Ljava/lang/Object;)J"));
        assertFalse(SwtNativeAccess.isCallbackDescriptor("(J)Ljava/lang/String;"));
        assertFalse(SwtNativeAccess.isCallbackDescriptor("([I)J"));
        assertFalse(SwtNativeAccess.isCallbackDescriptor("([JJ)J"));
        assertFalse(SwtNativeAccess.isCallbackDescriptor("(J)[J"));
    }

    @Test
    void structClasses() throws ReflectiveOperationException {
        Set<String> structClasses = access.structClasses();
        assertFalse(structClasses.isEmpty());
        // Callback is passed to its own native methods, the native code reads none of its fields
        assertFalse(structClasses.contains("org.eclipse.swt.internal.Callback"));
        List<String> errors = new ArrayList<>();
        for (String structClass : structClasses) {
            if (!structClass.startsWith("org.eclipse.swt.internal.")) {
                errors.add("not an internal class " + structClass);
                continue;
            }
            Class<?> type = type(structClass);
            // the native code looks up the fields on the class of the object and on its super classes
            Class<?> superClass = type.getSuperclass();
            if (superClass != null && superClass.getName().startsWith("org.eclipse.swt.internal.")
                    && !structClasses.contains(superClass.getName())) {
                errors.add("the super class of " + structClass + " is not a struct class : " + superClass.getName());
            }
            // the nested structs
            for (Field field : type.getDeclaredFields()) {
                Class<?> fieldType = field.getType().isArray() ? field.getType().getComponentType() : field.getType();
                String fieldTypeName = fieldType.getName();
                if (!Modifier.isStatic(field.getModifiers()) && fieldTypeName.startsWith("org.eclipse.swt.internal.")
                        && !structClasses.contains(fieldTypeName)) {
                    errors.add("the field " + structClass + "#" + field.getName() + " is not a struct class : "
                            + fieldType.getName());
                }
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsStructClasses() {
        assertStructClasses("org.eclipse.swt.internal.win32.RECT", "org.eclipse.swt.internal.win32.MSG",
                "org.eclipse.swt.internal.win32.NMHDR", "org.eclipse.swt.internal.win32.NMCUSTOMDRAW",
                "org.eclipse.swt.internal.win32.LOGFONT", "org.eclipse.swt.internal.win32.NONCLIENTMETRICS",
                "org.eclipse.swt.internal.gdip.Rect");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxStructClasses() {
        assertStructClasses("org.eclipse.swt.internal.gtk.GdkRectangle", "org.eclipse.swt.internal.gtk.GtkAllocation",
                "org.eclipse.swt.internal.gtk3.GdkEventKey");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macStructClasses() {
        assertStructClasses("org.eclipse.swt.internal.cocoa.NSRect", "org.eclipse.swt.internal.cocoa.NSPoint",
                "org.eclipse.swt.internal.cocoa.CGRect");
    }

    private static void assertStructClasses(String... expected) {
        List<String> missing = new ArrayList<>(List.of(expected));
        missing.removeAll(access.structClasses());
        assertEquals(List.of(), missing, "missing struct classes");
    }

    /**
     * Every callback is a method of the SWT jar, with primitive parameters or a single {@code long[]}.
     */
    @Test
    void callbacksExist() {
        assertFalse(access.callbacks().isEmpty());
        List<String> errors = new ArrayList<>();
        for (SwtNativeAccess.Method method : access.callbacks()) {
            try {
                Class<?>[] parameterTypes = new Class<?>[method.parameterTypes().size()];
                for (int i = 0; i < parameterTypes.length; i++) {
                    parameterTypes[i] = type(method.parameterTypes().get(i));
                    if (!parameterTypes[i].isPrimitive() && parameterTypes[i] != long[].class) {
                        errors.add("not a callback parameter type " + method);
                    }
                }
                type(method.className()).getDeclaredMethod(method.name(), parameterTypes);
            } catch (ReflectiveOperationException e) {
                errors.add("not found " + method + " (" + e + ")");
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsCallbacks() {
        assertCallbacks("org.eclipse.swt.widgets.Display#windowProc(long,long,long,long)",
                "org.eclipse.swt.widgets.Display#messageProc(long,long,long,long)",
                "org.eclipse.swt.widgets.Display#msgFilterProc(long,long,long)",
                "org.eclipse.swt.widgets.Display#foregroundIdleProc(long,long,long)",
                "org.eclipse.swt.widgets.Display#getMsgProc(long,long,long)",
                "org.eclipse.swt.widgets.Display#embeddedProc(long,long,long,long)",
                "org.eclipse.swt.widgets.Display#monitorEnumProc(long,long,long,long)",
                "org.eclipse.swt.widgets.Tracker#transparentProc(long,long,long,long)",
                "org.eclipse.swt.widgets.Tree#CompareFunc(long,long,long)",
                "org.eclipse.swt.graphics.Device#EnumFontFamProc(long,long,long,long)",
                // static callbacks : new Callback(BidiUtil.class, "EnumSystemLanguageGroupsProc", 5)
                "org.eclipse.swt.internal.BidiUtil#EnumSystemLanguageGroupsProc(long,long,long,long,long)",
                "org.eclipse.swt.ole.win32.OleFrame#getMsgProc(long,long,long)",
                // "callback" + i : the prefix of a string concatenation
                "org.eclipse.swt.internal.ole.win32.COMObject#callback0(long[])",
                "org.eclipse.swt.internal.ole.win32.COMObject#callback1(long[])",
                "org.eclipse.swt.internal.ole.win32.COMObject#callback2(long[])",
                "org.eclipse.swt.internal.ole.win32.COMObject#callback40(long[])",
                "org.eclipse.swt.internal.ole.win32.COMObject#callback79(long[])");
        // the Edge callbacks : the methods of the ICoreWebView2Swt* interfaces, on their implementations
        if (jarHas("org/eclipse/swt/browser/Edge$HandleCoreWebView2SwtCallback.class")) {
            assertCallbacks("org.eclipse.swt.browser.Edge$HandleCoreWebView2SwtCallback#Invoke(long,long)",
                    "org.eclipse.swt.internal.ole.win32.ICoreWebView2SwtCallback#Invoke(long,long)");
        }
        if (jarHas("org/eclipse/swt/browser/Edge$HandleCoreWebView2SwtHost.class")) {
            assertCallbacks("org.eclipse.swt.browser.Edge$HandleCoreWebView2SwtHost#CallJava(int,long,long)",
                    "org.eclipse.swt.internal.ole.win32.ICoreWebView2SwtHost#CallJava(int,long,long)");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxCallbacks() {
        assertCallbacks("org.eclipse.swt.widgets.Display#windowProc(long,long)",
                "org.eclipse.swt.widgets.Display#windowProc(long,long,long)",
                "org.eclipse.swt.widgets.Display#windowProc(long,long,long,long)",
                "org.eclipse.swt.widgets.Display#timerProc(long)");
    }

    private static void assertCallbacks(String... expected) {
        List<String> missing = new ArrayList<>(List.of(expected));
        missing.removeAll(callbacks);
        assertEquals(List.of(), missing, "missing callbacks");
    }

    /**
     * The SWT jar of the test class path : the jar of the platform of the build host, which has the
     * {@code org.eclipse.swt.internal.Library} class.
     */
    static Path swtJar() {
        try {
            Class<?> library = Class.forName("org.eclipse.swt.internal.Library", false,
                    SwtNativeAccessTest.class.getClassLoader());
            CodeSource codeSource = library.getProtectionDomain().getCodeSource();
            assertNotNull(codeSource, "no code source for " + library);
            Path jar = Path.of(codeSource.getLocation().toURI());
            assertTrue(Files.isRegularFile(jar), "the SWT jar is not a jar : " + jar);
            return jar;
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new AssertionError("no SWT jar on the test class path", e);
        }
    }

    /**
     * The names of the entries of the SWT jar.
     */
    static List<String> swtJarEntries() {
        try (JarFile jar = new JarFile(swtJar().toFile(), false)) {
            return Collections.list(jar.entries()).stream().map(JarEntry::getName).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean jarHas(String entry) {
        return swtJarEntries().contains(entry);
    }

    /**
     * The class files of a jar, as the build step reads them : their internal names and contents.
     */
    static Consumer<BiConsumer<String, byte[]>> classFiles(Path jar) {
        return consumer -> {
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                for (JarEntry entry : Collections.list(file.entries())) {
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                        try (InputStream in = file.getInputStream(entry)) {
                            consumer.accept(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * A class of the test class path (not initialized), a primitive type, or an array of them ({@code long[]}).
     */
    static Class<?> type(String name) throws ClassNotFoundException {
        if (name.endsWith("[]")) {
            return type(name.substring(0, name.length() - 2)).arrayType();
        }
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive != null ? primitive : Class.forName(name, false, SwtNativeAccessTest.class.getClassLoader());
    }
}
