package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the packages of a build share : the application, its target, the tools and the directories.
 */
public final class PackagingContext {

    private final DesktopPackagingConfig config;

    private final AppInfo app;

    private final TargetOs os;

    private final Tools tools;

    private final IconSet icons;

    private final Path projectDirectory;

    private final Path outputDirectory;

    public PackagingContext(DesktopPackagingConfig config, AppInfo app, TargetOs os, Tools tools, IconSet icons,
            Path projectDirectory, Path outputDirectory) {
        this.config = config;
        this.app = app;
        this.os = os;
        this.tools = tools;
        this.icons = icons;
        this.projectDirectory = projectDirectory;
        this.outputDirectory = outputDirectory;
    }

    public DesktopPackagingConfig config() {
        return config;
    }

    public AppInfo app() {
        return app;
    }

    /**
     * The operating system of the application.
     */
    public TargetOs os() {
        return os;
    }

    /**
     * The architecture of the application : the one of the build, which only the packagers ask for (the application
     * image is built on any architecture).
     *
     * @throws PackagingException for an architecture other than x64 and arm64
     */
    public Arch arch() {
        return Arch.host();
    }

    public Tools tools() {
        return tools;
    }

    public IconSet icons() {
        return icons;
    }

    /**
     * The directory of the relative paths of the configuration.
     */
    public Path projectDirectory() {
        return projectDirectory;
    }

    /**
     * The directory of the packages ({@code target/packaging}).
     */
    public Path outputDirectory() {
        return outputDirectory;
    }

    /**
     * An empty directory for the intermediate files of a package ({@code target/packaging/work/<name>}).
     */
    public Path workDirectory(String name) throws IOException {
        return FileTrees.emptyDirectory(outputDirectory.resolve("work").resolve(name));
    }

    /**
     * The path of a package in the output directory : a package of a previous build is deleted.
     */
    public Path output(String fileName) throws IOException {
        Files.createDirectories(outputDirectory);
        Path file = outputDirectory.resolve(fileName);
        FileTrees.delete(file);
        return file;
    }
}
