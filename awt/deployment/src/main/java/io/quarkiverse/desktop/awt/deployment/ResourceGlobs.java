package io.quarkiverse.desktop.awt.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;

/**
 * The resource globs of the lists, registered for native executables. Most of them address resources of the modules of
 * the JDK (the icons of the look and feels, the cursors, the print dialog icons...), which the JDK loads with
 * {@code Class.getResourceAsStream} from its own classes.
 * <p>
 * Quarkus writes the resources in the reachability metadata format, where a glob without a module only matches the
 * resources of the class path : the globs are registered in each module of the JDK that has their directory
 * ({@code NativeImageResourcePatternsBuildItem.Builder.module(String)}), and on the class path when no module has it.
 * Used by the Desktop AWT and Desktop Swing extensions.
 */
public final class ResourceGlobs {

    /**
     * The class path, in {@link #byModule(Collection)}.
     */
    static final String CLASS_PATH = "";

    private ResourceGlobs() {
    }

    /**
     * The build items that register the given globs.
     */
    public static List<NativeImageResourcePatternsBuildItem> patterns(Collection<String> globs) {
        List<NativeImageResourcePatternsBuildItem> items = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : byModule(globs).entrySet()) {
            NativeImageResourcePatternsBuildItem.Builder builder = NativeImageResourcePatternsBuildItem.builder()
                    .includeGlobs(entry.getValue());
            if (!entry.getKey().equals(CLASS_PATH)) {
                builder.module(entry.getKey());
            }
            items.add(builder.build());
        }
        return items;
    }

    /**
     * The globs grouped by the modules of the JDK that have their directory ({@link #CLASS_PATH} for the others).
     */
    static Map<String, Set<String>> byModule(Collection<String> globs) {
        Map<String, Set<String>> byModule = new TreeMap<>();
        for (String glob : globs) {
            Set<String> modules = ReachabilityLookups.modules(glob);
            for (String module : modules.isEmpty() ? Set.of(CLASS_PATH) : modules) {
                byModule.computeIfAbsent(module, m -> new java.util.TreeSet<>()).add(glob);
            }
        }
        return byModule;
    }
}
