package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.TraceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Component
public class WorkflowInlineExecutionService {

    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowCompiler workflowCompiler;
    private final ObjectProvider<WorkflowRuntime> workflowRuntimeProvider;
    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowVariableResolver variableResolver;
    private final TraceService traceService;
    private final ExecutorService workflowNodeExecutorService;
    private final ThreadLocal<WorkflowExecutionPlan> activePlan = new ThreadLocal<>();

    public WorkflowInlineExecutionService(WorkflowDefinitionService workflowDefinitionService,
            WorkflowCompiler workflowCompiler, ObjectProvider<WorkflowRuntime> workflowRuntimeProvider,
            @Lazy WorkflowNodeExecutor nodeExecutor, WorkflowVariableResolver variableResolver,
            TraceService traceService, ExecutorService workflowNodeExecutorService) {
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntimeProvider = workflowRuntimeProvider;
        this.nodeExecutor = nodeExecutor;
        this.variableResolver = variableResolver;
        this.traceService = traceService;
        this.workflowNodeExecutorService = workflowNodeExecutorService;
    }

    void bindPlan(WorkflowExecutionPlan plan) {
        activePlan.set(plan);
    }

    void clearPlan() {
        activePlan.remove();
    }

    Object executeLoop(String runId, WorkflowNode node, WorkflowExecutionState state) {
        WorkflowExecutionPlan plan = requirePlan();
        WorkflowLoopBlock loopBlock = plan.loopBlock(node.id());
        int maxIterations = loopBlock.maxIterations();
        List<Object> iterationOutputs = new ArrayList<>();
        WorkflowNodeTraceExecutor traceExecutor = traceExecutor();

        while (state.loopIteration(node.id()) < maxIterations) {
            if (!evaluateLoopCondition(node, state)) {
                break;
            }
            for (String bodyNodeId : loopBlock.bodyNodeIds()) {
                WorkflowNode bodyNode = plan.node(bodyNodeId);
                traceExecutor.execute(runId, bodyNode, state);
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
        Integer version = configInteger(node, "version");
        WorkflowDefinitionResolution resolution = workflowDefinitionService.resolveDefinition(definitionId, version);
        WorkflowExecutionPlan nestedPlan = workflowCompiler.compile(resolution.workflowDefinition());
        Map<String, Object> nestedInput = new LinkedHashMap<>(state.input());
        nestedInput.put("parentNodeId", node.id());
        nestedInput.put("parentLastOutput", state.lastOutput());
        nestedInput.put("parentNodeOutputs", state.nodeOutputs());

        WorkflowRuntime nestedRuntime = workflowRuntimeProvider.getObject();
        WorkflowRuntime.WorkflowExecutionResult nestedResult = nestedRuntime.run(
                runId + ":subgraph:" + node.id(), nestedPlan, nestedInput);
        Map<String, Object> output = orderedMap();
        output.put("definitionId", resolution.definitionId());
        output.put("version", resolution.version());
        output.put("nestedOutput", nestedResult.output());
        output.put("nestedStepCount", nestedResult.steps().size());
        state.setLastOutput(output);
        return output;
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
        for (Object item : items) {
            WorkflowNode syntheticNode = syntheticToolNode(node.id(), item);
            traceExecutor.execute(runId, syntheticNode, state);
            outputs.add(state.lastOutput());
        }

        Map<String, Object> output = orderedMap();
        output.put("itemCount", items.size());
        output.put("outputs", List.copyOf(outputs));
        state.setLastOutput(output);
        return output;
    }

    private WorkflowNode syntheticToolNode(String dynamicNodeId, Object item) {
        String toolName = item instanceof Map<?, ?> map && map.get("toolName") != null
                ? String.valueOf(map.get("toolName"))
                : String.valueOf(item);
        Map<String, Object> arguments = item instanceof Map<?, ?> map
                ? copyStringKeyMap(map)
                : Map.of();
        return new WorkflowNode(dynamicNodeId + ":dynamic:" + toolName, "tool",
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

    private boolean evaluateLoopCondition(WorkflowNode node, WorkflowExecutionState state) {
        String left = variableResolver.renderString(configString(node, "left", ""), state);
        String operator = configString(node, "operator", "exists").toLowerCase(Locale.ROOT);
        Object right = variableResolver.renderValue(node.config().getOrDefault("right", ""), state);
        boolean caseSensitive = Boolean.TRUE.equals(node.config().get("caseSensitive"));
        return nodeExecutor.evaluateCondition(left, operator, right, caseSensitive);
    }

    private WorkflowNodeTraceExecutor traceExecutor() {
        return new WorkflowNodeTraceExecutor(nodeExecutor, traceService, workflowNodeExecutorService);
    }

    private WorkflowExecutionPlan requirePlan() {
        WorkflowExecutionPlan plan = activePlan.get();
        if (plan == null) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Inline workflow execution requires an active execution plan");
        }
        return plan;
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
