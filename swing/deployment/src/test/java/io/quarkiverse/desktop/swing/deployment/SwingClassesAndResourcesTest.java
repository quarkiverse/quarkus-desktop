package io.quarkiverse.desktop.swing.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.quarkiverse.desktop.awt.deployment.AwtClassesAndResources;
import io.quarkiverse.desktop.awt.deployment.MemberEntry;

/**
 * Checks the list conventions documented in {@link SwingClassesAndResources}, that the entries exist in the JDK
 * running the tests, and that they are not registered by the Desktop AWT extension already.
 */
class SwingClassesAndResourcesTest {

    private static final List<String> PLATFORMS = List.of("WINDOWS_", "LINUX_", "MAC_");
    private static final Pattern LIST_NAME = Pattern.compile("(WINDOWS_|LINUX_|MAC_)?([A-Z_]+)");

    private static final String NAME = "[\\w$]+(\\.[\\w$]+)*";
    private static final String TYPE = NAME + "(\\[])*";
    private static final Pattern NAME_ENTRY = Pattern.compile(NAME);
    private static final Pattern TYPE_ENTRY = Pattern.compile(TYPE);
    private static final Pattern METHOD_ENTRY = Pattern
            .compile(NAME + "#(<init>|[\\w$]+)\\((" + TYPE + "(," + TYPE + ")*)?\\)");
    private static final Pattern FIELD_ENTRY = Pattern.compile(NAME + "#[\\w$]+");
    private static final Pattern GLOB_ENTRY = Pattern.compile("[^\\s/]\\S*");

    private static final Map<String, Pattern> KINDS = Map.ofEntries(
            Map.entry("RUNTIME_INITIALIZED_PACKAGES", NAME_ENTRY),
            Map.entry("RUNTIME_INITIALIZED_CLASSES", NAME_ENTRY),
            Map.entry("REFLECTIVE_CLASSES", TYPE_ENTRY),
            Map.entry("REFLECTIVE_FIELD_CLASSES", NAME_ENTRY),
            Map.entry("REFLECTIVE_CONSTRUCTORS", TYPE_ENTRY),
            Map.entry("REFLECTIVE_METHODS", METHOD_ENTRY),
            Map.entry("REFLECTIVE_FIELDS", FIELD_ENTRY),
            Map.entry("REFLECTIVE_PUBLIC_MEMBERS", NAME_ENTRY),
            Map.entry("JAVA_BEANS_CLASSES", NAME_ENTRY),
            Map.entry("REFLECTIVE_TYPES", NAME_ENTRY),
            Map.entry("NEGATIVE_CLASS_LOOKUPS", NAME_ENTRY),
            Map.entry("METHOD_LOOKUPS", METHOD_ENTRY),
            Map.entry("JNI_RUNTIME_ACCESS_CLASSES", NAME_ENTRY),
            Map.entry("JNI_RUNTIME_ACCESS_METHODS", METHOD_ENTRY),
            Map.entry("JNI_RUNTIME_ACCESS_FIELDS", FIELD_ENTRY),
            Map.entry("RESOURCE_BUNDLES", NAME_ENTRY),
            Map.entry("RESOURCE_GLOBS", GLOB_ENTRY),
            Map.entry("RESOURCE_LOOKUPS", GLOB_ENTRY),
            Map.entry("SERVICE_PROVIDERS", NAME_ENTRY));

    private static final Map<String, Class<?>> PRIMITIVES = Map.of("boolean", boolean.class, "byte", byte.class,
            "char", char.class, "short", short.class, "int", int.class, "long", long.class, "float", float.class,
            "double", double.class);

    @Test
    void listsFollowTheConventions() throws IllegalAccessException {
        Map<String, Set<String>> lists = new HashMap<>();
        for (Field field : SwingClassesAndResources.class.getDeclaredFields()) {
            if (field.getType() != String[].class) {
                continue;
            }
            String list = field.getName();
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers)
                    && (modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0,
                    list + " must be a package-private static field");
            Matcher name = LIST_NAME.matcher(list);
            assertTrue(name.matches() && KINDS.containsKey(name.group(2)),
                    list + " must be named [WINDOWS_|LINUX_|MAC_]KIND, with KIND in " + KINDS.keySet());
            String[] entries = (String[]) field.get(null);
            Set<String> unique = new LinkedHashSet<>(Arrays.asList(entries));
            assertEquals(entries.length, unique.size(), list + " has duplicate entries");
            for (String entry : entries) {
                assertTrue(KINDS.get(name.group(2)).matcher(entry).matches(), list + " : malformed entry " + entry);
            }
            lists.put(list, unique);
        }
        for (String kind : KINDS.keySet()) {
            Set<String> common = lists.get(kind);
            assertNotNull(common, "missing common list " + kind);
            // an entry needed on every platform belongs to the common list only
            for (String platform : PLATFORMS) {
                for (String entry : lists.getOrDefault(platform + kind, Set.of())) {
                    assertFalse(common.contains(entry), platform + kind + " : " + entry + " is in the common list");
                }
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsEntriesExist() throws IllegalAccessException {
        assertEntriesExist("WINDOWS_");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxEntriesExist() throws IllegalAccessException {
        assertEntriesExist("LINUX_");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macEntriesExist() throws IllegalAccessException {
        assertEntriesExist("MAC_");
    }

    @Test
    void entriesAreNotInTheAwtLists() throws IllegalAccessException {
        List<String> duplicates = new ArrayList<>();
        for (Field field : SwingClassesAndResources.class.getDeclaredFields()) {
            if (field.getType() != String[].class) {
                continue;
            }
            Matcher name = LIST_NAME.matcher(field.getName());
            assertTrue(name.matches(), field.getName());
            Set<String> awt = new LinkedHashSet<>();
            // an AWT entry for all platforms, or for the platform of the Swing list
            for (String list : name.group(1) == null ? List.of(name.group(2), "WINDOWS_" + name.group(2),
                    "LINUX_" + name.group(2), "MAC_" + name.group(2)) : List.of(name.group(2), field.getName())) {
                awt.addAll(awtList(list));
            }
            if (name.group(1) == null) {
                // a Swing entry for all platforms that AWT registers for one platform only is fine
                awt.retainAll(awtList(name.group(2)));
            }
            for (String entry : (String[]) field.get(null)) {
                if (awt.contains(entry)) {
                    duplicates.add(field.getName() + " : " + entry);
                }
            }
        }
        assertTrue(duplicates.isEmpty(), "registered by the Desktop AWT extension : " + duplicates);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsResourcesExist() throws IOException, IllegalAccessException {
        assertResourcesExist("WINDOWS_");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxResourcesExist() throws IOException, IllegalAccessException {
        assertResourcesExist("LINUX_");
    }

    /**
     * Each resource glob of the common list and of the list of the given platform matches a resource of the JDK.
     */
    private static void assertResourcesExist(String platform) throws IOException, IllegalAccessException {
        List<String> resources;
        Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
        try (Stream<Path> paths = Files.walk(modules)) {
            resources = paths.filter(Files::isRegularFile).map(path -> modules.relativize(path).toString())
                    // without the module name
                    .map(path -> path.substring(path.indexOf('/') + 1)).toList();
        }
        List<String> errors = new ArrayList<>();
        for (String glob : entries("RESOURCE_GLOBS", platform)) {
            Pattern pattern = globPattern(glob);
            if (resources.stream().noneMatch(resource -> pattern.matcher(resource).matches())) {
                errors.add(glob);
            }
        }
        assertTrue(errors.isEmpty(), "no JDK resource matches " + errors);
        // the lookups expected to fail find nothing, else they are resource globs
        List<String> found = new ArrayList<>();
        for (String glob : entries("RESOURCE_LOOKUPS", platform)) {
            Pattern pattern = globPattern(glob);
            if (resources.stream().anyMatch(resource -> pattern.matcher(resource).matches())) {
                found.add(glob);
            }
        }
        assertTrue(found.isEmpty(), "JDK resources match the resource lookups " + found);
    }

    /**
     * The regular expression of a resource glob : {@code **} matches across directories, {@code *} within one.
     */
    static Pattern globPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static List<String> awtList(String list) throws IllegalAccessException {
        try {
            Field field = AwtClassesAndResources.class.getDeclaredField(list);
            field.setAccessible(true);
            return Arrays.asList((String[]) field.get(null));
        } catch (NoSuchFieldException e) {
            return List.of();
        }
    }

    /**
     * The classes and members of the common lists and of the lists of the given platform exist in the JDK of the build
     * (the JDK of the platform).
     */
    private static void assertEntriesExist(String platform) throws IllegalAccessException {
        List<String> errors = new ArrayList<>();
        for (String kind : List.of("REFLECTIVE_CLASSES", "REFLECTIVE_FIELD_CLASSES", "REFLECTIVE_CONSTRUCTORS",
                "REFLECTIVE_TYPES",
                "JNI_RUNTIME_ACCESS_CLASSES",
                "RUNTIME_INITIALIZED_CLASSES", "SERVICE_PROVIDERS", "RESOURCE_BUNDLES")) {
            for (String entry : entries(kind, platform)) {
                try {
                    type(entry);
                } catch (ClassNotFoundException e) {
                    // a resource bundle is a class or a properties file of a JDK module
                    if (!kind.equals("RESOURCE_BUNDLES") || !jdkResourceExists(entry.replace('.', '/') + ".properties")) {
                        errors.add(kind + " : class not found " + entry);
                    }
                }
            }
        }
        // only the public members of these classes are registered : they are public classes
        for (String kind : List.of("REFLECTIVE_PUBLIC_MEMBERS", "JAVA_BEANS_CLASSES")) {
            for (String entry : entries(kind, platform)) {
                try {
                    for (Class<?> type = type(entry); type != null; type = type.getDeclaringClass()) {
                        if (!Modifier.isPublic(type.getModifiers())) {
                            errors.add(kind + " : not a public class " + entry);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    errors.add(kind + " : class not found " + entry);
                }
            }
        }
        for (String kind : List.of("REFLECTIVE_METHODS", "JNI_RUNTIME_ACCESS_METHODS")) {
            for (String entry : entries(kind, platform)) {
                MemberEntry method = MemberEntry.method(entry);
                try {
                    Class<?> declaringClass = type(method.className());
                    Class<?>[] parameterTypes = new Class<?>[method.parameterTypes().length];
                    for (int i = 0; i < parameterTypes.length; i++) {
                        parameterTypes[i] = type(method.parameterTypes()[i]);
                    }
                    if (method.name().equals("<init>")) {
                        declaringClass.getDeclaredConstructor(parameterTypes);
                    } else {
                        declaringClass.getDeclaredMethod(method.name(), parameterTypes);
                    }
                } catch (ReflectiveOperationException e) {
                    errors.add(kind + " : not found " + entry + " (" + e + ")");
                }
            }
        }
        // lookups expected to fail : the classes do not exist
        for (String entry : entries("NEGATIVE_CLASS_LOOKUPS", platform)) {
            try {
                type(entry);
                errors.add("NEGATIVE_CLASS_LOOKUPS : the class exists " + entry);
            } catch (ClassNotFoundException e) {
                // expected
            }
        }
        // method lookups : the class and the parameter types exist, the class may not declare the method
        for (String entry : entries("METHOD_LOOKUPS", platform)) {
            MemberEntry method = MemberEntry.method(entry);
            try {
                type(method.className());
                for (String parameterType : method.parameterTypes()) {
                    type(parameterType);
                }
            } catch (ClassNotFoundException e) {
                errors.add("METHOD_LOOKUPS : class not found " + entry);
            }
        }
        for (String kind : List.of("REFLECTIVE_FIELDS", "JNI_RUNTIME_ACCESS_FIELDS")) {
            for (String entry : entries(kind, platform)) {
                MemberEntry field = MemberEntry.field(entry);
                try {
                    type(field.className()).getDeclaredField(field.name());
                } catch (ReflectiveOperationException e) {
                    errors.add(kind + " : not found " + entry);
                }
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    private static List<String> entries(String kind, String platform) throws IllegalAccessException {
        List<String> entries = new ArrayList<>();
        for (String list : List.of(kind, platform + kind)) {
            try {
                entries.addAll(Arrays.asList((String[]) SwingClassesAndResources.class.getDeclaredField(list).get(null)));
            } catch (NoSuchFieldException e) {
                // no list for this platform
            }
        }
        return entries;
    }

    /**
     * Whether a resource exists in a module of the JDK running the tests (the resources of the packages of a module that
     * are not open cannot be read with a class loader).
     */
    private static boolean jdkResourceExists(String path) {
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        return ModuleLayer.boot().modules().stream()
                .anyMatch(module -> Files.exists(jrt.getPath("/modules", module.getName(), path)));
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        if (name.endsWith("[]")) {
            return type(name.substring(0, name.length() - 2)).arrayType();
        }
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive != null ? primitive
                : Class.forName(name, false, SwingClassesAndResourcesTest.class.getClassLoader());
    }
}
