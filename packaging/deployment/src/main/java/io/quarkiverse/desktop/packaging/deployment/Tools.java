package io.quarkiverse.desktop.packaging.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

/**
 * The packaging tools of the machine of the build : where they are, and how they run.
 */
public final class Tools {

    private static final Logger LOGGER = Logger.getLogger(Tools.class);

    private final Map<String, String> configured;

    /**
     * The directory of the relative paths of the configuration.
     */
    private final Path projectDirectory;

    /**
     * @param configured the paths of the configuration, by tool name ({@code quarkus.desktop.packaging.tools})
     * @param projectDirectory the directory of the relative paths of the configuration
     */
    public Tools(Map<String, String> configured, Path projectDirectory) {
        this.configured = configured;
        this.projectDirectory = projectDirectory;
    }

    /**
     * The tools of the {@code PATH}, and of paths that are absolute or relative to the working directory of the build.
     *
     * @param configured the paths, by tool name
     */
    public Tools(Map<String, String> configured) {
        this(configured, Path.of(""));
    }

    /**
     * The output of a tool.
     *
     * @param exitCode the exit code of the process
     * @param output its standard output and error streams
     */
    public record Result(int exitCode, String output) {

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    /**
     * The absolute path of a tool : the one of the configuration, the first one of the {@code PATH}, or the first
     * existing one of the given locations. The tools run in other working directories than the one of the build.
     *
     * @param name the name of the tool, without extension
     * @param locations where the installer of the tool puts it, when that is not on the {@code PATH}
     */
    public Optional<Path> find(String name, Path... locations) {
        Optional<Path> tool = configured(name);
        if (tool.isPresent()) {
            return tool;
        }
        Optional<Path> onPath = onPath(name);
        if (onPath.isPresent()) {
            return onPath;
        }
        for (Path location : locations) {
            if (location != null && Files.isRegularFile(location)) {
                return Optional.of(location);
            }
        }
        return Optional.empty();
    }

    /**
     * The absolute path of a tool in the configuration ({@code quarkus.desktop.packaging.tools.<name>}), if any : a
     * relative path is relative to the directory of the project.
     *
     * @param name the name of the tool, without extension
     * @throws PackagingException when the configured file does not exist
     */
    public Optional<Path> configured(String name) {
        String path = configured.get(name);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Path tool = projectDirectory.resolve(path.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(tool)) {
            throw new PackagingException("quarkus.desktop.packaging.tools." + name + " : " + tool + " not found");
        }
        return Optional.of(tool);
    }

    /**
     * The path of a tool that a package needs.
     *
     * @param hint how to install the tool
     * @throws PackagingException when the tool is not found
     */
    public Path require(String name, String hint, Path... locations) {
        return find(name, locations).orElseThrow(() -> new PackagingException(name + " not found : " + hint
                + " (or set quarkus.desktop.packaging.tools." + name + " to its path)"));
    }

    private static Optional<Path> onPath(String name) {
        return onPath(name, System.getenv("PATH"));
    }

    /**
     * The first file of a tool in the directories of a {@code PATH}, as an absolute path : a relative directory is
     * relative to the working directory of the build.
     *
     * @param path the directories, or {@code null}
     */
    static Optional<Path> onPath(String name, String path) {
        if (path == null) {
            return Optional.empty();
        }
        boolean windows = TargetOs.host() == TargetOs.WINDOWS;
        List<String> extensions = new ArrayList<>();
        if (windows) {
            extensions.addAll(List.of(".exe", ".cmd", ".bat"));
        } else {
            extensions.add("");
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                try {
                    Path candidate = Path.of(directory.trim()).resolve(name + extension);
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                        return Optional.of(candidate.toAbsolutePath());
                    }
                } catch (RuntimeException e) {
                    // an entry of the PATH that is not a path
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Runs a tool and returns its output, whatever its exit code.
     *
     * @param directory the working directory, or {@code null} for the one of the build
     * @param environment more environment variables
     */
    public static Result run(Path directory, Map<String, String> environment, List<String> command) {
        return run(directory, environment, command, false);
    }

    /**
     * Runs a tool and returns its output, whatever its exit code.
     *
     * @param confidential whether the command has a secret (the password of a certificate) : it is not logged then
     * @throws PackagingException for a batch file of Windows, with an argument that {@code cmd.exe} would change
     */
    public static Result run(Path directory, Map<String, String> environment, List<String> command,
            boolean confidential) {
        LOGGER.debugf("Running %s%s", confidential ? command.get(0) + " (arguments not logged)" : command,
                directory == null ? "" : " in " + directory);
        List<String> arguments = command;
        if (TargetOs.host() == TargetOs.WINDOWS) {
            arguments = isBatchFile(command.get(0)) ? batchCommand(command)
                    : command.stream().map(Tools::windowsArgument).toList();
        }
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            String output = decode(process.getInputStream().readAllBytes(), nativeEncoding());
            int exitCode = process.waitFor();
            LOGGER.debugf("%s exited with %d : %s", command.get(0), exitCode, output);
            return new Result(exitCode, output);
        } catch (IOException e) {
            throw new PackagingException("Unable to run " + command.get(0) + " : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PackagingException("Interrupted while running " + command.get(0), e);
        }
    }

    /**
     * An argument escaped for the parsing of the command line by the C runtime of Windows : {@code ProcessBuilder}
     * quotes an argument that has a space, but not the double quotes inside it, which would end the argument there.
     * A double quote gets a backslash, and the backslashes before it are doubled.
     */
    static String windowsArgument(String argument) {
        if (argument.indexOf('"') < 0) {
            return argument;
        }
        StringBuilder escaped = new StringBuilder();
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            escaped.append("\\".repeat(c == '"' ? 2 * backslashes + 1 : backslashes)).append(c);
            backslashes = 0;
        }
        // the backslashes at the end stay as they are : ProcessBuilder doubles the last one when it quotes the argument
        return escaped.append("\\".repeat(backslashes)).toString();
    }

    /**
     * Whether a tool is a batch file ({@code .cmd}, {@code .bat}), which Windows runs with {@code cmd.exe} : a
     * wrapper of the configuration ({@code quarkus.desktop.packaging.tools.<name>}), or of the {@code PATH}.
     */
    static boolean isBatchFile(String tool) {
        // Windows ignores the dots and spaces at the end of a file name
        String name = tool.toLowerCase(Locale.ROOT).replaceAll("[. ]+$", "");
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    /**
     * The command of a batch file, for {@code cmd.exe} : the path and each argument between double quotes, where
     * {@code cmd.exe} keeps {@code & | < > ^} and white space, with the backslashes at their end doubled, which the
     * command line parser of the program that the batch file runs reads as one before the closing quote.
     * {@code ProcessBuilder} leaves a quoted argument as it is.
     *
     * @throws PackagingException for a double quote, a percent sign or a line break, which {@code cmd.exe} would
     *         change whatever the quotes : the message does not have the argument, which may be a password
     */
    static List<String> batchCommand(List<String> command) {
        List<String> quoted = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String argument = command.get(i);
            if (argument.chars().anyMatch(c -> c == '"' || c == '%' || c == '\r' || c == '\n')) {
                throw new PackagingException(command.get(0) + " is a batch file, which cmd.exe runs : "
                        + (i == 0 ? "its path" : "its argument " + i) + " has a double quote, a percent sign or a line"
                        + " break, which cmd.exe changes. Set quarkus.desktop.packaging.tools.<name> to the executable"
                        + " (.exe) of the tool rather than to a batch file");
            }
            int backslashes = argument.length() - argument.replaceAll("\\\\+$", "").length();
            quoted.add('"' + argument + "\\".repeat(backslashes) + '"');
        }
        return quoted;
    }

    /**
     * The text of the output of a tool : UTF-8 when the bytes are valid UTF-8 (the tools of Linux and macOS, ASCII),
     * the given encoding otherwise (on Windows, the tools write in the ANSI code page).
     */
    static String decode(byte[] bytes, Charset fallback) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, fallback);
        }
    }

    /**
     * The encoding of the operating system ({@code native.encoding}) : the ANSI code page on Windows, which
     * {@code Charset.defaultCharset()} is not since Java 18 (UTF-8).
     */
    private static Charset nativeEncoding() {
        try {
            return Charset.forName(System.getProperty("native.encoding", ""));
        } catch (IllegalArgumentException e) {
            // no such property, or an encoding that this JVM does not have
            return Charset.defaultCharset();
        }
    }

    /**
     * Runs a tool that has to succeed.
     *
     * @throws PackagingException when the exit code of the tool is not zero, with its output
     */
    public static Result exec(Path directory, Map<String, String> environment, List<String> command) {
        Result result = run(directory, environment, command);
        if (!result.succeeded()) {
            throw failure(command, result);
        }
        return result;
    }

    /**
     * The failure of a tool : its command, its exit code and its output.
     */
    public static PackagingException failure(List<String> command, Result result) {
        return new PackagingException(command.get(0) + " failed with exit code " + result.exitCode() + " (" + String
                .join(" ", command) + ") :\n" + result.output().strip());
    }

    /**
     * Runs a tool that has to succeed, in the working directory of the build.
     */
    public static Result exec(List<String> command) {
        return exec(null, Map.of(), command);
    }
}
