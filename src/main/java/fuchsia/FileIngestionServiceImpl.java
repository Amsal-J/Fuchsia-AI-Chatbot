package fuchsia;

import fuchsia.grpc.FileIngestionServiceGrpc;
import fuchsia.grpc.UploadFileRequest;
import fuchsia.grpc.UploadFileResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileIngestionServiceImpl extends FileIngestionServiceGrpc.FileIngestionServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionServiceImpl.class);
    private static final int MAX_FILENAME_LENGTH = 255;

    private final Path targetDir;
    private final Object moveLock = new Object();

    public FileIngestionServiceImpl(Path targetDir) {
        this.targetDir = targetDir;
    }

    @Override
    public void uploadFile(UploadFileRequest request, StreamObserver<UploadFileResponse> responseObserver) {
        String filename = request.getFilename();

        // 1. Reject unsafe or invalid names
        String problem = validateFilename(filename);
        if (problem != null) {
            log.warn("Rejected upload '{}': {}", filename, problem);
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(problem).asRuntimeException());
            return;
        }

        Path target = targetDir.resolve(filename);
        Path temp = targetDir.resolve("." + filename + "." + UUID.randomUUID() + ".part");

        try {
            // 2. Write to a hidden temp file first
            Files.write(temp, request.getContent().toByteArray());

            // 3. Check for duplicates and move into place, one upload at a time
            synchronized (moveLock) {
                if (Files.exists(target)) {
                    Files.deleteIfExists(temp);
                    log.warn("Rejected upload '{}': already exists", filename);
                    responseObserver.onError(Status.ALREADY_EXISTS
                            .withDescription("A file named '" + filename + "' already exists")
                            .asRuntimeException());
                    return;
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }

            // 4. Tell the client it worked
            log.info("Saved {} ({} bytes) to {}", filename, request.getContent().size(), targetDir);
            responseObserver.onNext(UploadFileResponse.newBuilder()
                    .setAccepted(true)
                    .setMessage("Saved " + filename)
                    .build());
            responseObserver.onCompleted();

        } catch (IOException e) {
            log.error("Failed to save {}", filename, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Could not save file: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /** Returns a description of what's wrong with the filename, or null if it's fine. */
    static String validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Filename is required";
        }
        if (filename.length() > MAX_FILENAME_LENGTH) {
            return "Filename is too long (max " + MAX_FILENAME_LENGTH + " characters)";
        }
        if (filename.contains("/") || filename.contains("\\")) {
            return "Filename must not contain path separators";
        }
        if (filename.startsWith(".")) {
            return "Filenames starting with '.' are not allowed";
        }
        return null;
    }
}