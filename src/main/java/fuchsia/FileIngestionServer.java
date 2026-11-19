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
        Server server = start(Shared.GRPC_PORT, Paths.get(Shared.SOURCE_DIR));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down gRPC server");
            server.shutdown();
        }));

        server.awaitTermination();
    }

    /**
     * Creates the target folder if needed and starts the server.
     * Pass port 0 to let the operating system pick any free port.
     */
    static Server start(int port, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        Server server = ServerBuilder.forPort(port)
                .addService(new FileIngestionServiceImpl(targetDir))
                .build()
                .start();

        log.info("gRPC server listening on port {}, saving files to {}",
                server.getPort(), targetDir.toAbsolutePath());
        return server;
    }
}