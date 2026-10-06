package io.quarkiverse.desktop.swt.deployment;

/**
 * A class member entry of the registration lists ({@link SwtClassesAndResources}) : {@code "fqcn#name(paramType,...)"}
 * for a method or constructor ({@code <init>}), {@code "fqcn#field"} for a field. The same format as the lists of the
 * Desktop AWT extension.
 *
 * @param className the binary name of the declaring class
 * @param name the name of the member
 * @param parameterTypes the parameter types of a method (binary names, primitive names, {@code []} suffixes for
 *        arrays), empty for a field
 */
record MemberEntry(String className, String name, String[] parameterTypes) {

    /**
     * Parses a method or constructor entry, {@code "fqcn#name(paramType,...)"}.
     */
    static MemberEntry method(String entry) {
        int hash = entry.indexOf('#');
        int open = entry.indexOf('(', hash);
        if (hash < 0 || open < 0 || !entry.endsWith(")")) {
            throw new IllegalArgumentException("Not a method entry : " + entry);
        }
        String parameters = entry.substring(open + 1, entry.length() - 1);
        return new MemberEntry(entry.substring(0, hash), entry.substring(hash + 1, open),
                parameters.isEmpty() ? new String[0] : parameters.split(","));
    }

    /**
     * Parses a field entry, {@code "fqcn#field"}.
     */
    static MemberEntry field(String entry) {
        int hash = entry.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("Not a field entry : " + entry);
        }
        return new MemberEntry(entry.substring(0, hash), entry.substring(hash + 1), new String[0]);
    }
}
