package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Checks the list conventions documented in {@link SwtClassesAndResources}, and that the entries exist in the SWT jar
 * of the test class path (the SWT jar of the build host) or in the JDK running the tests.
 */
class SwtClassesAndResourcesTest {

    private static final List<String> PLATFORMS = List.of("WINDOWS_", "LINUX_", "MAC_");
    private static final Pattern LIST_NAME = Pattern.compile("(WINDOWS_|LINUX_|MAC_)?([A-Z_]+)");

    private static final String NAME = "[\\w$]+(\\.[\\w$]+)*";
    private static final String TYPE = NAME + "(\\[])*";
    private static final Pattern NAME_ENTRY = Pattern.compile(NAME);
    private static final Pattern METHOD_ENTRY = Pattern
            .compile(NAME + "#(<init>|[\\w$]+)\\((" + TYPE + "(," + TYPE + ")*)?\\)");
    private static final Pattern GLOB_ENTRY = Pattern.compile("[^\\s/]\\S*");

    private static final Map<String, Pattern> KINDS = Map.of(
            "RUNTIME_INITIALIZED_PACKAGES", NAME_ENTRY,
            "REFLECTIVE_CONSTRUCTORS", NAME_ENTRY,
            "NEGATIVE_CLASS_LOOKUPS", NAME_ENTRY,
            "JNI_RUNTIME_ACCESS_CLASSES", NAME_ENTRY,
            "JNI_RUNTIME_ACCESS_METHODS", METHOD_ENTRY,
            "RESOURCE_BUNDLES", NAME_ENTRY,
            "RESOURCE_GLOBS", GLOB_ENTRY,
            "CLASS_PATH_SERVICES", NAME_ENTRY);

    @Test
    void listsFollowTheConventions() throws IllegalAccessException {
        Map<String, Set<String>> lists = new HashMap<>();
        for (Field field : SwtClassesAndResources.class.getDeclaredFields()) {
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
            for (String platform : PLATFORMS) {
                // the build steps select the list of the platform of the SWT jar
                assertNotNull(lists.get(platform + kind), "missing platform list " + platform + kind);
                // an entry needed on every platform belongs to the common list only
                for (String entry : lists.get(platform + kind)) {
                    assertFalse(common.contains(entry), platform + kind + " : " + entry + " is in the common list");
                }
            }
        }
    }

    @Test
    void otherConstantsAreNotLists() {
        for (Field field : SwtClassesAndResources.class.getDeclaredFields()) {
            if (field.getType() != String[].class && !field.isSynthetic()) {
                assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
                        field.getName() + " must be a static final constant");
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
    @EnabledOnOs(OS.WINDOWS)
    void windowsResourcesExist() throws IllegalAccessException {
        assertResourcesExist("WINDOWS_");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxResourcesExist() throws IllegalAccessException {
        assertResourcesExist("LINUX_");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macResourcesExist() throws IllegalAccessException {
        assertResourcesExist("MAC_");
    }

    /**
     * The classes and members of the common lists and of the lists of the given platform exist in the SWT jar of the
     * build host (the SWT jar of the platform) or in the JDK, and the classes expected not to exist do not.
     */
    private static void assertEntriesExist(String platform) throws IllegalAccessException {
        List<String> errors = new ArrayList<>();
        List<String> swtEntries = SwtNativeAccessTest.swtJarEntries();
        for (String entry : entries("RUNTIME_INITIALIZED_PACKAGES", platform)) {
            String directory = entry.replace('.', '/') + "/";
            if (swtEntries.stream().noneMatch(name -> name.startsWith(directory) && name.endsWith(".class"))) {
                errors.add("RUNTIME_INITIALIZED_PACKAGES : no class of the SWT jar in " + entry);
            }
        }
        for (String kind : List.of("REFLECTIVE_CONSTRUCTORS", "JNI_RUNTIME_ACCESS_CLASSES", "CLASS_PATH_SERVICES")) {
            for (String entry : entries(kind, platform)) {
                try {
                    SwtNativeAccessTest.type(entry);
                } catch (ClassNotFoundException e) {
                    errors.add(kind + " : class not found " + entry);
                }
            }
        }
        for (String entry : entries("JNI_RUNTIME_ACCESS_METHODS", platform)) {
            MemberEntry method = MemberEntry.method(entry);
            try {
                Class<?> declaringClass = SwtNativeAccessTest.type(method.className());
                Class<?>[] parameterTypes = new Class<?>[method.parameterTypes().length];
                for (int i = 0; i < parameterTypes.length; i++) {
                    parameterTypes[i] = SwtNativeAccessTest.type(method.parameterTypes()[i]);
                }
                if (method.name().equals("<init>")) {
                    declaringClass.getDeclaredConstructor(parameterTypes);
                } else {
                    declaringClass.getDeclaredMethod(method.name(), parameterTypes);
                }
            } catch (ReflectiveOperationException e) {
                errors.add("JNI_RUNTIME_ACCESS_METHODS : not found " + entry + " (" + e + ")");
            }
        }
        // lookups expected to fail : the classes do not exist
        for (String entry : entries("NEGATIVE_CLASS_LOOKUPS", platform)) {
            try {
                SwtNativeAccessTest.type(entry);
                errors.add("NEGATIVE_CLASS_LOOKUPS : the class exists " + entry);
            } catch (ClassNotFoundException e) {
                // expected
            }
        }
        // a resource bundle is a class or a properties file
        for (String entry : entries("RESOURCE_BUNDLES", platform)) {
            if (!bundleExists(entry)) {
                errors.add("RESOURCE_BUNDLES : bundle not found " + entry);
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    private static boolean bundleExists(String baseName) {
        ClassLoader classLoader = SwtClassesAndResourcesTest.class.getClassLoader();
        try {
            ResourceBundle.getBundle(baseName, Locale.ROOT, classLoader);
            return true;
        } catch (MissingResourceException e) {
            return classLoader.getResource(baseName.replace('.', '/') + ".properties") != null;
        }
    }

    /**
     * Each resource glob of the common list and of the list of the given platform matches a resource of the SWT jar.
     */
    private static void assertResourcesExist(String platform) throws IllegalAccessException {
        List<String> resources = SwtNativeAccessTest.swtJarEntries().stream().filter(name -> !name.endsWith("/"))
                .toList();
        List<String> errors = new ArrayList<>();
        for (String glob : entries("RESOURCE_GLOBS", platform)) {
            Pattern pattern = globPattern(glob);
            if (resources.stream().noneMatch(resource -> pattern.matcher(resource).matches())) {
                errors.add(glob);
            }
        }
        assertTrue(errors.isEmpty(), "no resource of " + SwtNativeAccessTest.swtJar() + " matches " + errors);
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

    private static List<String> entries(String kind, String platform) throws IllegalAccessException {
        List<String> entries = new ArrayList<>();
        for (String list : List.of(kind, platform + kind)) {
            try {
                entries.addAll(Arrays.asList((String[]) SwtClassesAndResources.class.getDeclaredField(list).get(null)));
            } catch (NoSuchFieldException e) {
                // no list for this platform
            }
        }
        return entries;
    }
}
