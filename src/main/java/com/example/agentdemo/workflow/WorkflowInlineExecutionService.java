package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.TraceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Component
public class WorkflowInlineExecutionService {

    private static final int MAX_SUBGRAPH_NESTING_DEPTH = 10;

    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowCompiler workflowCompiler;
    private final ObjectProvider<WorkflowRuntime> workflowRuntimeProvider;
    private final ObjectProvider<WorkflowNodeExecutor> nodeExecutorProvider;
    private final WorkflowVariableResolver variableResolver;
    private final TraceService traceService;
    private final ExecutorService workflowNodeExecutorService;
    private final ThreadLocal<Deque<WorkflowExecutionPlan>> activePlans = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Integer> subgraphNestingDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<List<WorkflowStepSummary>> inlineStepSummaries = ThreadLocal.withInitial(ArrayList::new);

    public WorkflowInlineExecutionService(WorkflowDefinitionService workflowDefinitionService,
            WorkflowCompiler workflowCompiler, ObjectProvider<WorkflowRuntime> workflowRuntimeProvider,
            ObjectProvider<WorkflowNodeExecutor> nodeExecutorProvider, WorkflowVariableResolver variableResolver,
            TraceService traceService, ExecutorService workflowNodeExecutorService) {
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntimeProvider = workflowRuntimeProvider;
        this.nodeExecutorProvider = nodeExecutorProvider;
        this.variableResolver = variableResolver;
        this.traceService = traceService;
        this.workflowNodeExecutorService = workflowNodeExecutorService;
    }

    void bindPlan(WorkflowExecutionPlan plan) {
        activePlans.get().push(plan);
    }

    void clearPlan() {
        Deque<WorkflowExecutionPlan> plans = activePlans.get();
        if (!plans.isEmpty()) {
            plans.pop();
        }
        if (plans.isEmpty()) {
            activePlans.remove();
            subgraphNestingDepth.remove();
            inlineStepSummaries.remove();
        }
    }

    List<WorkflowStepSummary> drainInlineStepSummaries() {
        List<WorkflowStepSummary> drained = List.copyOf(inlineStepSummaries.get());
        inlineStepSummaries.get().clear();
        return drained;
    }

    Object executeLoop(String runId, WorkflowNode node, WorkflowExecutionState state) {
        WorkflowExecutionPlan plan = requirePlan();
        WorkflowLoopBlock loopBlock = plan.loopBlock(node.id());
        int maxIterations = loopBlock.maxIterations();
        List<Object> iterationOutputs = new ArrayList<>();

        while (state.loopIteration(node.id()) < maxIterations) {
            if (!evaluateLoopCondition(node, state)) {
                break;
            }
            for (String bodyNodeId : loopBlock.bodyNodeIds()) {
                WorkflowNode bodyNode = plan.node(bodyNodeId);
                recordInlineSummary(traceExecutor().execute(runId, bodyNode, state).summary());
            }
            iterationOutputs.add(state.lastOutput());
            state.incrementLoopIteration(node.id());
        }

        Map<String, Object> output = orderedMap();
        output.put("iterations", state.loopIteration(node.id()));
        output.put("maxIterations", maxIterations);
        output.put("iterationOutputs", List.copyOf(iterationOutputs));
        output.put("lastIterationOutput", iterationOutputs.isEmpty() ? null : iterationOutputs.getLast());
        state.setLastOutput(output);
        return output;
    }

    Object executeSubgraph(String runId, WorkflowNode node, WorkflowExecutionState state) {
        String definitionId = configString(node, "definitionId");
        if (!StringUtils.hasText(definitionId)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Subgraph node requires config.definitionId: " + node.id());
        }
        int nestingDepth = subgraphNestingDepth.get() + 1;
        if (nestingDepth > MAX_SUBGRAPH_NESTING_DEPTH) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Subgraph nesting depth exceeds limit of " + MAX_SUBGRAPH_NESTING_DEPTH + ": " + node.id());
        }
        Integer version = configInteger(node, "version");
        WorkflowDefinitionResolution resolution = workflowDefinitionService.resolveDefinition(definitionId, version);
        WorkflowExecutionPlan nestedPlan = workflowCompiler.compile(resolution.workflowDefinition());
        Map<String, Object> nestedInput = new LinkedHashMap<>(state.input());
        nestedInput.put("parentNodeId", node.id());
        nestedInput.put("parentLastOutput", state.lastOutput());
        nestedInput.put("parentNodeOutputs", state.nodeOutputs());

        subgraphNestingDepth.set(nestingDepth);
        try {
            WorkflowRuntime nestedRuntime = workflowRuntimeProvider.getObject();
            WorkflowRuntime.WorkflowExecutionResult nestedResult = nestedRuntime.run(
                    runId + ":subgraph:" + node.id(), nestedPlan, nestedInput);
            nestedResult.steps().forEach(this::recordInlineSummary);
            Map<String, Object> output = orderedMap();
            output.put("definitionId", resolution.definitionId());
            output.put("version", resolution.version());
            output.put("nestedOutput", nestedResult.output());
            output.put("nestedStepCount", nestedResult.steps().size());
            state.setLastOutput(output);
            return output;
        }
        finally {
            subgraphNestingDepth.set(nestingDepth - 1);
        }
    }

    Object executeDynamic(String runId, WorkflowNode node, WorkflowExecutionState state) {
        String itemsFrom = configString(node, "itemsFrom");
        if (!StringUtils.hasText(itemsFrom)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Dynamic node requires config.itemsFrom: " + node.id());
        }
        Object resolved = variableResolver.renderValue(itemsFrom, state);
        List<?> items = toItemList(resolved, node.id());
        String action = configString(node, "action");
        if (!StringUtils.hasText(action)) {
            action = "tool";
        }
        if (!"tool".equalsIgnoreCase(action)) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Dynamic node only supports action=tool in demo scope: " + node.id());
        }

        List<Object> outputs = new ArrayList<>();
        WorkflowNodeTraceExecutor traceExecutor = traceExecutor();
        for (int index = 0; index < items.size(); index++) {
            WorkflowNode syntheticNode = syntheticToolNode(node.id(), index, items.get(index));
            recordInlineSummary(traceExecutor.execute(runId, syntheticNode, state).summary());
            outputs.add(state.lastOutput());
        }

        Map<String, Object> output = orderedMap();
        output.put("itemCount", items.size());
        output.put("outputs", List.copyOf(outputs));
        state.setLastOutput(output);
        return output;
    }

    private WorkflowNode syntheticToolNode(String dynamicNodeId, int index, Object item) {
        String toolName = item instanceof Map<?, ?> map && map.get("toolName") != null
                ? String.valueOf(map.get("toolName"))
                : String.valueOf(item);
        Map<String, Object> arguments = item instanceof Map<?, ?> map
                ? copyStringKeyMap(map)
                : Map.of();
        return new WorkflowNode(dynamicNodeId + ":dynamic:" + index + ":" + toolName, "tool",
                Map.of("toolName", toolName, "arguments", arguments));
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = orderedMap();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key && !"toolName".equals(key)) {
                copy.put(key, entry.getValue());
            }
        }
        return copy;
    }

    private List<?> toItemList(Object resolved, String nodeId) {
        if (resolved instanceof List<?> list) {
            return list;
        }
        throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                "Dynamic node itemsFrom must resolve to a list: " + nodeId);
    }

    private void recordInlineSummary(WorkflowStepSummary summary) {
        inlineStepSummaries.get().add(summary);
    }

    private boolean evaluateLoopCondition(WorkflowNode node, WorkflowExecutionState state) {
        String left = variableResolver.renderString(configString(node, "left", ""), state);
        String operator = configString(node, "operator", "exists").toLowerCase(Locale.ROOT);
        Object right = variableResolver.renderValue(node.config().getOrDefault("right", ""), state);
        boolean caseSensitive = Boolean.TRUE.equals(node.config().get("caseSensitive"));
        return nodeExecutorProvider.getObject().evaluateCondition(left, operator, right, caseSensitive);
    }

    private WorkflowNodeTraceExecutor traceExecutor() {
        return new WorkflowNodeTraceExecutor(nodeExecutorProvider.getObject(), traceService, workflowNodeExecutorService);
    }

    private WorkflowExecutionPlan requirePlan() {
        Deque<WorkflowExecutionPlan> plans = activePlans.get();
        if (plans.isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Inline workflow execution requires an active execution plan");
        }
        return plans.peek();
    }

    private String configString(WorkflowNode node, String key) {
        return configString(node, key, "");
    }

    private String configString(WorkflowNode node, String key, String defaultValue) {
        Object value = node.config().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Integer configInteger(WorkflowNode node, String key) {
        Object value = node.config().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

}
