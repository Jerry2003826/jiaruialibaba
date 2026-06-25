package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowRunGraphService {

    private final WorkflowCompiler workflowCompiler;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowRunRecordRepository workflowRunRecordRepository;
    private final TraceService traceService;
    private final WorkflowGraphRenderer workflowGraphRenderer;
    private final ObjectMapper objectMapper;

    public WorkflowRunGraphService(WorkflowCompiler workflowCompiler, WorkflowDefinitionService workflowDefinitionService,
            WorkflowRunRecordRepository workflowRunRecordRepository, TraceService traceService,
            WorkflowGraphRenderer workflowGraphRenderer, ObjectMapper objectMapper) {
        this.workflowCompiler = workflowCompiler;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowRunRecordRepository = workflowRunRecordRepository;
        this.traceService = traceService;
        this.workflowGraphRenderer = workflowGraphRenderer;
        this.objectMapper = objectMapper;
    }

    public WorkflowRunGraphResponse getRunGraph(String runId) {
        RunResponse run = getWorkflowRun(runId);
        WorkflowDefinitionResolution resolution = resolveDefinition(runId, run);
        WorkflowDefinition definition = resolution.workflowDefinition();
        WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
        List<RunStepResponse> steps = traceService.listSteps(runId);
        Map<String, String> stepTypesByNodeId = buildStepTypesByNodeId(definition);
        List<WorkflowRunGraphNodeView> nodes = workflowGraphRenderer.runNodes(definition, executionPlan, steps,
                stepTypesByNodeId);
        List<WorkflowRunGraphEdgeView> edges = workflowGraphRenderer.runEdges(definition, executionPlan, steps, nodes);
        return new WorkflowRunGraphResponse(runId, resolution.definitionId(), resolution.version(), run.status(),
                WorkflowValidationSummaryFactory.from(definition, executionPlan), nodes, edges,
                workflowGraphRenderer.runMermaid(definition, executionPlan, nodes, edges));
    }

    private RunResponse getWorkflowRun(String runId) {
        try {
            return traceService.getRun(runId);
        }
        catch (BusinessException ex) {
            if ("RUN_NOT_FOUND".equals(ex.getCode())) {
                throw new BusinessException("WORKFLOW_RUN_NOT_FOUND", "Workflow run not found: " + runId, ex);
            }
            throw ex;
        }
    }

    private WorkflowDefinitionResolution resolveDefinition(String runId, RunResponse run) {
        return workflowRunRecordRepository.findById(runId)
                .map(record -> workflowDefinitionService.resolveDefinition(record.getDefinitionId(),
                        record.getDefinitionVersion()))
                .orElseGet(() -> resolveInlineDefinition(run));
    }

    private WorkflowDefinitionResolution resolveInlineDefinition(RunResponse run) {
        if (run.type() != RunType.WORKFLOW) {
            throw graphUnavailable(run.runId());
        }
        WorkflowRunTraceInput traceInput = readTraceInput(run);
        if (traceInput.request() == null || traceInput.request().workflowDefinition() == null) {
            throw graphUnavailable(run.runId());
        }
        return new WorkflowDefinitionResolution(traceInput.definitionId(), traceInput.definitionVersion(),
                traceInput.request().workflowDefinition());
    }

    private WorkflowRunTraceInput readTraceInput(RunResponse run) {
        if (!StringUtils.hasText(run.input())) {
            throw graphUnavailable(run.runId());
        }
        try {
            return objectMapper.readValue(run.input(), WorkflowRunTraceInput.class);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("WORKFLOW_GRAPH_UNAVAILABLE",
                    "Workflow graph is unavailable for run: " + run.runId(), ex);
        }
    }

    private BusinessException graphUnavailable(String runId) {
        return new BusinessException("WORKFLOW_GRAPH_UNAVAILABLE", "Workflow graph is unavailable for run: " + runId);
    }

    private Map<String, String> buildStepTypesByNodeId(WorkflowDefinition definition) {
        Map<String, String> typesByNodeId = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            typesByNodeId.put(node.id(), normalizeNodeType(node.type()));
        }
        for (WorkflowNode node : definition.nodes()) {
            if (!"subgraph".equalsIgnoreCase(node.type())) {
                continue;
            }
            Object definitionIdValue = node.config().get("definitionId");
            if (definitionIdValue == null || !StringUtils.hasText(String.valueOf(definitionIdValue))) {
                continue;
            }
            Integer version = configInteger(node, "version");
            try {
                WorkflowDefinitionResolution resolution = workflowDefinitionService.resolveDefinition(
                        String.valueOf(definitionIdValue), version);
                for (WorkflowNode nestedNode : resolution.workflowDefinition().nodes()) {
                    typesByNodeId.put(node.id() + "::" + nestedNode.id(), normalizeNodeType(nestedNode.type()));
                }
            }
            catch (BusinessException ignored) {
                // Keep graph available even when a referenced subgraph cannot be resolved for typing.
            }
        }
        return typesByNodeId;
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

    private String normalizeNodeType(String nodeType) {
        return nodeType == null ? "" : nodeType.toLowerCase();
    }

}
