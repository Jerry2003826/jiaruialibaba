package com.example.agentdemo.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.agentdemo.common.BusinessException;
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
    private final WorkflowNodeTraceExecutor traceExecutor;
    private final WorkflowInlineExecutionService inlineExecutionService;

    public GraphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService executorService, WorkflowInlineExecutionService inlineExecutionService) {
        this(nodeExecutor, traceService, executorService, inlineExecutionService, new WorkflowRunBudgetRegistry());
    }

    public GraphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService executorService, WorkflowInlineExecutionService inlineExecutionService,
            WorkflowRunBudgetRegistry budgetRegistry) {
        this.nodeExecutor = nodeExecutor;
        this.traceExecutor = new WorkflowNodeTraceExecutor(nodeExecutor, traceService, executorService, budgetRegistry);
        this.inlineExecutionService = inlineExecutionService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input) {
        inlineExecutionService.bindPlan(executionPlan);
        try {
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
        finally {
            inlineExecutionService.clearPlan();
        }
    }

    private CompiledGraph compileGraph(String runId, WorkflowExecutionPlan executionPlan,
            GraphExecutionContext context) throws GraphStateException {
        StateGraph graph = new StateGraph("workflow-" + runId,
                KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
        WorkflowInlineExecutionService.InlineExecutionContext inlineContext = inlineExecutionService.captureContext();
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            if (executionPlan.isCompositeScopedNode(node.id())) {
                continue;
            }
            graph.addNode(node.id(), AsyncNodeAction.node_async(overAllState ->
                    inlineExecutionService.callWithContext(inlineContext,
                            () -> executeGraphNode(runId, node, context))));
        }
        for (BranchPath branchPath : context.branchPaths()) {
            graph.addNode(branchPath.graphNodeId(), AsyncNodeAction.node_async(overAllState ->
                    inlineExecutionService.callWithContext(inlineContext,
                            () -> executeGraphBranch(runId, branchPath, context))));
        }
        graph.addEdge(StateGraph.START, executionPlan.startNode().id());
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            if (node.id().equals(executionPlan.endNode().id())) {
                continue;
            }
            if (executionPlan.isCompositeScopedNode(node.id())) {
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
        if ("loop".equals(type)) {
            WorkflowExecutionEdge exitEdge = outgoing.stream()
                    .filter(edge -> "exit".equals(edge.condition()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("WORKFLOW_UNSUPPORTED",
                            "Loop node missing exit edge: " + node.id()));
            graph.addEdge(node.id(), exitEdge.to());
            return;
        }
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
        try {
            WorkflowNodeTraceResult result = traceExecutor.execute(runId, node, state);
            Object output = result.output();
            context.addSummary(result.summary());
            if (context.executionPlan().isCompositeContainerNode(node.id())) {
                inlineExecutionService.drainInlineStepSummaries().forEach(context::addSummary);
            }
            return Map.of(NODE_OUTPUT_STATE_KEY, output == null ? "" : output);
        }
        catch (WorkflowNodeTraceExecutor.TracedWorkflowNodeFailure ex) {
            context.addSummary(ex.summary());
            if (context.executionPlan().isCompositeContainerNode(node.id())) {
                inlineExecutionService.drainInlineStepSummaries();
            }
            throw ex.original();
        }
    }

    private Map<String, BranchPath> branchPathsByStartNodeId(WorkflowExecutionPlan executionPlan) {
        Map<String, BranchPath> branchPaths = new LinkedHashMap<>();
        Set<String> reservedNodeIds = new HashSet<>(executionPlan.nodesById().keySet());
        for (WorkflowParallelBlock block : executionPlan.parallelBlocks()) {
            for (WorkflowBranchPath branch : block.branches()) {
                String graphNodeId = syntheticBranchNodeId(block.parallelNodeId(), branch.branchStartNodeId(),
                        reservedNodeIds);
                branchPaths.put(branch.branchStartNodeId(),
                        new BranchPath(block.parallelNodeId(), branch.branchStartNodeId(), branch.joinNodeId(),
                                graphNodeId, branch.nodeIds()));
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
