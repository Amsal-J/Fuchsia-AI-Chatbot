package fuchsia;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import fuchsia.grpc.FileIngestionServiceGrpc;
import fuchsia.grpc.UploadFileRequest;
import fuchsia.grpc.UploadFileResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIngestionServerTest {

    private static final int FOUR_MB = 4 * 1024 * 1024;   // gRPC's default max message size

    @TempDir
    Path folder1;

    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        for (Server server : servers) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ---------- helpers ----------

    private Server startServer(int port, Path dir) throws IOException {
        Server server = FileIngestionServer.start(port, dir);
        servers.add(server);   // remembered so @AfterEach always cleans it up
        return server;
    }

    private FileIngestionServiceGrpc.FileIngestionServiceBlockingStub connect(int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        channels.add(channel);
        return FileIngestionServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static UploadFileRequest request(String filename, byte[] content) {
        return UploadFileRequest.newBuilder()
                .setFilename(filename)
                .setContent(ByteString.copyFrom(content))
                .build();
    }

    // ---------- starting ----------

    @Test
    void startsOnRandomPort_andAcceptsUploadsOverTheNetwork() throws IOException {
        Server server = startServer(0, folder1);
        assertTrue(server.getPort() > 0, "the OS should have assigned a real port");

        UploadFileResponse response = connect(server.getPort())
                .uploadFile(request("hello.txt", "hi".getBytes(UTF_8)));

        assertTrue(response.getAccepted());
        assertEquals("hi", Files.readString(folder1.resolve("hello.txt")));
    }

    @Test
    void createsTargetFolderIfMissing() throws IOException {
        Path newDir = folder1.resolve("created-on-start");

        startServer(0, newDir);

        assertTrue(Files.isDirectory(newDir));
    }

    @Test
    void startingOnAPortAlreadyInUse_fails() throws IOException {
        Server first = startServer(0, folder1);

        assertThrows(IOException.class, () -> startServer(first.getPort(), folder1));
    }

    // ---------- stopping ----------

    @Test
    void shutdown_terminatesServer_andReleasesPort() throws Exception {
        Server server = startServer(0, folder1);
        int port = server.getPort();

        server.shutdown();
        assertTrue(server.awaitTermination(5, TimeUnit.SECONDS), "server should stop within 5s");
        assertTrue(server.isTerminated());

        Server again = startServer(port, folder1);   // the same port is free again
        assertEquals(port, again.getPort());
    }

    @Test
    void afterShutdown_clientsGetUnavailable() throws Exception {
        Server server = startServer(0, folder1);
        int port = server.getPort();
        server.shutdown().awaitTermination(5, TimeUnit.SECONDS);

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> connect(port).uploadFile(request("a.txt", "x".getBytes(UTF_8))));

        assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
    }

    // ---------- size limits ----------

    @Test
    void fileJustUnder4MB_isAccepted() throws IOException {
        Server server = startServer(0, folder1);
        byte[] content = new byte[FOUR_MB - 1024];   // leave room for the filename and message overhead
        content[0] = 1;                              // non-empty

        UploadFileResponse response = connect(server.getPort())
                .uploadFile(request("big.bin", content));

        assertTrue(response.getAccepted());
        assertEquals(content.length, Files.size(folder1.resolve("big.bin")));
    }

    @Test
    void fileOver4MB_isRejected_andNothingIsWritten() throws IOException {
        Server server = startServer(0, folder1);
        byte[] content = new byte[FOUR_MB + 1];

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> connect(server.getPort()).uploadFile(request("too-big.bin", content)));

        // The server rejects with RESOURCE_EXHAUSTED, but because it cuts off the
        // client mid-upload, the client may only see CANCELLED. Either means rejected.
        Status.Code code = e.getStatus().getCode();
        assertTrue(code == Status.Code.RESOURCE_EXHAUSTED || code == Status.Code.CANCELLED,
                "expected RESOURCE_EXHAUSTED or CANCELLED but was " + code);
        assertFalse(Files.exists(folder1.resolve("too-big.bin")));
    }
}