package io.quarkiverse.desktop.swt.runtime;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.OpenRequest;
import io.quarkiverse.desktop.swt.OpenRequest.Source;

/**
 * One instance of the application per user ({@code quarkus.desktop.swt.single-instance}) : the first instance holds an
 * exclusive lock on a file for its whole life, and listens on the loopback interface ; a second launch, which does not
 * get the lock, sends its files and URIs to it and exits.
 * <p>
 * The files are in a directory of the user (the temporary directory, per user on Windows and macOS ;
 * {@code $XDG_RUNTIME_DIR} on Linux, else the temporary directory), named after a hash of the identifier of the
 * application and of the user name : {@code <name>.lock}, and {@code <name>.port}, readable by the user only, with the
 * port and a random token. A second launch sends the token, then one line per file ({@code F <path>}) and per URI
 * ({@code U <uri>}), in UTF-8, and an empty line ; the first instance answers {@code OK} once it took the request. A
 * connection without the token is closed.
 */
final class SingleInstance implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(SingleInstance.class);

    /**
     * How long a second launch tries to reach the first instance, and how long the first instance reads a request.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private static final String ACKNOWLEDGEMENT = "OK";

    private static final int MAX_LINE_BYTES = 32 * 1024;

    private static final int MAX_ITEMS = 1024;

    /**
     * What a second launch gets : the first instance (to close when the application stops) when this one is it, else
     * whether the request was forwarded.
     *
     * @param instance the first instance, {@code null} when another instance runs, or when the lock failed
     * @param forwarded whether the request was forwarded to the running instance : this launch exits
     */
    record Result(SingleInstance instance, boolean forwarded) {
    }

    private final FileChannel channel;

    private final FileLock lock;

    private final Path portFile;

    private volatile ServerSocket server;

    private volatile boolean closed;

    private SingleInstance(FileChannel channel, FileLock lock, Path portFile) {
        this.channel = channel;
        this.lock = lock;
        this.portFile = portFile;
    }

    /**
     * The directory of the files of the user : {@code $XDG_RUNTIME_DIR} on Linux when it is a directory, else the
     * temporary directory ({@code java.io.tmpdir}, per user on Windows and macOS).
     *
     * @param environment the environment variables
     * @param osName {@code os.name}
     * @param tmpDir {@code java.io.tmpdir}
     */
    static Path directory(Map<String, String> environment, String osName, String tmpDir) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (!os.contains("win") && !os.contains("mac")) {
            String runtimeDir = environment.get("XDG_RUNTIME_DIR");
            if (runtimeDir != null && !runtimeDir.isBlank()) {
                try {
                    Path directory = Path.of(runtimeDir);
                    if (Files.isDirectory(directory)) {
                        return directory;
                    }
                } catch (InvalidPathException e) {
                    // the temporary directory
                }
            }
        }
        return Path.of(tmpDir);
    }

    /**
     * The name of the files : a hash of the identifier of the application and of the user name.
     */
    static String name(String identifier, String user) {
        // FNV-1a : no security provider to look up in native executables
        long hash = 0xcbf29ce484222325L;
        for (byte b : (identifier + '\0' + user).getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return "quarkus-desktop-" + HexFormat.of().toHexDigits(hash);
    }

    /**
     * Makes this launch the first instance, listening for the next launches, or forwards its request to the running
     * instance. Any failure lets this launch start as usual, logged.
     *
     * @param directory the directory of the files ({@link #directory})
     * @param name the name of the files ({@link #name})
     * @param request the files and URIs of this launch
     * @param requests takes the requests of the next launches, on the thread of the listener
     * @param timeout how long to try to reach the running instance, and to read a request
     */
    static Result start(Path directory, String name, OpenRequest request, Consumer<OpenRequest> requests,
            Duration timeout) {
        SingleInstance instance;
        try {
            instance = lock(directory, name);
        } catch (IOException | RuntimeException e) {
            LOGGER.warnf("Unable to lock %s : the application starts without checking for another instance (%s)",
                    directory.resolve(name + ".lock"), e.toString());
            return new Result(null, false);
        }
        if (instance == null) {
            if (forward(directory, name, request, timeout)) {
                return new Result(null, true);
            }
            LOGGER.warn("Another instance of the application holds its lock, but it could not be reached : this one"
                    + " starts too");
            return new Result(null, false);
        }
        try {
            instance.listen(requests, timeout);
        } catch (IOException | RuntimeException e) {
            LOGGER.warnf("Unable to listen for the next launches of the application : they start another instance (%s)",
                    e.toString());
            instance.close();
            return new Result(null, false);
        }
        return new Result(instance, false);
    }

    /**
     * Takes the lock.
     *
     * @return the first instance, not listening yet, or {@code null} when another instance holds the lock (also in this
     *         JVM)
     */
    static SingleInstance lock(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(name + ".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return new SingleInstance(channel, lock, directory.resolve(name + ".port"));
    }

    /**
     * Listens on the loopback interface, and writes the port and a new token into the port file.
     *
     * @param requests takes the requests, on the thread of the listener
     * @param timeout how long a request is read
     */
    void listen(Consumer<OpenRequest> requests, Duration timeout) throws IOException {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        try {
            writePortFile(socket.getLocalPort(), token);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        server = socket;
        Thread thread = new Thread(() -> accept(socket, token, requests, timeout), "quarkus-desktop-single-instance");
        thread.setDaemon(true);
        thread.start();
        LOGGER.debugf("The application listens for its next launches on port %d (%s)", socket.getLocalPort(), portFile);
    }

    /**
     * Writes the port file, readable by the user only : a temporary file moved over it.
     */
    private void writePortFile(int port, String token) throws IOException {
        Path temporary = portFile.resolveSibling(portFile.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.deleteIfExists(temporary);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(temporary);
        }
        try {
            Files.writeString(temporary, port + "\n" + token + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, portFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, portFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void accept(ServerSocket socket, String token, Consumer<OpenRequest> requests, Duration timeout) {
        while (!closed) {
            try (Socket connection = socket.accept()) {
                handle(connection, token, requests, timeout);
            } catch (SocketException e) {
                if (!closed) {
                    LOGGER.debugf("A connection of another launch failed : %s", e.toString());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debugf("A connection of another launch failed : %s", e.toString());
            }
        }
    }

    /**
     * Reads a request, within the time-out : the token, the files and the URIs.
     */
    private void handle(Socket connection, String token, Consumer<OpenRequest> requests, Duration timeout)
            throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        connection.setSoTimeout((int) timeout.toMillis());
        InputStream in = connection.getInputStream();
        String received = readLine(in, deadline);
        if (received == null || !MessageDigest.isEqual(received.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            LOGGER.debug("A connection without the token of the application is closed");
            return;
        }
        List<Path> files = new ArrayList<>();
        List<URI> uris = new ArrayList<>();
        for (int i = 0; i <= MAX_ITEMS; i++) {
            String line = readLine(in, deadline);
            if (line == null) {
                LOGGER.debug("A request of another launch ended early : it is ignored");
                return;
            }
            if (line.isEmpty()) {
                break;
            }
            if (i == MAX_ITEMS) {
                LOGGER.debug("A request of another launch is too long : it is ignored");
                return;
            }
            String value = line.length() > 2 ? line.substring(2) : "";
            try {
                if (line.startsWith("F ")) {
                    files.add(Path.of(value));
                } else if (line.startsWith("U ")) {
                    uris.add(new URI(value));
                }
            } catch (InvalidPathException | URISyntaxException e) {
                LOGGER.debugf("Ignored %s of another launch : %s", line, e.getMessage());
            }
        }
        requests.accept(new OpenRequest(files, uris, Source.ANOTHER_LAUNCH));
        OutputStream out = connection.getOutputStream();
        out.write((ACKNOWLEDGEMENT + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * A line in UTF-8, without its end : {@code null} at the end of the stream.
     *
     * @throws IOException when it is too long, or the time-out expired
     */
    private static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (System.nanoTime() > deadline) {
                throw new IOException("Time-out");
            }
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                String value = line.toString(StandardCharsets.UTF_8);
                return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
            }
            if (line.size() >= MAX_LINE_BYTES) {
                throw new IOException("Line too long");
            }
            line.write(b);
        }
    }

    /**
     * Sends a request to the running instance, retrying until the time-out while it starts (no port file yet, or a
     * stale one).
     *
     * @return whether the running instance took it
     */
    static boolean forward(Path directory, String name, OpenRequest request, Duration timeout) {
        Path portFile = directory.resolve(name + ".port");
        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder lines = new StringBuilder();
        request.files().forEach(file -> append(lines, "F ", file.toString()));
        request.uris().forEach(uri -> append(lines, "U ", uri.toString()));
        lines.append('\n');
        while (true) {
            try {
                checkOwner(portFile);
                List<String> content = Files.readAllLines(portFile, StandardCharsets.UTF_8);
                if (content.size() < 2) {
                    throw new NoSuchFileException(portFile.toString(), null, "incomplete");
                }
                int port = Integer.parseInt(content.get(0).trim());
                String token = content.get(1).trim();
                return send(port, token, lines.toString(), deadline);
            } catch (FileSystemException | ConnectException e) {
                // not written yet, being replaced, or stale while the running instance starts
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOGGER.debugf("Unable to reach the running instance of the application : %s", e.toString());
                    return false;
                }
                try {
                    Thread.sleep(Math.min(100, Math.max(1, remaining / 1_000_000)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debugf("Unable to reach the running instance of the application : %s", e.toString());
                return false;
            }
        }
    }

    /**
     * Checks that the user owns the port file, where the temporary directory is shared ({@code /tmp} on Linux without
     * {@code $XDG_RUNTIME_DIR}) : another user could write one.
     */
    private static void checkOwner(Path portFile) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            String owner = Files.getOwner(portFile).getName();
            if (!owner.equals(System.getProperty("user.name"))) {
                throw new IOException(portFile + " belongs to " + owner);
            }
        }
    }

    private static void append(StringBuilder lines, String prefix, String value) {
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            lines.append(prefix).append(value).append('\n');
        }
    }

    private static boolean send(int port, String token, String lines, long deadline) throws IOException {
        int remaining = (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), remaining);
            socket.setSoTimeout(remaining);
            OutputStream out = socket.getOutputStream();
            out.write((token + "\n" + lines).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String answer = readLine(socket.getInputStream(), deadline);
            if (ACKNOWLEDGEMENT.equals(answer)) {
                return true;
            }
            LOGGER.debug("The running instance of the application did not take the request (another token?)");
            return false;
        }
    }

    /**
     * Stops listening, deletes the port file and releases the lock.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ServerSocket socket = server;
            if (socket != null) {
                socket.close();
                Files.deleteIfExists(portFile);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Unable to remove %s : %s", portFile, e.toString());
        }
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            LOGGER.debugf("Unable to release the lock of the application : %s", e.toString());
        }
    }
}
