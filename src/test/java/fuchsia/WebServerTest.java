package fuchsia;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fuchsia.grpc.FileIngestionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real web server on a free port, pointed at an in-process gRPC server,
 * and talks to it over HTTP the same way the page does.
 */
class WebServerTest {

    @TempDir
    Path tempDir;

    private Path folder1;
    private Server grpcServer;
    private ManagedChannel channel;
    private HttpServer webServer;
    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        folder1 = Files.createDirectory(tempDir.resolve("folder1"));

        String name = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(name)
                .addService(new FileIngestionServiceImpl(folder1))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();

        webServer = WebServer.start("localhost", 0, FileIngestionServiceGrpc.newBlockingStub(channel));
        baseUrl = "http://localhost:" + webServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        webServer.stop(0);
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        grpcServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---------- helpers ----------

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a file exactly like app.js does. */
    private HttpResponse<String> upload(String filename, byte[] content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/upload"))
                .header("Content-Type", "application/octet-stream")
                .header("X-File-Name", URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> namesInFolder1() throws IOException {
        try (Stream<Path> files = Files.list(folder1)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---------- serving the page ----------

    @Test
    void rootServesTheUploadPage() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(response.body().contains("FuchsiaAI"));
    }

    @Test
    void stylesheetAndScriptAreServed() throws Exception {
        assertEquals(200, get("/styles.css").statusCode());
        assertEquals(200, get("/app.js").statusCode());
    }

    @Test
    void unknownPathsAndTraversalAttempts_return404() throws Exception {
        assertEquals(404, get("/missing.html").statusCode());
        assertEquals(404, get("/fuchsia/Shared.class").statusCode());
        assertEquals(404, get("/%2E%2E/index.html").statusCode()); // decodes to "/../index.html"
    }

    // ---------- uploads that work ----------

    @Test
    void upload_isForwardedToGrpc_andLandsInFolder1() throws Exception {
        HttpResponse<String> response = upload("hello.txt", "hi".getBytes());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Saved hello.txt", response.body());
        assertEquals("hi", Files.readString(folder1.resolve("hello.txt")));
    }

    @Test
    void fileNameWithSpacesAndUnicode_isDecodedCorrectly() throws Exception {
        HttpResponse<String> response = upload("my résumé + notes.txt", "x".getBytes());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("my résumé + notes.txt"), namesInFolder1());
    }

    @Test
    void fileExactlyAtMaxSize_isAccepted() throws Exception {
        byte[] content = new byte[(int) WebServer.MAX_FILE_BYTES];
        content[0] = 1;

        assertEquals(200, upload("max.bin", content).statusCode());
    }

    // ---------- uploads that fail ----------

    @Test
    void duplicate_returns409_withServerReason() throws Exception {
        upload("a.txt", "first".getBytes());

        HttpResponse<String> second = upload("a.txt", "second".getBytes());

        assertEquals(409, second.statusCode());
        assertTrue(second.body().contains("already exists"), second.body());
        assertEquals("first", Files.readString(folder1.resolve("a.txt")));
    }

    @Test
    void invalidFilename_returns400() throws Exception {
        HttpResponse<String> response = upload(".hidden", "x".getBytes());

        assertEquals(400, response.statusCode());
        assertTrue(namesInFolder1().isEmpty());
    }

    @Test
    void pathTraversalFilename_returns400_andWritesNothing() throws Exception {
        HttpResponse<String> response = upload("../escape.txt", "x".getBytes());

        assertEquals(400, response.statusCode());
        assertTrue(namesInFolder1().isEmpty());
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    }

    @Test
    void emptyFile_returns400() throws Exception {
        HttpResponse<String> response = upload("empty.txt", new byte[0]);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("empty"), response.body());
    }

    @Test
    void fileOverMaxSize_returns413_andIsNeverForwarded() throws Exception {
        byte[] content = new byte[(int) WebServer.MAX_FILE_BYTES + 1];

        HttpResponse<String> response = upload("too-big.bin", content);

        assertEquals(413, response.statusCode());
        assertTrue(namesInFolder1().isEmpty());
    }

    @Test
    void missingFileNameHeader_returns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/upload"))
                .POST(HttpRequest.BodyPublishers.ofByteArray("x".getBytes()))
                .build();

        assertEquals(400, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void getOnUploadEndpoint_returns405() throws Exception {
        assertEquals(405, get("/api/upload").statusCode());
    }

    @Test
    void grpcServerDown_returns503() throws Exception {
        grpcServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

        HttpResponse<String> response = upload("a.txt", "x".getBytes());

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("not running"), response.body());
    }
}