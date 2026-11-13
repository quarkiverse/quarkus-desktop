package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.swt.OpenRequest;
import io.quarkiverse.desktop.swt.OpenRequest.Source;

/**
 * The single instance : two instances in this JVM, the protocol between them, and the failures that let a second
 * launch start. Without a display.
 */
class SingleInstanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final String NAME = SingleInstance.name("org.acme.editor", "user");

    @TempDir
    Path directory;

    @Test
    void secondLaunchForwardsItsRequest() throws Exception {
        BlockingQueue<OpenRequest> received = new LinkedBlockingQueue<>();
        SingleInstance.Result first = SingleInstance.start(directory, NAME, empty(), received::add, TIMEOUT);
        assertNotNull(first.instance());
        assertFalse(first.forwarded());
        try {
            OpenRequest request = new OpenRequest(List.of(directory.resolve("a b.acme"), directory.resolve("é.acme")),
                    List.of(URI.create("acme://open/42")), Source.ANOTHER_LAUNCH);
            SingleInstance.Result second = SingleInstance.start(directory, NAME, request, r -> {
                throw new AssertionError("the second launch does not listen");
            }, TIMEOUT);
            assertTrue(second.forwarded());
            assertNull(second.instance());
            OpenRequest forwarded = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(forwarded);
            assertEquals(Source.ANOTHER_LAUNCH, forwarded.source());
            assertEquals(request.files(), forwarded.files());
            assertEquals(request.uris(), forwarded.uris());
            // a launch without files nor URIs is forwarded too
            assertTrue(SingleInstance.start(directory, NAME, empty(), r -> {
            }, TIMEOUT).forwarded());
            assertTrue(received.poll(5, TimeUnit.SECONDS).isEmpty());
        } finally {
            first.instance().close();
        }
        assertFalse(Files.exists(directory.resolve(NAME + ".port")));
        // once the first instance stopped, the next launch is the first instance
        SingleInstance.Result next = SingleInstance.start(directory, NAME, empty(), r -> {
        }, TIMEOUT);
        assertNotNull(next.instance());
        next.instance().close();
    }

    @Test
    void otherApplicationsAndUsers() throws Exception {
        assertNotEquals(NAME, SingleInstance.name("org.acme.other", "user"));
        assertNotEquals(NAME, SingleInstance.name("org.acme.editor", "other"));
        assertEquals(NAME, SingleInstance.name("org.acme.editor", "user"));
        SingleInstance.Result first = SingleInstance.start(directory, NAME, empty(), r -> {
        }, TIMEOUT);
        SingleInstance.Result other = SingleInstance.start(directory, SingleInstance.name("org.acme.other", "user"),
                empty(), r -> {
                }, TIMEOUT);
        try {
            assertNotNull(other.instance());
        } finally {
            first.instance().close();
            other.instance().close();
        }
    }

    @Test
    void wrongTokenIsIgnored() throws Exception {
        BlockingQueue<OpenRequest> received = new LinkedBlockingQueue<>();
        SingleInstance first = SingleInstance.lock(directory, NAME);
        assertNotNull(first);
        try {
            first.listen(received::add, TIMEOUT);
            int port = Integer.parseInt(Files.readAllLines(directory.resolve(NAME + ".port")).get(0));
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write("wrong\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                // closed without an answer
                assertEquals(-1, socket.getInputStream().read());
            }
            // the listener still serves the next launches
            assertTrue(SingleInstance.forward(directory, NAME, empty(), TIMEOUT));
            assertTrue(received.poll(5, TimeUnit.SECONDS).isEmpty());
            assertNull(received.poll(100, TimeUnit.MILLISECONDS));
        } finally {
            first.close();
        }
    }

    @Test
    void slowClientTimesOut() throws Exception {
        SingleInstance first = SingleInstance.lock(directory, NAME);
        try {
            first.listen(r -> {
            }, Duration.ofMillis(300));
            int port = Integer.parseInt(Files.readAllLines(directory.resolve(NAME + ".port")).get(0));
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(5000);
                // sends nothing : closed once the time-out expired
                InputStream in = socket.getInputStream();
                assertEquals(-1, in.read());
            }
            assertTrue(SingleInstance.forward(directory, NAME, empty(), TIMEOUT));
        } finally {
            first.close();
        }
    }

    @Test
    void unreachableInstanceLetsTheLaunchStart() throws Exception {
        // the lock is held, the port file names a closed port
        SingleInstance first = SingleInstance.lock(directory, NAME);
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Files.writeString(directory.resolve(NAME + ".port"), closed.getLocalPort() + "\nsecret\n");
        }
        try {
            long start = System.nanoTime();
            SingleInstance.Result second = SingleInstance.start(directory, NAME, empty(), r -> {
            }, Duration.ofMillis(500));
            assertFalse(second.forwarded());
            assertNull(second.instance());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        } finally {
            first.close();
        }
    }

    @Test
    void wrongAnswerLetsTheLaunchStart() throws Exception {
        SingleInstance first = SingleInstance.lock(directory, NAME);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Files.writeString(directory.resolve(NAME + ".port"), server.getLocalPort() + "\nsecret\n");
            Thread fake = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write("NO\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // the test fails
                }
            });
            fake.start();
            assertFalse(SingleInstance.forward(directory, NAME, empty(), TIMEOUT));
            fake.join(5000);
        } finally {
            first.close();
        }
    }

    @Test
    void portFileIsPrivate() throws Exception {
        SingleInstance first = SingleInstance.lock(directory, NAME);
        try {
            first.listen(r -> {
            }, TIMEOUT);
            Path portFile = directory.resolve(NAME + ".port");
            List<String> lines = Files.readAllLines(portFile);
            assertEquals(2, lines.size());
            assertEquals(32, lines.get(1).length());
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", PosixFilePermissions
                        .toString(Files.getPosixFilePermissions(portFile)));
            }
        } finally {
            first.close();
        }
    }

    @Test
    void directories() {
        assertEquals(Path.of("tmp"), SingleInstance.directory(Map.of("XDG_RUNTIME_DIR", directory.toString()),
                "Windows 11", "tmp"));
        assertEquals(Path.of("tmp"), SingleInstance.directory(Map.of("XDG_RUNTIME_DIR", directory.toString()),
                "Mac OS X", "tmp"));
        assertEquals(directory, SingleInstance.directory(Map.of("XDG_RUNTIME_DIR", directory.toString()), "Linux",
                "tmp"));
        assertEquals(Path.of("tmp"), SingleInstance.directory(Map.of("XDG_RUNTIME_DIR",
                directory.resolve("missing").toString()), "Linux", "tmp"));
        assertEquals(Path.of("tmp"), SingleInstance.directory(Map.of(), "Linux", "tmp"));
    }

    private static OpenRequest empty() {
        return new OpenRequest(List.of(), List.of(), Source.ANOTHER_LAUNCH);
    }
}
