package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The directories that the packages are made of : copies, listings, permissions.
 */
public final class FileTrees {

    private FileTrees() {
    }

    /**
     * Copies a file or a directory and its content, with the permissions : a source that is a symbolic link is copied
     * as the file or the directory that it links to (a license file linked to the one of the repository), the symbolic
     * links inside a directory are copied as links.
     */
    public static void copy(Path source, Path target) throws IOException {
        Path from = Files.isSymbolicLink(source) ? source.toRealPath() : source;
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(from, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS);
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(from.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes a file or a directory and its content, if it exists.
     */
    public static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                deleteFile(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteFile(Path file) throws IOException {
        try {
            Files.delete(file);
        } catch (IOException e) {
            // a read-only file on Windows
            if (!file.toFile().setWritable(true)) {
                throw e;
            }
            Files.delete(file);
        }
    }

    /**
     * An empty directory : created, or emptied.
     */
    public static Path emptyDirectory(Path directory) throws IOException {
        delete(directory);
        return Files.createDirectories(directory);
    }

    /**
     * The regular files and the symbolic links of a directory, as sorted relative paths.
     */
    public static List<Path> files(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> files.add(root.relativize(path)));
        }
        files.sort(null);
        return files;
    }

    /**
     * The size of the regular files of a directory, in bytes.
     */
    public static long size(Path root) throws IOException {
        long size = 0;
        for (Path file : files(root)) {
            Path path = root.resolve(file);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                size += Files.size(path);
            }
        }
        return size;
    }

    /**
     * Writes a text file in UTF-8, with its parent directories.
     */
    public static Path write(Path file, String content) throws IOException {
        return write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a file, with its parent directories.
     */
    public static Path write(Path file, byte[] content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        return Files.write(file, content);
    }

    /**
     * Makes a file executable by everyone ({@code rwxr-xr-x}), where files have such permissions.
     */
    public static void executable(Path file) throws IOException {
        permissions(file, "rwxr-xr-x");
    }

    /**
     * Sets the permissions of a file ({@code rw-r--r--}), where files have such permissions.
     */
    public static void permissions(Path file, String permissions) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        }
    }

    /**
     * Gives a directory the permissions of the files of a package : {@code rwxr-xr-x} for the directories and the
     * executable files, {@code rw-r--r--} for the others, whatever the umask of the build.
     */
    public static void normalizePermissions(Path root) throws IOException {
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                boolean executable = Files.isDirectory(path) || Files.isExecutable(path);
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
            }
        }
    }

    /**
     * Whether a file is an ELF file : an executable or a shared library of Linux.
     */
    public static boolean isElf(Path file) {
        return hasMagic(file, 0x7f, 'E', 'L', 'F');
    }

    /**
     * Whether a file is a 64-bit Mach-O file or a universal file : an executable or a dynamic library of macOS.
     */
    public static boolean isMachO(Path file) {
        return hasMagic(file, 0xcf, 0xfa, 0xed, 0xfe) || hasMagic(file, 0xca, 0xfe, 0xba, 0xbe)
                || hasMagic(file, 0xca, 0xfe, 0xba, 0xbf);
    }

    private static boolean hasMagic(Path file, int... magic) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(magic.length);
            if (header.length < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if ((header[i] & 0xff) != magic[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
