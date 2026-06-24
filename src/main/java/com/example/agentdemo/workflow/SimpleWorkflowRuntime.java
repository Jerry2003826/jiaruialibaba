package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.TraceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class SimpleWorkflowRuntime implements WorkflowRuntime {

    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowNodeRunner nodeRunner;
    private final TraceService traceService;
    private final ExecutorService executorService;

    public SimpleWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService executorService) {
        this.nodeExecutor = nodeExecutor;
        this.nodeRunner = new WorkflowNodeRunner(nodeExecutor, executorService);
        this.traceService = traceService;
        this.executorService = executorService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WorkflowNode node = executionPlan.startNode();
        while (node != null) {
            if (!visited.add(node.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
            }
            executeWithTrace(runId, node, state, summaries);
            if (node.id().equals(executionPlan.endNode().id())) {
                break;
            }
            if ("parallel".equals(nodeExecutor.normalizeType(node))) {
                node = executeParallelBranches(runId, executionPlan, node, state, summaries);
                continue;
            }
            node = nextNode(executionPlan, node, state);
        }
        return new WorkflowExecutionResult(state.finalOutput(), summaries);
    }

    private WorkflowNode nextNode(WorkflowExecutionPlan executionPlan, WorkflowNode current,
            WorkflowExecutionState state) {
        List<WorkflowExecutionEdge> outgoing = executionPlan.outgoing(current.id());
        if (outgoing.isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Workflow node has no outgoing edge: " + current.id());
        }
        if (outgoing.size() == 1 && !outgoing.getFirst().conditional()) {
            return executionPlan.node(outgoing.getFirst().to());
        }
        Boolean conditionResult = state.lastConditionResult();
        for (WorkflowExecutionEdge edge : outgoing) {
            if (edge.matches(conditionResult)) {
                return executionPlan.node(edge.to());
            }
        }
        throw new BusinessException("WORKFLOW_UNSUPPORTED",
                "No matching workflow edge for condition node: " + current.id());
    }

    private WorkflowNode executeParallelBranches(String runId, WorkflowExecutionPlan executionPlan,
            WorkflowNode parallelNode, WorkflowExecutionState state, List<WorkflowStepSummary> summaries) {
        List<BranchPath> branchPaths = branchPaths(executionPlan, parallelNode);
        int baseToolCallCount = state.toolCallCount();
        List<Future<BranchExecutionResult>> futures = branchPaths.stream()
                .map(path -> executorService.submit(() -> executeBranch(runId, path, state)))
                .toList();
        Map<String, Object> branchOutputs = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                BranchExecutionResult result = futures.get(i).get();
                summaries.addAll(result.summaries());
                state.mergeBranchState(result.state(), baseToolCallCount);
                branchOutputs.put(result.branchStartNodeId(), result.output());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                cancelFutures(futures);
                throw new BusinessException("WORKFLOW_INTERRUPTED",
                        "Workflow parallel branches were interrupted: " + parallelNode.id(), ex);
            }
            catch (ExecutionException ex) {
                cancelFutures(futures);
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new BusinessException("WORKFLOW_PARALLEL_FAILED",
                        "Workflow parallel branch failed: " + parallelNode.id(), ex);
            }
        }
        state.setParallelBranchOutputs(branchOutputs);
        return branchPaths.getFirst().joinNode();
    }

    private BranchExecutionResult executeBranch(String runId, BranchPath path, WorkflowExecutionState parentState) {
        WorkflowExecutionState branchState = parentState.copyForBranch();
        List<WorkflowStepSummary> branchSummaries = new ArrayList<>();
        for (WorkflowNode branchNode : path.nodes()) {
            executeWithTrace(runId, branchNode, branchState, branchSummaries);
        }
        return new BranchExecutionResult(path.branchStartNodeId(), branchState.lastOutput(), branchState,
                branchSummaries);
    }

    private List<BranchPath> branchPaths(WorkflowExecutionPlan executionPlan, WorkflowNode parallelNode) {
        List<WorkflowExecutionEdge> outgoing = executionPlan.outgoing(parallelNode.id());
        List<BranchPath> paths = new ArrayList<>();
        WorkflowNode commonJoin = null;
        for (WorkflowExecutionEdge edge : outgoing) {
            BranchPath path = branchPath(executionPlan, edge.to());
            if (commonJoin == null) {
                commonJoin = path.joinNode();
            }
            else if (!commonJoin.id().equals(path.joinNode().id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Parallel branches must converge to the same join node: " + parallelNode.id());
            }
            paths.add(path);
        }
        return paths;
    }

    private BranchPath branchPath(WorkflowExecutionPlan executionPlan, String branchStartNodeId) {
        List<WorkflowNode> nodes = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WorkflowNode current = executionPlan.node(branchStartNodeId);
        while (current != null) {
            if (!visited.add(current.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
            }
            if ("join".equals(nodeExecutor.normalizeType(current))) {
                return new BranchPath(branchStartNodeId, nodes, current);
            }
            nodes.add(current);
            List<WorkflowExecutionEdge> outgoing = executionPlan.outgoing(current.id());
            if (outgoing.size() != 1 || outgoing.getFirst().conditional()) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Parallel branch must be linear before join: " + current.id());
            }
            current = executionPlan.node(outgoing.getFirst().to());
        }
        throw new BusinessException("WORKFLOW_UNSUPPORTED",
                "Parallel branch references missing node: " + branchStartNodeId);
    }

    private void cancelFutures(List<Future<BranchExecutionResult>> futures) {
        for (Future<BranchExecutionResult> future : futures) {
            future.cancel(true);
        }
    }

    private void executeWithTrace(String runId, WorkflowNode node, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {
        RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            WorkflowNodeExecutionResult result = nodeRunner.execute(runId, node, state);
            Object output = result.output();
            state.recordNodeOutput(node.id());
            traceService.completeStep(step.getStepId(), result.traceOutput());
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "SUCCEEDED", output));
        }
        catch (WorkflowNodeExecutionFailure ex) {
            traceService.failStep(step.getStepId(), ex.original(), ex.traceOutput());
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED",
                    ex.summaryOutput()));
            throw ex.original();
        }
        catch (RuntimeException ex) {
            Object failureOutput = failureOutput(ex);
            traceService.failStep(step.getStepId(), ex, failureOutput);
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED", failureOutput));
            throw ex;
        }
    }

    private Object failureOutput(RuntimeException ex) {
        if (ex instanceof WorkflowNodeExecutionException nodeException) {
            return nodeException.output();
        }
        return nodeExecutor.errorOutput(ex);
    }

    private record BranchPath(String branchStartNodeId, List<WorkflowNode> nodes, WorkflowNode joinNode) {

        private BranchPath {
            nodes = List.copyOf(nodes);
        }

    }

    private record BranchExecutionResult(String branchStartNodeId, Object output, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {

        private BranchExecutionResult {
            summaries = List.copyOf(summaries);
        }

    }

}
