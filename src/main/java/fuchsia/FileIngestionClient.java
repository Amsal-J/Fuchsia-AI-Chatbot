package fuchsia;

import com.google.protobuf.ByteString;
import fuchsia.grpc.FileIngestionServiceGrpc;
import fuchsia.grpc.UploadFileRequest;
import fuchsia.grpc.UploadFileResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class FileIngestionClient {

    /** gRPC's 4 MB message limit, minus room for the filename and protobuf overhead. */
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024 - 1024;

    /** The outcome of one upload. */
    record UploadResult(String filename, boolean success, String detail) {
        @Override
        public String toString() {
            return (success ? "OK   " : "FAIL ") + filename + ": " + detail;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 0) {
            System.err.println("Usage: FileIngestionClient <file> [more files...]");
            System.exit(1);
        }

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(Shared.GRPC_HOST, Shared.GRPC_PORT)
                .usePlaintext()
                .build();

        try {
            FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub =
                    FileIngestionServiceGrpc.newBlockingStub(channel);

            for (String arg : args) {
                System.out.println(upload(stub, Paths.get(arg)));
            }
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static UploadResult upload(FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub, Path file) {
        String filename = file.getFileName().toString();
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                return new UploadResult(filename, false,
                        "file too large (" + size + " bytes, max " + MAX_FILE_BYTES + ")");
            }

            byte[] bytes = Files.readAllBytes(file);
            UploadFileRequest request = UploadFileRequest.newBuilder()
                    .setFilename(filename)
                    .setContent(ByteString.copyFrom(bytes))
                    .build();

            UploadFileResponse response = stub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .uploadFile(request);

            return new UploadResult(filename, true, response.getMessage());

        } catch (IOException e) {
            return new UploadResult(filename, false, "could not read file (" + e.getMessage() + ")");
        } catch (StatusRuntimeException e) {
            return new UploadResult(filename, false,
                    e.getStatus().getCode() + " - " + e.getStatus().getDescription());
        }
    }
}