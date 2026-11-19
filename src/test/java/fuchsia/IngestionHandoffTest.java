package fuchsia;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import fuchsia.grpc.FileIngestionServiceGrpc;
import fuchsia.grpc.UploadFileRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks that files uploaded through gRPC are exactly what the Temporal sync expects. */
class IngestionHandoffTest {

    @TempDir
    Path tempDir;

    private Path folder1;
    private Path folder2;
    private Server server;
    private ManagedChannel channel;
    private FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub;
    private final IngestionActivitiesImpl activities = new IngestionActivitiesImpl();

    @BeforeEach
    void setUp() throws IOException {
        folder1 = Files.createDirectory(tempDir.resolve("folder1"));
        folder2 = Files.createDirectory(tempDir.resolve("folder2"));

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

    private void upload(String filename, byte[] content) {
        stub.uploadFile(UploadFileRequest.newBuilder()
                .setFilename(filename)
                .setContent(ByteString.copyFrom(content))
                .build());
    }

    private List<String> scan() {
        return activities.findNewFiles(folder1.toString(), folder2.toString());
    }

    @Test
    void uploadedFile_isFoundBySync_andCopiedWithIdenticalBytes() throws IOException {
        byte[] content = new byte[100_000];
        new Random(7).nextBytes(content);

        upload("report.pdf", content);                 // gRPC → folder1

        assertEquals(List.of("report.pdf"), scan());   // the sync sees it

        activities.copyFile(folder1.toString(), folder2.toString(), "report.pdf");   // folder1 → folder2

        assertArrayEquals(content, Files.readAllBytes(folder2.resolve("report.pdf")));
        assertTrue(scan().isEmpty(), "once synced, the file should not be found again");
    }

    @Test
    void inProgressUploadTempFiles_areInvisibleToTheSync() throws IOException {
        // While an upload is being written, the server keeps it in a hidden
        // ".<name>.<random>.part" file. The sync must never pick that up.
        Files.writeString(folder1.resolve(".report.pdf.1234.part"), "half-written data");

        assertTrue(scan().isEmpty());
    }

    @Test
    void rejectedUploads_neverReachTheSync() {
        assertThrows(StatusRuntimeException.class, () -> upload("empty.txt", new byte[0]));
        assertThrows(StatusRuntimeException.class, () -> upload(".hidden", "x".getBytes()));
        assertThrows(StatusRuntimeException.class, () -> upload("../escape.txt", "x".getBytes()));

        assertTrue(scan().isEmpty());
    }
}