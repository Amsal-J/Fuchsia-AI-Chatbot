package fuchsia;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import fuchsia.grpc.FileIngestionServiceGrpc;
import fuchsia.grpc.UploadFileRequest;
import fuchsia.grpc.UploadFileResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileIngestionServiceImplTest {

    @TempDir
    Path folder1;

    private Server server;
    private ManagedChannel channel;
    private FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub;

    // ---------- setup / teardown ----------

    @BeforeEach
    void setUp() throws IOException {
        startServer(folder1);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        stopServer();
    }

    private void startServer(Path targetDir) throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new FileIngestionServiceImpl(targetDir))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
        stub = FileIngestionServiceGrpc.newBlockingStub(channel);
    }

    private void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---------- helpers ----------

    private UploadFileResponse upload(String filename, byte[] content) {
        return stub.uploadFile(UploadFileRequest.newBuilder()
                .setFilename(filename)
                .setContent(ByteString.copyFrom(content))
                .build());
    }

    private Status uploadExpectingError(String filename, byte[] content) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> upload(filename, content));
        return e.getStatus();
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);   // fixed seed = same "random" bytes every run
        return bytes;
    }

    /** Every file in the folder, including hidden ones (so leftover .part files show up). */
    private static List<String> namesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Fires all uploads at the same moment from separate threads; returns each result code. */
    private List<Status.Code> uploadConcurrently(List<String> filenames) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(filenames.size());
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Status.Code>> futures = new ArrayList<>();
            for (int i = 0; i < filenames.size(); i++) {
                String name = filenames.get(i);
                byte[] content = text("writer-" + i);
                futures.add(pool.submit(() -> {
                    startGate.await();               // wait for the starting gun
                    try {
                        upload(name, content);
                        return Status.Code.OK;
                    } catch (StatusRuntimeException e) {
                        return e.getStatus().getCode();
                    }
                }));
            }
            startGate.countDown();                   // fire!
            List<Status.Code> results = new ArrayList<>();
            for (Future<Status.Code> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- successful uploads ----------

    @Test
    void upload_savesFileWithIdenticalBytes() throws IOException {
        byte[] content = randomBytes(64 * 1024);

        UploadFileResponse response = upload("data.bin", content);

        assertTrue(response.getAccepted());
        assertEquals("Saved data.bin", response.getMessage());
        assertArrayEquals(content, Files.readAllBytes(folder1.resolve("data.bin")));
    }

    @Test
    void upload_leavesNoTempFilesBehind() throws IOException {
        upload("a.txt", text("hello"));

        assertEquals(List.of("a.txt"), namesIn(folder1));
    }

    // ---------- rejections ----------

    @Test
    void duplicate_isRejected_andOriginalIsUnchanged() throws IOException {
        upload("a.txt", text("first"));

        Status status = uploadExpectingError("a.txt", text("second"));

        assertEquals(Status.Code.ALREADY_EXISTS, status.getCode());
        assertEquals("first", Files.readString(folder1.resolve("a.txt")));
        assertEquals(List.of("a.txt"), namesIn(folder1));   // no leftover temp file
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.txt", "a/b.txt", ".secret"})
    void invalidFilename_isRejected_andNothingIsWritten(String badName) throws IOException {
        Status status = uploadExpectingError(badName, text("data"));

        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertTrue(namesIn(folder1).isEmpty());
        assertFalse(Files.exists(folder1.getParent().resolve("evil.txt")),
                "path traversal must not write outside folder1");
    }

    @Test
    void emptyFile_isRejected_andNothingIsWritten() throws IOException {
        Status status = uploadExpectingError("empty.txt", new byte[0]);

        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("File is empty", status.getDescription());
        assertTrue(namesIn(folder1).isEmpty());
    }

    // ---------- concurrency ----------

    @Test
    void sameFilenameFromManyThreads_exactlyOneSucceeds() throws Exception {
        List<Status.Code> results = uploadConcurrently(Collections.nCopies(10, "same.txt"));

        assertEquals(1, Collections.frequency(results, Status.Code.OK), "results: " + results);
        assertEquals(9, Collections.frequency(results, Status.Code.ALREADY_EXISTS), "results: " + results);
        assertTrue(Files.readString(folder1.resolve("same.txt")).startsWith("writer-"));
        assertEquals(List.of("same.txt"), namesIn(folder1));   // no leftover temp files
    }

    @Test
    void differentFilenamesFromManyThreads_allSucceed() throws Exception {
        List<String> names = IntStream.range(0, 10).mapToObj(i -> "file-" + i + ".txt").toList();

        List<Status.Code> results = uploadConcurrently(names);

        assertTrue(results.stream().allMatch(c -> c == Status.Code.OK), "results: " + results);
        assertEquals(10, namesIn(folder1).size());
    }

    // ---------- failures ----------

    @Test
    void diskWriteFailure_returnsInternal_andServiceRecovers() throws Exception {
        Path missing = folder1.resolve("does-not-exist");
        stopServer();
        startServer(missing);   // point the service at a folder that isn't there

        Status first = uploadExpectingError("a.txt", text("data"));
        assertEquals(Status.Code.INTERNAL, first.getCode());

        Files.createDirectory(missing);          // fix the problem...
        UploadFileResponse response = upload("a.txt", text("data"));
        assertTrue(response.getAccepted());      // ...and the same server works again
    }
}