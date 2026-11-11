package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * What the packages of a build share : the application, its target, the tools and the directories.
 */
public final class PackagingContext {

    private final DesktopPackagingConfig config;

    private final AppInfo app;

    private final TargetOs os;

    /**
     * Reads the architecture of the application, when a packager first asks for it.
     */
    private final Supplier<Arch> archSupplier;

    /**
     * The architecture of the application, once read.
     */
    private Arch arch;

    private final Tools tools;

    private final IconSet icons;

    private final Path projectDirectory;

    private final Path outputDirectory;

    /**
     * The libraries of the application image that its executable loads only in some cases, relative to the root of the
     * image, with {@code /} separators.
     */
    private final Set<String> optionalLibraries;

    /**
     * @param arch reads the architecture of the application : the one of its native executable, or of the build
     */
    public PackagingContext(DesktopPackagingConfig config, AppInfo app, TargetOs os, Supplier<Arch> arch, Tools tools,
            IconSet icons, Path projectDirectory, Path outputDirectory) {
        this(config, app, os, arch, tools, icons, projectDirectory, outputDirectory, Set.of());
    }

    /**
     * @param arch reads the architecture of the application : the one of its native executable, or of the build
     * @param optionalLibraries the libraries of the application image that its executable loads only in some cases
     */
    private PackagingContext(DesktopPackagingConfig config, AppInfo app, TargetOs os, Supplier<Arch> arch, Tools tools,
            IconSet icons, Path projectDirectory, Path outputDirectory, Set<String> optionalLibraries) {
        this.config = config;
        this.app = app;
        this.os = os;
        this.archSupplier = arch;
        this.tools = tools;
        this.icons = icons;
        this.projectDirectory = projectDirectory;
        this.outputDirectory = outputDirectory;
        this.optionalLibraries = Collections.unmodifiableSet(new TreeSet<>(optionalLibraries));
    }

    /**
     * This context, for an application that gives itself a name at run time ({@link AppInfo#applicationId}).
     */
    PackagingContext withApplicationId(Optional<String> applicationId) {
        return new PackagingContext(config, app.withApplicationId(applicationId), os, this::arch, tools, icons,
                projectDirectory, outputDirectory, optionalLibraries);
    }

    /**
     * This context, for an application image whose executable loads some libraries only in some cases.
     *
     * @param optionalLibraries the libraries, relative to the root of the image, with {@code /} separators
     */
    PackagingContext withOptionalLibraries(Set<String> optionalLibraries) {
        return new PackagingContext(config, app, os, this::arch, tools, icons, projectDirectory, outputDirectory,
                optionalLibraries);
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
     * The architecture of the application : the one of its native executable, or of the build in JVM mode, which only
     * the packagers ask for (the application image is built on any architecture).
     *
     * @throws PackagingException for an architecture other than x64 and arm64
     */
    public Arch arch() {
        if (arch == null) {
            arch = archSupplier.get();
        }
        return arch;
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
     * The libraries of the application image that its executable loads only in some cases
     * ({@code NativeExecutableFileBuildItem.isOptional()}), relative to the root of the image, with {@code /}
     * separators, sorted : the Debian and RPM packages do not depend on the libraries that they are linked with.
     */
    public Set<String> optionalLibraries() {
        return optionalLibraries;
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
