package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.TraceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SimpleWorkflowRuntime implements WorkflowRuntime {

    private final WorkflowNodeExecutor nodeExecutor;
    private final TraceService traceService;

    public SimpleWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService) {
        this.nodeExecutor = nodeExecutor;
        this.traceService = traceService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, List<WorkflowNode> orderedNodes, Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        for (WorkflowNode node : orderedNodes) {
            executeWithTrace(runId, node, state, summaries);
        }
        return new WorkflowExecutionResult(state.finalOutput(), summaries);
    }

    private void executeWithTrace(String runId, WorkflowNode node, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {
        RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            Object output = nodeExecutor.execute(runId, node, state);
            traceService.completeStep(step.getStepId(), output);
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "SUCCEEDED", output));
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.getStepId(), ex);
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED",
                    nodeExecutor.errorOutput(ex)));
            throw ex;
        }
    }

}
