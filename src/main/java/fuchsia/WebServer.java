package fuchsia;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fuchsia.grpc.FileIngestionServiceGrpc;
import fuchsia.grpc.UploadFileRequest;
import fuchsia.grpc.UploadFileResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client-facing web server. It does two jobs:
 *
 * 1. Serves the upload page (src/main/resources/static/) at http://localhost:8080
 * 2. Receives each file the page sends to POST /api/upload and forwards it to the gRPC server
 *    with the same UploadFile call that FileIngestionClient uses.
 *
 *   browser --HTTP POST /api/upload--> WebServer --gRPC UploadFile--> FileIngestionServer --> folder1
 *
 * Browsers can't speak gRPC directly, so this class is the translator between the two.
 * It never writes to folder1 itself; the gRPC server stays the only thing that does.
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    /** Same limit as the test client: gRPC's 4 MB message limit minus room for the filename. */
    static final long MAX_FILE_BYTES = FileIngestionClient.MAX_FILE_BYTES;

    /** How long to wait for the gRPC server to answer one upload. */
    static final long GRPC_DEADLINE_SECONDS = 10;

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8");

    public static void main(String[] args) throws IOException, InterruptedException {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(Shared.GRPC_HOST, Shared.GRPC_PORT)
                .usePlaintext()
                .build();

        HttpServer server = start(Shared.WEB_BIND, Shared.WEB_PORT,
                FileIngestionServiceGrpc.newBlockingStub(channel));

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down web server");
            server.stop(1);
            channel.shutdown();
            stopped.countDown();
        }));

        stopped.await();
    }

    /**
     * Starts the web server. Pass port 0 to let the operating system pick any free port.
     * The stub decides which gRPC server uploads go to (tests pass an in-process one).
     */
    static HttpServer start(String bindAddress, int port,
                            FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/api/upload", exchange -> handleUpload(exchange, stub));
        server.createContext("/", WebServer::handleStatic);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // one thread per request
        server.start();

        log.info("Web UI at http://localhost:{} (forwarding uploads to gRPC {}:{})",
                server.getAddress().getPort(), Shared.GRPC_HOST, Shared.GRPC_PORT);
        return server;
    }

    // ---------- POST /api/upload ----------

    /**
     * Request:  raw file bytes as the body, file name in the X-File-Name header (URI-encoded).
     * Response: 200 on success, otherwise an error status with a short reason as plain text,
     *           which the page shows in red.
     */
    static void handleUpload(HttpExchange exchange,
                             FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Only POST is allowed.");
                return;
            }

            String rawName = exchange.getRequestHeaders().getFirst("X-File-Name");
            if (rawName == null) {
                sendText(exchange, 400, "Missing file name.");
                return;
            }
            String filename;
            try {
                filename = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                sendText(exchange, 400, "Invalid file name.");
                return;
            }

            // Read at most one byte past the limit, so a huge upload can't fill memory.
            byte[] content = exchange.getRequestBody().readNBytes((int) MAX_FILE_BYTES + 1);
            if (content.length > MAX_FILE_BYTES) {
                sendText(exchange, 413, "file is too large (max 4 MB).");
                return;
            }

            // Filename rules, empty files and duplicates are all checked by the gRPC server,
            // so they live in one place (FileIngestionServiceImpl).
            UploadFileRequest request = UploadFileRequest.newBuilder()
                    .setFilename(filename)
                    .setContent(ByteString.copyFrom(content))
                    .build();

            try {
                UploadFileResponse response = stub
                        .withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .uploadFile(request);
                log.info("Forwarded {} ({} bytes) to gRPC: {}", filename, content.length, response.getMessage());
                sendText(exchange, 200, response.getMessage());
            } catch (StatusRuntimeException e) {
                log.warn("gRPC rejected {}: {}", filename, e.getStatus());
                sendGrpcError(exchange, e);
            }
        } catch (RuntimeException e) {
            // Anything unexpected still gets an answer, so the page shows red instead of hanging.
            log.error("Upload failed unexpectedly", e);
            sendText(exchange, 500, "the server could not save the file.");
        } finally {
            exchange.close();
        }
    }

    /** Turns a gRPC error into the matching HTTP status and a reason the user can read. */
    private static void sendGrpcError(HttpExchange exchange, StatusRuntimeException e) throws IOException {
        String description = e.getStatus().getDescription();
        switch (e.getStatus().getCode()) {
            case INVALID_ARGUMENT -> sendText(exchange, 400, description);   // e.g. "File is empty"
            case ALREADY_EXISTS -> sendText(exchange, 409, description);     // "A file named 'x' already exists"
            case RESOURCE_EXHAUSTED -> sendText(exchange, 413, "file is too large (max 4 MB).");
            case UNAVAILABLE -> sendText(exchange, 503, "the upload service is not running right now.");
            case DEADLINE_EXCEEDED -> sendText(exchange, 504, "the upload timed out.");
            // INTERNAL and anything else: keep server details (paths, exceptions) out of the page.
            default -> sendText(exchange, 500, "the server could not save the file.");
        }
    }

    // ---------- GET: the page itself ----------

    static void handleStatic(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Only GET is allowed.");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            String extension = path.substring(path.lastIndexOf('.') + 1);
            if (path.contains("..") || !CONTENT_TYPES.containsKey(extension)) {
                sendText(exchange, 404, "Not found.");
                return;
            }

            try (InputStream in = WebServer.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    sendText(exchange, 404, "Not found.");
                    return;
                }
                send(exchange, 200, CONTENT_TYPES.get(extension), in.readAllBytes());
            }
        } finally {
            exchange.close();
        }
    }

    // ---------- helpers ----------

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        String body = text == null ? "" : text;
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}