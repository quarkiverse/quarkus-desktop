package io.quarkiverse.desktop.packaging.deployment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(AppInfo.class);

    private static final Pattern NUMBERS = Pattern.compile("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /**
     * A letter or a digit of ASCII.
     */
    private static final Pattern ASCII_LETTER_OR_DIGIT = Pattern.compile("[A-Za-z0-9]");

    /**
     * The Latin letters that do not decompose into a letter of ASCII and accents, and their spelling in ASCII (a
     * capital letter at the start of a word : {@code Æsir} is {@code Aesir}).
     */
    private static final String[][] LETTERS = { { "ß", "ss" }, { "ẞ", "SS" }, { "æ", "ae" }, { "Æ", "Ae" },
            { "œ", "oe" }, { "Œ", "Oe" }, { "ø", "o" }, { "Ø", "O" }, { "ð", "d" }, { "Ð", "D" }, { "đ", "d" },
            { "Đ", "D" }, { "þ", "th" }, { "Þ", "Th" }, { "ħ", "h" }, { "Ħ", "H" }, { "ı", "i" }, { "ł", "l" },
            { "Ł", "L" }, { "ŋ", "ng" }, { "Ŋ", "Ng" }, { "ŧ", "t" }, { "Ŧ", "T" } };

    /**
     * A dash, or a dot after a digit, before a pre-release qualifier ({@code -SNAPSHOT}, {@code -rc1}, {@code -M2},
     * {@code .Beta3}) : the versions that Maven sorts before the release.
     */
    private static final Pattern PRE_RELEASE = Pattern.compile("(?:-|(?<=\\d)\\.)(?=(?i)(?:alpha|beta|milestone|rc|cr"
            + "|snapshot|preview|pre|dev|ea|[abm](?=\\d))(?:[\\d._+-]|$))");

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
        if (!NUMBERS.matcher(version).find()) {
            LOGGER.warnf("The version %s has no leading number : the Windows and macOS packages get the version 1.0.0"
                    + " (set quarkus.desktop.packaging.version to a version that starts with a number)", version);
        }
        // the default package name and identifier are in ASCII : for a name without letters or digits there (a name in
        // Arabic, Chinese or Russian), they come from quarkus.application.name (the artifact ID), rather than from the
        // word application, which the other applications of the vendor would have too
        String source = hasAsciiLetterOrDigit(name) || !isSet(applicationName) ? name : applicationName;
        Optional<String> configuredPackageName = config.linux().packageName().map(String::trim)
                .filter(value -> !value.isEmpty());
        String packageName = configuredPackageName.orElseGet(() -> packageName(source));
        if (!packageName.matches("[a-z0-9][a-z0-9+.-]+")) {
            throw new PackagingException("quarkus.desktop.packaging.linux.package-name=\"" + packageName + "\" : a"
                    + " package name has at least two characters among lowercase letters, digits, dashes, dots and plus"
                    + " signs, and starts with a letter or a digit");
        }
        Optional<String> configuredIdentifier = config.identifier().map(String::trim).filter(value -> !value.isEmpty());
        String identifier = configuredIdentifier.orElseGet(() -> identifier(groupId, source));
        if (!identifier.matches("[A-Za-z0-9_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_][A-Za-z0-9_-]*)+")) {
            throw new PackagingException("quarkus.desktop.packaging.identifier=\"" + identifier + "\" : an identifier is"
                    + " a reverse domain name such as org.acme.editor, whose elements have letters, digits, dashes and"
                    + " underscores, and do not start with a dash");
        }
        if (!hasAsciiLetterOrDigit(source) && (configuredPackageName.isEmpty() || configuredIdentifier.isEmpty())) {
            LOGGER.warnf("The name of the application, %s, has no letter or digit in ASCII, nor has"
                    + " quarkus.application.name : its identifier is %s and its Linux package name %s, which other"
                    + " applications may have too. Set quarkus.desktop.packaging.identifier and"
                    + " quarkus.desktop.packaging.linux.package-name", name, identifier, packageName);
        }
        Optional<String> license = config.licenseFile().map(String::trim).filter(value -> !value.isEmpty());
        Optional<Path> licenseFile;
        try {
            licenseFile = license.map(projectDirectory::resolve);
        } catch (InvalidPathException e) {
            throw new PackagingException("quarkus.desktop.packaging.license-file=\"" + license.get() + "\" : not a valid"
                    + " path (" + e.getReason() + ")");
        }
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
     * A name in ASCII where it can be : without accents ({@code É} is {@code E}), with the compatibility characters
     * decomposed ({@code ﬁ} is {@code fi}, {@code ²} is {@code 2}), and the Latin letters that do not decompose
     * spelled in ASCII ({@code ß} is {@code ss}). The other characters stay as they are.
     */
    static String ascii(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        for (String[] letter : LETTERS) {
            ascii = ascii.replace(letter[0], letter[1]);
        }
        return ascii;
    }

    /**
     * Whether a name has a letter or a digit in ASCII, once in ASCII where it can be.
     */
    private static boolean hasAsciiLetterOrDigit(String name) {
        return ASCII_LETTER_OR_DIGIT.matcher(ascii(name)).find();
    }

    /**
     * Checks that the name of the application can name its files on an operating system : its executable, its
     * directory and its shortcuts on Windows, its bundle on macOS, which {@code jpackage} names in JVM mode (the files
     * of the Linux packages are named after the package name). On Windows, the installation directory has no semicolon
     * either : the launcher of a JVM application does not start from a directory whose path has one.
     *
     * @param installDirectory {@code quarkus.desktop.packaging.windows.install-directory}
     * @throws PackagingException for a name with a control character or with a character that the file names of the
     *         operating system or {@code jpackage} reject, for an installation directory with a semicolon
     */
    static void checkName(String name, Optional<String> installDirectory, TargetOs os) {
        // the characters of the file names of Windows, and the ones that jpackage rejects
        String rejected = switch (os) {
            case WINDOWS -> "\\/:*?\"<>|%;";
            case MACOS -> "/\"%";
            case LINUX -> "";
        };
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || rejected.indexOf(c) >= 0
                    || os != TargetOs.LINUX && c != ' ' && Character.isWhitespace(c)) {
                String rule = switch (os) {
                    case WINDOWS -> "names its executable, its directory and its shortcuts on Windows, which cannot have"
                            + " \\ / : * ? \" < > | % ; (a JVM application does not start from a path with a"
                            + " semicolon), control characters or white space other than spaces";
                    case MACOS -> "names its bundle and its executable on macOS, which cannot have / \" %, control"
                            + " characters or white space other than spaces";
                    case LINUX -> "cannot have control characters";
                };
                throw new PackagingException("quarkus.desktop.packaging.name=\"" + name + "\" : the name of the"
                        + " application " + rule + " : set quarkus.desktop.packaging.name to another name");
            }
        }
        if (os == TargetOs.WINDOWS && installDirectory.filter(directory -> directory.indexOf(';') >= 0).isPresent()) {
            throw new PackagingException("quarkus.desktop.packaging.windows.install-directory=\"" + installDirectory.get()
                    + "\" : the launcher of a JVM application does not start on Windows from a directory whose path"
                    + " has a semicolon : remove it");
        }
    }

    /**
     * A name as a package name, in ASCII : {@code Acme Editor} is {@code acme-editor}, {@code Café Manager} is
     * {@code cafe-manager}.
     */
    static String packageName(String name) {
        String value = ascii(name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return value.length() < 2 ? "application" : value;
    }

    /**
     * The default identifier : the group ID of the project and the name of the application, as an element of a
     * reverse domain name, in ASCII ({@code Acme Éditeur} is {@code AcmeEditeur}).
     */
    static String identifier(String groupId, String name) {
        String element = ascii(name).replaceAll("[^A-Za-z0-9_]+", "");
        if (element.isEmpty()) {
            element = "application";
        }
        List<String> elements = new ArrayList<>();
        for (String part : (groupId == null ? "" : groupId).split("\\.")) {
            String cleaned = part.replaceAll("[^A-Za-z0-9_-]+", "");
            if (!cleaned.isEmpty()) {
                elements.add(cleaned);
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
     * {@code 1.2.3} with three parts and {@code 1.2.3.0} with four ; a leading {@code v} is ignored ({@code v1.2.3} is
     * {@code 1.2.3}) ; a version without leading number is {@code 1.0.0}.
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
     * The version as Debian and RPM packages write it : no leading {@code v}, no dash (it separates the version from
     * the release), a tilde before a pre-release qualifier, which sorts {@code 1.0~SNAPSHOT} and {@code 1.0~rc1} before
     * {@code 1.0}, and a dot before any other suffix, which sorts {@code 1.0.SP1} and {@code 1.0.2} after {@code 1.0}.
     */
    public String linuxVersion() {
        String value = PRE_RELEASE.matcher(version.replaceFirst("^[vV](?=\\d)", "")).replaceAll("~").replace('-', '.')
                .replaceAll("[^A-Za-z0-9.+~]", ".");
        return Character.isDigit(value.charAt(0)) ? value : "0." + value;
    }

    /**
     * The name of the application as the base name of a file, in ASCII : without the characters that file systems,
     * shells or packaging tools reject, and without separators at its ends ({@code Acme محرر} is {@code Acme}). The
     * package name for a name without letters or digits there.
     */
    public String fileName() {
        String value = ascii(name).trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9._-]", "")
                .replaceAll("-{2,}", "-").replaceAll("^[-._]+|[-._]+$", "");
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
