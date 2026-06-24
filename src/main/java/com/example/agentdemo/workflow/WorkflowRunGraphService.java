package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
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

    private static final String WORKFLOW_NODE_STEP_PREFIX = "workflow_node_";

    private final WorkflowCompiler workflowCompiler;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowRunRecordRepository workflowRunRecordRepository;
    private final TraceService traceService;
    private final ObjectMapper objectMapper;

    public WorkflowRunGraphService(WorkflowCompiler workflowCompiler, WorkflowDefinitionService workflowDefinitionService,
            WorkflowRunRecordRepository workflowRunRecordRepository, TraceService traceService,
            ObjectMapper objectMapper) {
        this.workflowCompiler = workflowCompiler;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowRunRecordRepository = workflowRunRecordRepository;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public WorkflowRunGraphResponse getRunGraph(String runId) {
        RunResponse run = getWorkflowRun(runId);
        WorkflowDefinitionResolution resolution = resolveDefinition(runId, run);
        WorkflowDefinition definition = resolution.workflowDefinition();
        WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
        Map<String, RunStepResponse> stepsByNodeName = latestStepsByNodeName(traceService.listSteps(runId));
        List<WorkflowRunGraphNodeView> nodes = runGraphNodes(definition, stepsByNodeName);
        List<WorkflowRunGraphEdgeView> edges = runGraphEdges(definition, stepsByNodeName);
        return new WorkflowRunGraphResponse(runId, resolution.definitionId(), resolution.version(), run.status(),
                WorkflowValidationSummaryFactory.from(definition, executionPlan), nodes, edges,
                runGraphMermaid(nodes, edges));
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

    private Map<String, RunStepResponse> latestStepsByNodeName(List<RunStepResponse> steps) {
        Map<String, RunStepResponse> stepsByNodeName = new LinkedHashMap<>();
        for (RunStepResponse step : steps) {
            stepsByNodeName.put(normalizeStepNodeName(step.nodeName()), step);
        }
        return stepsByNodeName;
    }

    private String normalizeStepNodeName(String nodeName) {
        if (nodeName != null && nodeName.startsWith(WORKFLOW_NODE_STEP_PREFIX)) {
            return nodeName.substring(WORKFLOW_NODE_STEP_PREFIX.length());
        }
        return nodeName;
    }

    private List<WorkflowRunGraphNodeView> runGraphNodes(WorkflowDefinition definition,
            Map<String, RunStepResponse> stepsByNodeName) {
        return definition.nodes()
                .stream()
                .map(node -> {
                    RunStepResponse step = stepsByNodeName.get(node.id());
                    String statusLabel = step == null ? "NOT_EXECUTED" : step.status().name();
                    return new WorkflowRunGraphNodeView(node.id(), node.type(),
                            node.id() + " (" + node.type() + ") " + statusLabel, step != null,
                            step == null ? null : step.status(), step == null ? null : step.stepId(),
                            step == null ? null : step.errorMessage());
                })
                .toList();
    }

    private List<WorkflowRunGraphEdgeView> runGraphEdges(WorkflowDefinition definition,
            Map<String, RunStepResponse> stepsByNodeName) {
        return definition.edges()
                .stream()
                .map(edge -> {
                    WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
                    String label = StringUtils.hasText(executionEdge.condition()) ? executionEdge.condition() : null;
                    boolean traversed = stepsByNodeName.containsKey(executionEdge.from())
                            && stepsByNodeName.containsKey(executionEdge.to());
                    return new WorkflowRunGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), label, traversed);
                })
                .toList();
    }

    private String runGraphMermaid(List<WorkflowRunGraphNodeView> nodes, List<WorkflowRunGraphEdgeView> edges) {
        Map<String, String> aliasesByNodeId = new LinkedHashMap<>();
        StringBuilder builder = new StringBuilder("flowchart TD");
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowRunGraphNodeView node = nodes.get(i);
            String alias = "n" + i;
            aliasesByNodeId.put(node.id(), alias);
            builder.append('\n')
                    .append("  ")
                    .append(alias)
                    .append("[\"")
                    .append(escapeMermaidLabel(node.label()))
                    .append("\"]");
        }
        for (WorkflowRunGraphEdgeView edge : edges) {
            appendRunGraphEdge(builder, aliasesByNodeId.get(edge.from()), aliasesByNodeId.get(edge.to()), edge);
        }
        builder.append('\n')
                .append("  classDef succeeded fill:#e8f5e9,stroke:#2e7d32,color:#111")
                .append('\n')
                .append("  classDef failed fill:#ffebee,stroke:#c62828,color:#111")
                .append('\n')
                .append("  classDef running fill:#fff8e1,stroke:#f9a825,color:#111")
                .append('\n')
                .append("  classDef notExecuted fill:#f5f5f5,stroke:#9e9e9e,color:#555");
        for (int i = 0; i < nodes.size(); i++) {
            builder.append('\n')
                    .append("  class n")
                    .append(i)
                    .append(' ')
                    .append(nodeClass(nodes.get(i).status()));
        }
        return builder.toString();
    }

    private void appendRunGraphEdge(StringBuilder builder, String fromAlias, String toAlias,
            WorkflowRunGraphEdgeView edge) {
        builder.append('\n')
                .append("  ")
                .append(fromAlias);
        if (edge.traversed()) {
            if (StringUtils.hasText(edge.label())) {
                builder.append(" -- \"")
                        .append(escapeMermaidLabel(edge.label()))
                        .append("\" --> ");
            }
            else {
                builder.append(" --> ");
            }
        }
        else if (StringUtils.hasText(edge.label())) {
            builder.append(" -. \"")
                    .append(escapeMermaidLabel(edge.label()))
                    .append("\" .-> ");
        }
        else {
            builder.append(" -.-> ");
        }
        builder.append(toAlias);
    }

    private String nodeClass(StepStatus status) {
        if (status == null) {
            return "notExecuted";
        }
        return switch (status) {
            case SUCCEEDED -> "succeeded";
            case FAILED -> "failed";
            case RUNNING -> "running";
        };
    }

    private String escapeMermaidLabel(String label) {
        return label.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace("\r", " ")
                .replace("\n", " ");
    }

}
