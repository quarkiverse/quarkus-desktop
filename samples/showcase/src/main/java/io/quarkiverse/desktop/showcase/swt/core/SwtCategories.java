package io.quarkiverse.desktop.showcase.swt.core;

import java.util.List;
import java.util.Locale;

/**
 * The categories of the SWT pages, in display order : the SWT counterpart of {@code core/Categories}.
 */
public final class SwtCategories {

    public static final String OVERVIEW = "Overview";
    public static final String WIDGETS = "Widgets";
    public static final String CUSTOM = "Custom Widgets";
    public static final String LAYOUTS = "Layouts";
    public static final String GRAPHICS = "Graphics";
    public static final String TEXT = "Text & Fonts";
    public static final String IMAGES = "Images";
    public static final String DESKTOP = "Data Transfer & Desktop";
    public static final String PRINTING = "Printing";
    public static final String A11Y = "Accessibility";

    public static final List<String> ORDER = List.of(OVERVIEW, WIDGETS, CUSTOM, LAYOUTS, GRAPHICS, TEXT, IMAGES,
            DESKTOP, PRINTING, A11Y);

    /**
     * Short keys of the categories (same order as {@link #ORDER}), accepted by {@code -Dshowcase.categories=}.
     */
    public static final List<String> KEYS = List.of("overview", "widgets", "custom", "layouts", "graphics", "text",
            "images", "desktop", "printing", "a11y");

    private SwtCategories() {
    }

    /**
     * The index of {@code category} in {@link #ORDER}, after the last one when it is unknown.
     */
    public static int rank(String category) {
        int index = ORDER.indexOf(category);
        return index < 0 ? ORDER.size() : index;
    }

    /**
     * {@code true} when {@code filter} designates {@code category} : its name or its key, ignoring case.
     */
    public static boolean matches(String filter, String category) {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        int index = ORDER.indexOf(category);
        return f.equals(category.toLowerCase(Locale.ROOT)) || (index >= 0 && f.equals(KEYS.get(index)));
    }
}
