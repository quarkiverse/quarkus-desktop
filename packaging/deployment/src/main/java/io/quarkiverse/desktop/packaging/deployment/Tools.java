package io.quarkiverse.desktop.packaging.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
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
        String path = configured.get(name);
        if (path != null && !path.isBlank()) {
            Path tool = Path.of(path.trim());
            if (!Files.isRegularFile(tool)) {
                throw new PackagingException("quarkus.desktop.packaging.tools." + name + " : " + tool + " not found");
            }
            return Optional.of(tool);
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
        LOGGER.debugf("Running %s%s", command, directory == null ? "" : " in " + directory);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            // the tools write in the encoding of the console
            String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
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
     * Runs a tool that has to succeed.
     *
     * @throws PackagingException when the exit code of the tool is not zero, with its output
     */
    public static Result exec(Path directory, Map<String, String> environment, List<String> command) {
        Result result = run(directory, environment, command);
        if (!result.succeeded()) {
            throw new PackagingException(command.get(0) + " failed with exit code " + result.exitCode() + " (" + String
                    .join(" ", command) + ") :\n" + result.output().strip());
        }
        return result;
    }

    /**
     * Runs a tool that has to succeed, in the working directory of the build.
     */
    public static Result exec(List<String> command) {
        return exec(null, Map.of(), command);
    }
}
