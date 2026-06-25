package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
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
    private final WorkflowNodeTraceExecutor traceExecutor;
    private final ExecutorService executorService;
    private final WorkflowInlineExecutionService inlineExecutionService;

    public SimpleWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService executorService, WorkflowInlineExecutionService inlineExecutionService) {
        this.nodeExecutor = nodeExecutor;
        this.traceExecutor = new WorkflowNodeTraceExecutor(nodeExecutor, traceService, executorService);
        this.executorService = executorService;
        this.inlineExecutionService = inlineExecutionService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input) {
        inlineExecutionService.bindPlan(executionPlan);
        try {
            return runBound(runId, executionPlan, input);
        }
        finally {
            inlineExecutionService.clearPlan();
        }
    }

    private WorkflowExecutionResult runBound(String runId, WorkflowExecutionPlan executionPlan,
            Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WorkflowNode node = executionPlan.startNode();
        while (node != null) {
            if (executionPlan.isCompositeScopedNode(node.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Composite-scoped node must not be executed in main workflow path: " + node.id());
            }
            if (!visited.add(node.id()) && !"loop_back".equals(nodeExecutor.normalizeType(node))) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
            }
            executeWithTrace(runId, node, state, summaries, executionPlan);
            if (node.id().equals(executionPlan.endNode().id())) {
                break;
            }
            if ("loop".equals(nodeExecutor.normalizeType(node))) {
                node = executionPlan.node(executionPlan.loopBlock(node.id()).exitNodeId());
                continue;
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
        WorkflowParallelBlock parallelBlock = executionPlan.parallelBlock(parallelNode.id());
        List<WorkflowBranchPath> branchPaths = parallelBlock.branches();
        int baseToolCallCount = state.toolCallCount();
        List<Future<BranchExecutionResult>> futures = branchPaths.stream()
                .map(path -> executorService.submit(() -> executeBranch(runId, executionPlan, path, state)))
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
        return executionPlan.node(parallelBlock.joinNodeId());
    }

    private BranchExecutionResult executeBranch(String runId, WorkflowExecutionPlan executionPlan,
            WorkflowBranchPath path, WorkflowExecutionState parentState) {
        WorkflowExecutionState branchState = parentState.copyForBranch();
        List<WorkflowStepSummary> branchSummaries = new ArrayList<>();
        for (String nodeId : path.nodeIds()) {
            WorkflowNode branchNode = executionPlan.node(nodeId);
            executeWithTrace(runId, branchNode, branchState, branchSummaries, executionPlan);
        }
        return new BranchExecutionResult(path.branchStartNodeId(), branchState.lastOutput(), branchState,
                branchSummaries);
    }

    private void cancelFutures(List<Future<BranchExecutionResult>> futures) {
        for (Future<BranchExecutionResult> future : futures) {
            future.cancel(true);
        }
    }

    private void executeWithTrace(String runId, WorkflowNode node, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries, WorkflowExecutionPlan executionPlan) {
        try {
            summaries.add(traceExecutor.execute(runId, node, state).summary());
            if (executionPlan.isCompositeContainerNode(node.id())) {
                summaries.addAll(inlineExecutionService.drainInlineStepSummaries());
            }
        }
        catch (WorkflowNodeTraceExecutor.TracedWorkflowNodeFailure ex) {
            summaries.add(ex.summary());
            if (executionPlan.isCompositeContainerNode(node.id())) {
                inlineExecutionService.drainInlineStepSummaries();
            }
            throw ex.original();
        }
    }

    private record BranchExecutionResult(String branchStartNodeId, Object output, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {

        private BranchExecutionResult {
            summaries = List.copyOf(summaries);
        }

    }

}
