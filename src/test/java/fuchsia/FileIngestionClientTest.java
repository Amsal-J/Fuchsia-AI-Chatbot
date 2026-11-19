package fuchsia;

import static org.junit.jupiter.api.Assertions.*;

import fuchsia.FileIngestionClient.UploadResult;
import fuchsia.grpc.FileIngestionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIngestionClientTest {

    @TempDir
    Path tempDir;

    private Path folder1;     // where the server saves uploads
    private Path sourceDir;   // where the test creates files to send
    private Server server;
    private ManagedChannel channel;
    private FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        folder1 = Files.createDirectory(tempDir.resolve("folder1"));
        sourceDir = Files.createDirectory(tempDir.resolve("source"));

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new FileIngestionServiceImpl(folder1))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
        stub = FileIngestionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---------- helpers ----------

    private Path createSourceFile(String relativePath, byte[] content) throws IOException {
        Path file = sourceDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    private List<String> namesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---------- success ----------

    @Test
    void successfulUpload_returnsServerMessage() throws IOException {
        Path file = createSourceFile("hello.txt", "hi".getBytes());

        UploadResult result = FileIngestionClient.upload(stub, file);

        assertTrue(result.success(), result.toString());
        assertEquals("hello.txt", result.filename());
        assertEquals("Saved hello.txt", result.detail());
        assertEquals("hi", Files.readString(folder1.resolve("hello.txt")));
    }

    @Test
    void sendsOnlyTheFilename_notTheLocalPath() throws IOException {
        Path file = createSourceFile("some/deep/folder/report.txt", "data".getBytes());

        FileIngestionClient.upload(stub, file);

        assertEquals(List.of("report.txt"), namesIn(folder1));
    }

    @Test
    void fileExactlyAtMaxSize_isSent() throws IOException {
        byte[] content = new byte[(int) FileIngestionClient.MAX_FILE_BYTES];
        content[0] = 1;
        Path file = createSourceFile("max.bin", content);

        UploadResult result = FileIngestionClient.upload(stub, file);

        assertTrue(result.success(), result.toString());
    }

    // ---------- failures ----------

    @Test
    void duplicate_returnsAlreadyExistsFailure() throws IOException {
        Path file = createSourceFile("a.txt", "x".getBytes());
        FileIngestionClient.upload(stub, file);

        UploadResult second = FileIngestionClient.upload(stub, file);

        assertFalse(second.success());
        assertTrue(second.detail().startsWith("ALREADY_EXISTS"), second.detail());
    }

    @Test
    void invalidFilename_returnsInvalidArgumentFailure() throws IOException {
        Path file = createSourceFile(".hidden", "x".getBytes());

        UploadResult result = FileIngestionClient.upload(stub, file);

        assertFalse(result.success());
        assertTrue(result.detail().startsWith("INVALID_ARGUMENT"), result.detail());
    }

    @Test
    void missingLocalFile_returnsReadFailure_andSendsNothing() throws IOException {
        Path missing = sourceDir.resolve("does-not-exist.txt");

        UploadResult result = FileIngestionClient.upload(stub, missing);

        assertFalse(result.success());
        assertTrue(result.detail().startsWith("could not read file"), result.detail());
        assertTrue(namesIn(folder1).isEmpty());
    }

    @Test
    void fileOverMaxSize_isRejectedByClient_andNeverSent() throws IOException {
        byte[] content = new byte[(int) FileIngestionClient.MAX_FILE_BYTES + 1];
        Path file = createSourceFile("too-big.bin", content);

        UploadResult result = FileIngestionClient.upload(stub, file);

        assertFalse(result.success());
        assertTrue(result.detail().startsWith("file too large"), result.detail());
        assertTrue(namesIn(folder1).isEmpty());
    }

    @Test
    void serverUnavailable_returnsUnavailableFailure() throws Exception {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        Path file = createSourceFile("a.txt", "x".getBytes());

        UploadResult result = FileIngestionClient.upload(stub, file);

        assertFalse(result.success());
        assertTrue(result.detail().startsWith("UNAVAILABLE"), result.detail());
    }
}