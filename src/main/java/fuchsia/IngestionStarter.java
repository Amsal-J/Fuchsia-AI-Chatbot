package fuchsia;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;

public class IngestionStarter {
    public static void main(String[] args) {
        WorkflowServiceStubs service = Shared.connect();
        WorkflowClient client = WorkflowClient.newInstance(service);

        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setWorkflowId(Shared.WORKFLOW_ID)
            .setTaskQueue(Shared.TASK_QUEUE)
            .build();

        IngestionWorkflow workflow = client.newWorkflowStub(IngestionWorkflow.class, options);

        try {
            WorkflowExecution execution = WorkflowClient.start(
                workflow::sync,
                Shared.SOURCE_DIR, Shared.DEST_DIR,
                Shared.POLLING_INTERVAL, Shared.LOOPS_BEFORE_RESET);

            System.out.printf("Started ingestion sync. WorkflowId=%s RunId=%s%n", execution.getWorkflowId(), execution.getRunId());
        } catch (WorkflowExecutionAlreadyStarted e) {
            System.out.println("Ingestion sync is already running (WorkflowId=" + Shared.WORKFLOW_ID + "). Nothing to do.");
        }

        System.exit(0);
    }
}