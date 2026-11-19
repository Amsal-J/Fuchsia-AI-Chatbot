package fuchsia;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileIngestionServer {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionServer.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        Path targetDir = Paths.get(Shared.SOURCE_DIR);
        Files.createDirectories(targetDir);

        Server server = ServerBuilder.forPort(Shared.GRPC_PORT)
            .addService(new FileIngestionServiceImpl(targetDir))
            .build()
            .start();

        log.info("gRPC server listening on port {}, saving to file {}", Shared.GRPC_PORT, targetDir.toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down gRPC server");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}