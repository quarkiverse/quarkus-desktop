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
     * @param configured the paths of the configuration, by tool name ({@code quarkus.desktop.packaging.tools})
     */
    public Tools(Map<String, String> configured) {
        this.configured = configured;
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
     * The path of a tool : the one of the configuration, the first one of the {@code PATH}, or the first existing one
     * of the given locations.
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
     * The path of a tool in the configuration ({@code quarkus.desktop.packaging.tools.<name>}), if any.
     *
     * @param name the name of the tool, without extension
     * @throws PackagingException when the configured file does not exist
     */
    public Optional<Path> configured(String name) {
        String path = configured.get(name);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Path tool = Path.of(path.trim());
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
        String path = System.getenv("PATH");
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
                        return Optional.of(candidate);
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
     */
    public static Result run(Path directory, Map<String, String> environment, List<String> command,
            boolean confidential) {
        LOGGER.debugf("Running %s%s", confidential ? command.get(0) + " (arguments not logged)" : command,
                directory == null ? "" : " in " + directory);
        ProcessBuilder builder = new ProcessBuilder(TargetOs.host() == TargetOs.WINDOWS
                ? command.stream().map(Tools::windowsArgument).toList()
                : command).redirectErrorStream(true);
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
