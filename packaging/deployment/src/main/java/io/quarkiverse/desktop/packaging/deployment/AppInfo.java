package io.quarkiverse.desktop.packaging.deployment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the packages say about the application : its names, version and vendor, from the configuration and the project.
 *
 * @param name the name that the users see ({@code Acme Editor})
 * @param packageName the name of the Linux packages and command ({@code acme-editor})
 * @param identifier the reverse domain name of the application ({@code org.acme.editor})
 * @param version the version, as the project writes it ({@code 1.2.3-SNAPSHOT})
 * @param vendor the vendor
 * @param description a one line description
 * @param copyright the copyright notice
 * @param homepage the home page
 * @param licenseFile the license, as a text file
 */
public record AppInfo(String name, String packageName, String identifier, String version, String vendor,
        String description, Optional<String> copyright, Optional<String> homepage, Optional<Path> licenseFile) {

    /**
     * The value of the name and version of an application that has none (the {@code UNSET_VALUE} of
     * {@code ApplicationInfoBuildItem}).
     */
    static final String UNSET = "<<unset>>";

    private static final Pattern NUMBERS = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /**
     * @param applicationName {@code quarkus.application.name}
     * @param applicationVersion {@code quarkus.application.version}
     * @param groupId the group ID of the project, possibly empty
     * @param projectDirectory the directory of the relative paths of the configuration
     */
    static AppInfo of(DesktopPackagingConfig config, String applicationName, String applicationVersion, String groupId,
            Path projectDirectory) {
        String name = config.name().map(String::trim).filter(value -> !value.isEmpty())
                .orElse(isSet(applicationName) ? applicationName : "application");
        String version = config.version().map(String::trim).filter(value -> !value.isEmpty())
                .orElse(isSet(applicationVersion) ? applicationVersion : "1.0.0");
        String packageName = config.linux().packageName().orElseGet(() -> packageName(name));
        if (!packageName.matches("[a-z0-9][a-z0-9+.-]+")) {
            throw new PackagingException("quarkus.desktop.packaging.linux.package-name=" + packageName + " : a package"
                    + " name has at least two characters among lowercase letters, digits, dashes, dots and plus"
                    + " signs, and starts with a letter or a digit");
        }
        String identifier = config.identifier().orElseGet(() -> identifier(groupId, name));
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)+")) {
            throw new PackagingException("quarkus.desktop.packaging.identifier=" + identifier + " : an identifier is a"
                    + " reverse domain name such as org.acme.editor, whose elements have letters, digits, dashes and"
                    + " underscores, and do not start with a digit");
        }
        Optional<Path> licenseFile = config.licenseFile().map(projectDirectory::resolve);
        if (licenseFile.isPresent() && !Files.isRegularFile(licenseFile.get())) {
            throw new PackagingException("quarkus.desktop.packaging.license-file : " + licenseFile.get() + " not found");
        }
        return new AppInfo(name, packageName, identifier, version, config.vendor(), config.description().orElse(name),
                config.copyright(), config.homepage(), licenseFile);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !UNSET.equals(value);
    }

    /**
     * A name as a package name : {@code Acme Editor} is {@code acme-editor}.
     */
    static String packageName(String name) {
        String value = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return value.length() < 2 ? "application" : value;
    }

    /**
     * The default identifier : the group ID of the project and the name of the application, as an element of a
     * reverse domain name.
     */
    static String identifier(String groupId, String name) {
        String element = name.replaceAll("[^A-Za-z0-9_]+", "");
        if (element.isEmpty()) {
            element = "application";
        }
        if (Character.isDigit(element.charAt(0))) {
            element = "_" + element;
        }
        List<String> elements = new ArrayList<>();
        for (String part : (groupId == null ? "" : groupId).split("\\.")) {
            String cleaned = part.replaceAll("[^A-Za-z0-9_-]+", "");
            if (!cleaned.isEmpty()) {
                elements.add(Character.isDigit(cleaned.charAt(0)) ? "_" + cleaned : cleaned);
            }
        }
        if (elements.isEmpty()) {
            elements.add("local");
            elements.add("application");
        }
        elements.add(element);
        return String.join(".", elements);
    }

    /**
     * The leading numbers of the version, for the formats that take numbers only : {@code 1.2.3-SNAPSHOT} is
     * {@code 1.2.3} with three parts and {@code 1.2.3.0} with four ; a version without leading number is {@code 1.0.0}.
     */
    public String numericVersion(int parts) {
        Matcher matcher = NUMBERS.matcher(version);
        boolean found = matcher.find();
        List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= parts; i++) {
            String number = found && i <= 4 ? matcher.group(i) : null;
            if (number == null) {
                numbers.add(!found && i == 1 ? "1" : "0");
            } else {
                // no leading zeros, and no number that overflows the fields of the formats
                String trimmed = number.replaceFirst("^0+(?=\\d)", "");
                numbers.add(trimmed.length() > 9 ? "999999999" : trimmed);
            }
        }
        return String.join(".", numbers);
    }

    /**
     * The version as Debian and RPM packages write it : no dash (it separates the version from the release), a tilde
     * before a qualifier, which sorts {@code 1.0~SNAPSHOT} before {@code 1.0}.
     */
    public String linuxVersion() {
        String value = version.replace('-', '~').replaceAll("[^A-Za-z0-9.+~]", ".");
        return Character.isDigit(value.charAt(0)) ? value : "0." + value;
    }

    /**
     * The name of the application as the base name of a file : without the characters that file systems, shells or
     * packaging tools reject.
     */
    public String fileName() {
        String value = name.trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9._-]", "");
        return value.isEmpty() ? packageName : value;
    }

    /**
     * A GUID derived from the identifier of the application and a purpose : the same for every build.
     */
    public String guid(String purpose) {
        return UUID.nameUUIDFromBytes(("quarkus-desktop-packaging:" + purpose + ":" + identifier)
                .getBytes(StandardCharsets.UTF_8)).toString().toUpperCase(Locale.ROOT);
    }
}
