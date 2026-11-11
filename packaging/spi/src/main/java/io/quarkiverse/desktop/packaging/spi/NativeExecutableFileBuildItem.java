package io.quarkiverse.desktop.packaging.spi;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A file that the native executable of the application needs next to it : a native library that the executable loads
 * from its directory, typically. The Desktop Packaging extension copies it into the application image, in the directory
 * of the executable.
 * <p>
 * The build steps that write files next to the native executable (after the native build, with a
 * {@code NativeImageBuildItem} parameter) produce this build item, or at least declare that they do : the packages are
 * then built after these steps ran. The shared libraries that are in the directory of the executable when the packages
 * are built ({@code *.dll}, {@code *.so}, {@code *.dylib}, {@code *.jnilib}) are in the application image without a
 * build item ; other files need one.
 */
public final class NativeExecutableFileBuildItem extends MultiBuildItem {

    private final Path file;

    private final String directory;

    /**
     * @param file the file, copied in the directory of the executable with its name
     */
    public NativeExecutableFileBuildItem(Path file) {
        this(file, "");
    }

    /**
     * @param file the file, copied with its name
     * @param directory the directory of the copy, relative to the directory of the executable, with {@code /}
     *        separators : empty for the directory of the executable
     */
    public NativeExecutableFileBuildItem(Path file, String directory) {
        this.file = Objects.requireNonNull(file, "file");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path getFile() {
        return file;
    }

    /**
     * The directory of the copy, relative to the directory of the executable : empty for the directory of the
     * executable.
     */
    public String getDirectory() {
        return directory;
    }
}
