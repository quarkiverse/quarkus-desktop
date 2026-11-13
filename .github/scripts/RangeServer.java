import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves the files of a directory on the loopback interface, with the ranges of the HTTP requests (one range, or several
 * as a multipart/byteranges response), which the downloads of Windows (App Installer, Delivery Optimization) and zsync
 * ask for : the server of Python serves whole files only. Logs each request on the standard output.
 * <p>
 * {@code java .github/scripts/RangeServer.java <port> <directory>}
 */
public class RangeServer {

    private static final Pattern RANGE = Pattern.compile("(\\d*)-(\\d*)");

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                serve(exchange, directory);
            } catch (IOException | RuntimeException e) {
                System.out.println("error " + e);
            }
        });
        server.start();
        System.out.println("serving " + directory + " on 127.0.0.1:" + port);
    }

    private static void serve(HttpExchange exchange, Path directory) throws IOException {
        String method = exchange.getRequestMethod();
        String range = exchange.getRequestHeaders().getFirst("Range");
        String name = java.net.URLDecoder.decode(exchange.getRequestURI().getRawPath().substring(1), StandardCharsets.UTF_8);
        Path file = directory.resolve(name).normalize();
        System.out.println(method + " " + exchange.getRequestURI() + (range != null ? " " + range : ""));
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        long length = Files.size(file);
        String type = name.endsWith(".appinstaller") ? "application/appinstaller"
                : name.endsWith(".msix") ? "application/msix" : "application/octet-stream";
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        boolean head = method.equals("HEAD");
        List<long[]> ranges = range != null && range.startsWith("bytes=") ? ranges(range.substring(6), length) : null;
        if (ranges == null || ranges.isEmpty()) {
            exchange.getResponseHeaders().set("Content-Type", type);
            send(exchange, 200, length, head);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    Files.copy(file, out);
                }
            }
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            if (ranges.size() == 1) {
                long[] r = ranges.get(0);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + r[0] + "-" + r[1] + "/" + length);
                send(exchange, 206, r[1] - r[0] + 1, head);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        copy(input, r, out);
                    }
                }
                return;
            }
            String boundary = "range-server-boundary";
            exchange.getResponseHeaders().set("Content-Type", "multipart/byteranges; boundary=" + boundary);
            exchange.sendResponseHeaders(206, 0);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    for (long[] r : ranges) {
                        out.write(("\r\n--" + boundary + "\r\nContent-Type: " + type + "\r\nContent-Range: bytes " + r[0]
                                + "-" + r[1] + "/" + length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        copy(input, r, out);
                    }
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
    }

    /**
     * Sends the status and the headers, with the length of the content : for a {@code HEAD} request too, without content.
     */
    private static void send(HttpExchange exchange, int status, long length, boolean head) throws IOException {
        if (head) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, length);
        }
    }

    /**
     * The ranges of a {@code Range} header, as inclusive first and last bytes, {@code null} for an invalid header.
     */
    private static List<long[]> ranges(String spec, long length) {
        List<long[]> ranges = new ArrayList<>();
        for (String part : spec.split(",")) {
            Matcher matcher = RANGE.matcher(part.strip());
            if (!matcher.matches() || matcher.group(1).isEmpty() && matcher.group(2).isEmpty()) {
                return null;
            }
            long first;
            long last;
            if (matcher.group(1).isEmpty()) {
                first = Math.max(0, length - Long.parseLong(matcher.group(2)));
                last = length - 1;
            } else {
                first = Long.parseLong(matcher.group(1));
                last = matcher.group(2).isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(matcher.group(2)));
            }
            if (first <= last) {
                ranges.add(new long[] { first, last });
            }
        }
        return ranges;
    }

    private static void copy(RandomAccessFile input, long[] range, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        input.seek(range[0]);
        long remaining = range[1] - range[0] + 1;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
