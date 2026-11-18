package fuchsia;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

public final class Shared {
    public static final String TASK_QUEUE = "FUCHSIA_INGESTION_TASK_QUEUE";
    public static final String WORKFLOW_ID = "fuchsia-ingestion";
    public static final String SOURCE_DIR = "folder1";
    public static final String DEST_DIR = "folder2";
    public static final int POLLING_INTERVAL = 60;
    public static final int LOOPS_BEFORE_RESET = 100;
    public static final String TEMPORAL_ADDRESS = System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233");
    public static final int GRPC_PORT = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "50051"));
    public static final String GRPC_HOST = System.getenv().getOrDefault("GRPC_HOST", "localhost");

    public static WorkflowServiceStubs connect() {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(TEMPORAL_ADDRESS)
                .build());
    }

    private Shared() {}
}
