package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;

import java.util.concurrent.ExecutorService;

class WorkflowNodeTraceExecutor {

    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowNodeRunner nodeRunner;
    private final TraceService traceService;

    WorkflowNodeTraceExecutor(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService executorService) {
        this.nodeExecutor = nodeExecutor;
        this.nodeRunner = new WorkflowNodeRunner(nodeExecutor, executorService);
        this.traceService = traceService;
    }

    WorkflowNodeTraceResult execute(String runId, WorkflowNode node, WorkflowExecutionState state) {
        TraceStep step = traceService.startTraceStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            WorkflowNodeExecutionResult result = nodeRunner.execute(runId, node, state);
            Object output = result.output();
            state.recordNodeOutput(node.id());
            traceService.completeStep(step.stepId(), result.traceOutput());
            return new WorkflowNodeTraceResult(output,
                    new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "SUCCEEDED", output));
        }
        catch (WorkflowNodeExecutionFailure ex) {
            traceService.failStep(step.stepId(), ex.original(), ex.traceOutput());
            throw new TracedWorkflowNodeFailure(ex.original(),
                    new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED",
                            ex.summaryOutput()));
        }
        catch (RuntimeException ex) {
            Object failureOutput = failureOutput(ex);
            traceService.failStep(step.stepId(), ex, failureOutput);
            throw new TracedWorkflowNodeFailure(ex,
                    new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED", failureOutput));
        }
    }

    private Object failureOutput(RuntimeException ex) {
        if (ex instanceof WorkflowNodeExecutionException nodeException) {
            return nodeException.output();
        }
        return nodeExecutor.errorOutput(ex);
    }

    static final class TracedWorkflowNodeFailure extends RuntimeException {

        private final RuntimeException original;
        private final WorkflowStepSummary summary;

        private TracedWorkflowNodeFailure(RuntimeException original, WorkflowStepSummary summary) {
            super(original.getMessage(), original);
            this.original = original;
            this.summary = summary;
        }

        RuntimeException original() {
            return original;
        }

        WorkflowStepSummary summary() {
            return summary;
        }

    }

}
