package io.quarkiverse.desktop.packaging.deployment;

/**
 * The text of the XML files of the packages.
 */
public final class Xml {

    private Xml() {
    }

    /**
     * A text as the value of an attribute or as the content of an element.
     */
    public static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
