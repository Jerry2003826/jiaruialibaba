package com.example.agentdemo.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.TraceService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class GraphWorkflowRuntime implements WorkflowRuntime {

    private static final String NODE_OUTPUT_STATE_KEY = "lastNodeOutput";

    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowNodeRunner nodeRunner;
    private final TraceService traceService;

    public GraphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService executorService) {
        this.nodeExecutor = nodeExecutor;
        this.nodeRunner = new WorkflowNodeRunner(nodeExecutor, executorService);
        this.traceService = traceService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input) {
        GraphExecutionContext context = new GraphExecutionContext(executionPlan, new WorkflowExecutionState(input));
        try {
            CompiledGraph graph = compileGraph(runId, executionPlan, context);
            graph.invoke(Map.of("workflowInput", input));
            return new WorkflowExecutionResult(context.rootState().finalOutput(), context.summaries());
        }
        catch (GraphStateException ex) {
            throw new BusinessException("WORKFLOW_GRAPH_FAILED", "Failed to compile Spring AI Alibaba Graph", ex);
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new BusinessException("WORKFLOW_GRAPH_FAILED", "Failed to run Spring AI Alibaba Graph", ex);
        }
    }

    private CompiledGraph compileGraph(String runId, WorkflowExecutionPlan executionPlan,
            GraphExecutionContext context) throws GraphStateException {
        StateGraph graph = new StateGraph("workflow-" + runId,
                KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            graph.addNode(node.id(), AsyncNodeAction.node_async(overAllState ->
                    executeGraphNode(runId, node, context)));
        }
        for (BranchPath branchPath : context.branchPaths()) {
            graph.addNode(branchPath.graphNodeId(), AsyncNodeAction.node_async(overAllState ->
                    executeGraphBranch(runId, branchPath, context)));
        }
        graph.addEdge(StateGraph.START, executionPlan.startNode().id());
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            if (node.id().equals(executionPlan.endNode().id())) {
                continue;
            }
            if (context.branchStartNodeId(node.id()) != null) {
                continue;
            }
            addOutgoingEdges(graph, node, executionPlan.outgoing(node.id()), context);
        }
        addBranchEdges(graph, context);
        graph.addEdge(executionPlan.endNode().id(), StateGraph.END);
        return graph.compile();
    }

    private void addOutgoingEdges(StateGraph graph, WorkflowNode node, List<WorkflowExecutionEdge> outgoing,
            GraphExecutionContext context) throws GraphStateException {
        if (outgoing.size() == 1 && !outgoing.getFirst().conditional()) {
            graph.addEdge(node.id(), outgoing.getFirst().to());
            return;
        }
        String type = nodeExecutor.normalizeType(node);
        if ("parallel".equals(type)) {
            graph.addEdge(node.id(), outgoing.stream()
                    .map(edge -> context.branchGraphNodeId(edge.to()))
                    .toList());
            return;
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        for (WorkflowExecutionEdge edge : outgoing) {
            mappings.put(edge.condition(), edge.to());
        }
        graph.addConditionalEdges(node.id(),
                ignored -> CompletableFuture.completedFuture(conditionRouteKey(node, context.rootState())),
                mappings);
    }

    private void addBranchEdges(StateGraph graph, GraphExecutionContext context) throws GraphStateException {
        for (BranchPath branchPath : context.branchPaths()) {
            graph.addEdge(branchPath.graphNodeId(), branchPath.joinNodeId());
        }
    }

    private String conditionRouteKey(WorkflowNode node, WorkflowExecutionState state) {
        Boolean conditionResult = state.lastConditionResult();
        if (conditionResult == null) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Condition node did not produce a boolean result: " + node.id());
        }
        return conditionResult.toString();
    }

    private Map<String, Object> executeGraphNode(String runId, WorkflowNode node, GraphExecutionContext context) {
        if ("join".equals(nodeExecutor.normalizeType(node))) {
            synchronized (context.rootLock()) {
                context.prepareJoinInput(node);
                return executeGraphNodeLocked(runId, node, context.rootState(), context);
            }
        }
        String branchStartNodeId = context.branchStartNodeId(node.id());
        if (branchStartNodeId != null) {
            return executeGraphNodeLocked(runId, node, context.branchState(branchStartNodeId).state(), context);
        }
        synchronized (context.rootLock()) {
            return executeGraphNodeLocked(runId, node, context.rootState(), context);
        }
    }

    private Map<String, Object> executeGraphBranch(String runId, BranchPath branchPath,
            GraphExecutionContext context) {
        BranchState branchState = context.branchState(branchPath.branchStartNodeId());
        Map<String, Object> lastGraphOutput = Map.of();
        for (String nodeId : branchPath.nodeIds()) {
            WorkflowNode node = context.executionPlan().node(nodeId);
            lastGraphOutput = executeGraphNodeLocked(runId, node, branchState.state(), context);
        }
        return lastGraphOutput;
    }

    private Map<String, Object> executeGraphNodeLocked(String runId, WorkflowNode node, WorkflowExecutionState state,
            GraphExecutionContext context) {
        RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            WorkflowNodeExecutionResult result = nodeRunner.execute(runId, node, state);
            Object output = result.output();
            state.recordNodeOutput(node.id());
            traceService.completeStep(step.getStepId(), result.traceOutput());
            context.addSummary(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "SUCCEEDED",
                    output));
            return Map.of(NODE_OUTPUT_STATE_KEY, output == null ? "" : output);
        }
        catch (WorkflowNodeExecutionFailure ex) {
            traceService.failStep(step.getStepId(), ex.original(), ex.traceOutput());
            context.addSummary(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED",
                    ex.summaryOutput()));
            throw ex.original();
        }
        catch (RuntimeException ex) {
            Object failureOutput = failureOutput(ex);
            traceService.failStep(step.getStepId(), ex, failureOutput);
            context.addSummary(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED",
                    failureOutput));
            throw ex;
        }
    }

    private Object failureOutput(RuntimeException ex) {
        if (ex instanceof WorkflowNodeExecutionException nodeException) {
            return nodeException.output();
        }
        return nodeExecutor.errorOutput(ex);
    }

    private Map<String, BranchPath> branchPathsByStartNodeId(WorkflowExecutionPlan executionPlan) {
        Map<String, BranchPath> branchPaths = new LinkedHashMap<>();
        Set<String> reservedNodeIds = new HashSet<>(executionPlan.nodesById().keySet());
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            if (!"parallel".equals(nodeExecutor.normalizeType(node))) {
                continue;
            }
            for (WorkflowExecutionEdge edge : executionPlan.outgoing(node.id())) {
                String branchStartNodeId = edge.to();
                WorkflowNode current = executionPlan.node(branchStartNodeId);
                List<String> nodeIds = new ArrayList<>();
                while (!"join".equals(nodeExecutor.normalizeType(current))) {
                    nodeIds.add(current.id());
                    current = executionPlan.node(executionPlan.outgoing(current.id()).getFirst().to());
                }
                String graphNodeId = syntheticBranchNodeId(node.id(), branchStartNodeId, reservedNodeIds);
                branchPaths.put(branchStartNodeId,
                        new BranchPath(node.id(), branchStartNodeId, current.id(), graphNodeId,
                                List.copyOf(nodeIds)));
            }
        }
        return branchPaths;
    }

    private String syntheticBranchNodeId(String parallelNodeId, String branchStartNodeId,
            Set<String> reservedNodeIds) {
        String baseId = "workflow_branch_" + parallelNodeId + "_" + branchStartNodeId;
        String candidate = baseId;
        int suffix = 1;
        while (!reservedNodeIds.add(candidate)) {
            candidate = baseId + "_" + suffix++;
        }
        return candidate;
    }

    private Map<String, String> branchStartNodeIdsByNode(Map<String, BranchPath> branchPathsByStartNodeId) {
        Map<String, String> branchStarts = new HashMap<>();
        for (BranchPath branchPath : branchPathsByStartNodeId.values()) {
            for (String nodeId : branchPath.nodeIds()) {
                branchStarts.put(nodeId, branchPath.branchStartNodeId());
            }
        }
        return branchStarts;
    }

    private final class GraphExecutionContext {

        private final WorkflowExecutionPlan executionPlan;
        private final WorkflowExecutionState rootState;
        private final Object rootLock = new Object();
        private final Map<String, BranchPath> branchPathsByStartNodeId;
        private final Map<String, String> branchStartNodeIdsByNode;
        private final Map<String, BranchState> branchStates = new HashMap<>();
        private final List<WorkflowStepSummary> summaries = Collections.synchronizedList(new ArrayList<>());

        private GraphExecutionContext(WorkflowExecutionPlan executionPlan, WorkflowExecutionState rootState) {
            this.executionPlan = executionPlan;
            this.rootState = rootState;
            this.branchPathsByStartNodeId = branchPathsByStartNodeId(executionPlan);
            this.branchStartNodeIdsByNode = branchStartNodeIdsByNode(branchPathsByStartNodeId);
        }

        private WorkflowExecutionPlan executionPlan() {
            return executionPlan;
        }

        private WorkflowExecutionState rootState() {
            return rootState;
        }

        private Object rootLock() {
            return rootLock;
        }

        private String branchStartNodeId(String nodeId) {
            return branchStartNodeIdsByNode.get(nodeId);
        }

        private List<BranchPath> branchPaths() {
            return List.copyOf(branchPathsByStartNodeId.values());
        }

        private String branchGraphNodeId(String branchStartNodeId) {
            BranchPath branchPath = branchPathsByStartNodeId.get(branchStartNodeId);
            return branchPath == null ? branchStartNodeId : branchPath.graphNodeId();
        }

        private BranchState branchState(String branchStartNodeId) {
            synchronized (rootLock) {
                return branchStates.computeIfAbsent(branchStartNodeId,
                        ignored -> new BranchState(rootState.copyForBranch(), rootState.toolCallCount()));
            }
        }

        private void prepareJoinInput(WorkflowNode joinNode) {
            Map<String, Object> branchOutputs = new LinkedHashMap<>();
            for (BranchPath branchPath : branchPathsByStartNodeId.values()) {
                if (!branchPath.joinNodeId().equals(joinNode.id())) {
                    continue;
                }
                String branchStartNodeId = branchPath.branchStartNodeId();
                BranchState branchState = branchStates.get(branchStartNodeId);
                if (branchState == null) {
                    throw new BusinessException("WORKFLOW_UNSUPPORTED",
                            "Parallel branch did not complete before join: " + branchStartNodeId);
                }
                rootState.mergeBranchState(branchState.state(), branchState.baseToolCallCount());
                branchOutputs.put(branchStartNodeId, branchState.state().lastOutput());
            }
            if (branchOutputs.isEmpty()) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Join node is not connected to a compiled parallel branch: " + joinNode.id());
            }
            rootState.setParallelBranchOutputs(branchOutputs);
        }

        private void addSummary(WorkflowStepSummary summary) {
            summaries.add(summary);
        }

        private List<WorkflowStepSummary> summaries() {
            return List.copyOf(summaries);
        }

    }

    private record BranchState(WorkflowExecutionState state, int baseToolCallCount) {
    }

    private record BranchPath(String parallelNodeId, String branchStartNodeId, String joinNodeId, String graphNodeId,
            List<String> nodeIds) {

    }

}
