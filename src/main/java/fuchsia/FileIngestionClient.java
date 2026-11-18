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
                upload(stub, Paths.get(arg));
            }
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void upload(FileIngestionServiceGrpc.FileIngestionServiceBlockingStub stub, Path file) {
        String filename = file.getFileName().toString();
        try {
            byte[] bytes = Files.readAllBytes(file);

            UploadFileRequest request = UploadFileRequest.newBuilder()
                    .setFilename(filename)
                    .setContent(ByteString.copyFrom(bytes))
                    .build();

            UploadFileResponse response = stub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .uploadFile(request);

            System.out.println("OK   " + filename + ": " + response.getMessage());

        } catch (IOException e) {
            System.out.println("FAIL " + filename + ": could not read file (" + e.getMessage() + ")");
        } catch (StatusRuntimeException e) {
            System.out.println("FAIL " + filename + ": " + e.getStatus().getCode()
                    + " - " + e.getStatus().getDescription());
        }
    }
}