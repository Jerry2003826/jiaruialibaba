package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowService {

    private static final String WORKFLOW_NODE_STEP_PREFIX = "workflow_node_";

    private final WorkflowCompiler workflowCompiler;
    private final WorkflowRuntime workflowRuntime;
    private final TraceService traceService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowRunRecordRepository workflowRunRecordRepository;
    private final RunRepository runRepository;

    public WorkflowService(WorkflowCompiler workflowCompiler, WorkflowRuntime workflowRuntime,
            TraceService traceService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowRunRecordRepository workflowRunRecordRepository, RunRepository runRepository) {
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntime = workflowRuntime;
        this.traceService = traceService;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowRunRecordRepository = workflowRunRecordRepository;
        this.runRepository = runRepository;
    }

    public WorkflowRunResponse run(WorkflowRunRequest request) {
        requireDefinitionReference(request);
        WorkflowDefinitionResolution definitionResolution = resolveDefinition(request);
        RunEntity run = traceService.createRun(RunType.WORKFLOW,
                new WorkflowRunTraceInput(request, definitionResolution.definitionId(), definitionResolution.version()));
        try {
            recordRunMetadata(run, definitionResolution);
            WorkflowDefinition definition = definitionResolution.workflowDefinition();
            WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
            WorkflowRuntime.WorkflowExecutionResult result = workflowRuntime.run(run.getRunId(), executionPlan,
                    request.input());
            WorkflowRunResponse response = new WorkflowRunResponse(result.output(), run.getRunId(), result.steps(),
                    definitionResolution.definitionId(), definitionResolution.version());
            traceService.markRunSucceeded(run.getRunId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.markRunFailed(run.getRunId(), ex);
            throw ex;
        }
    }

    public WorkflowValidationResponse validate(WorkflowValidationRequest request) {
        try {
            WorkflowExecutionPlan executionPlan = workflowCompiler.compile(request.workflowDefinition());
            return WorkflowValidationResponse.valid(
                    WorkflowValidationSummaryFactory.from(request.workflowDefinition(), executionPlan));
        }
        catch (BusinessException ex) {
            return WorkflowValidationResponse.invalid(ex.getCode(), ex.getMessage());
        }
    }

    public List<WorkflowRunRecordResponse> listRuns(String definitionId, Integer definitionVersion) {
        return listRuns(definitionId, definitionVersion, null, 0, 20).content();
    }

    public WorkflowRunPageResponse listRuns(String definitionId, Integer definitionVersion, RunStatus status,
            int page, int size) {
        validateRunQuery(definitionId, page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<WorkflowRunRecordEntity> recordPage =
                workflowRunRecordRepository.searchRuns(definitionId, definitionVersion, status, pageable);
        List<WorkflowRunRecordEntity> records = recordPage.getContent();
        Map<String, RunEntity> runsById = findRunsById(records);
        List<WorkflowRunRecordResponse> content =
                records.stream().map(entity -> toResponse(entity, runsById.get(entity.getRunId()))).toList();
        return new WorkflowRunPageResponse(content, recordPage.getNumber(), recordPage.getSize(),
                recordPage.getTotalElements(), recordPage.getTotalPages());
    }

    public WorkflowRunDetailResponse getRunDetail(String runId) {
        WorkflowRunRecordEntity record = workflowRunRecordRepository.findById(runId)
                .orElseThrow(() -> new BusinessException("WORKFLOW_RUN_NOT_FOUND", "Workflow run not found: " + runId));
        RunResponse run = traceService.getRun(runId);
        List<RunStepResponse> steps = traceService.listSteps(runId);
        return new WorkflowRunDetailResponse(toResponse(record, run), run, steps);
    }

    public WorkflowRunGraphResponse getRunGraph(String runId) {
        WorkflowRunRecordEntity record = workflowRunRecordRepository.findById(runId)
                .orElseThrow(() -> new BusinessException("WORKFLOW_RUN_NOT_FOUND", "Workflow run not found: " + runId));
        WorkflowDefinitionResolution resolution = workflowDefinitionService.resolveDefinition(record.getDefinitionId(),
                record.getDefinitionVersion());
        WorkflowDefinition definition = resolution.workflowDefinition();
        WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
        RunResponse run = traceService.getRun(runId);
        Map<String, RunStepResponse> stepsByNodeName = latestStepsByNodeName(traceService.listSteps(runId));
        List<WorkflowRunGraphNodeView> nodes = runGraphNodes(definition, stepsByNodeName);
        List<WorkflowRunGraphEdgeView> edges = runGraphEdges(definition, stepsByNodeName);
        return new WorkflowRunGraphResponse(runId, record.getDefinitionId(), record.getDefinitionVersion(),
                run.status(), WorkflowValidationSummaryFactory.from(definition, executionPlan), nodes, edges,
                runGraphMermaid(nodes, edges));
    }

    private void requireDefinitionReference(WorkflowRunRequest request) {
        if (request.workflowDefinition() == null && !StringUtils.hasText(request.definitionId())) {
            throw new BusinessException("WORKFLOW_DEFINITION_REQUIRED", "workflowDefinition or definitionId is required");
        }
    }

    private WorkflowDefinitionResolution resolveDefinition(WorkflowRunRequest request) {
        if (request.workflowDefinition() != null) {
            return new WorkflowDefinitionResolution(null, null, request.workflowDefinition());
        }
        if (StringUtils.hasText(request.definitionId())) {
            return workflowDefinitionService.resolveDefinition(request.definitionId(), request.definitionVersion());
        }
        throw new IllegalStateException("Workflow definition reference should have been validated before run creation");
    }

    private void validateRunQuery(String definitionId, int page, int size) {
        if (!StringUtils.hasText(definitionId)) {
            throw new BusinessException("WORKFLOW_RUN_QUERY_INVALID", "definitionId is required");
        }
        if (page < 0) {
            throw new BusinessException("WORKFLOW_RUN_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("WORKFLOW_RUN_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

    private void recordRunMetadata(RunEntity run, WorkflowDefinitionResolution definitionResolution) {
        if (definitionResolution.definitionId() == null || definitionResolution.version() == null) {
            return;
        }
        workflowRunRecordRepository.save(new WorkflowRunRecordEntity(run.getRunId(),
                definitionResolution.definitionId(), definitionResolution.version(), run.getStartedAt()));
    }

    private Map<String, RunEntity> findRunsById(List<WorkflowRunRecordEntity> records) {
        if (records.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> runIds = records.stream().map(WorkflowRunRecordEntity::getRunId).toList();
        return runRepository.findAllByRunIdIn(runIds)
                .stream()
                .collect(Collectors.toMap(RunEntity::getRunId, Function.identity()));
    }

    private WorkflowRunRecordResponse toResponse(WorkflowRunRecordEntity entity, RunEntity run) {
        if (run == null) {
            return new WorkflowRunRecordResponse(entity.getRunId(), entity.getDefinitionId(),
                    entity.getDefinitionVersion(), entity.getStartedAt(), null, null, null, null);
        }
        return new WorkflowRunRecordResponse(entity.getRunId(), entity.getDefinitionId(),
                entity.getDefinitionVersion(), entity.getStartedAt(), run.getStatus(), run.getOutput(),
                run.getErrorMessage(), run.getEndedAt());
    }

    private WorkflowRunRecordResponse toResponse(WorkflowRunRecordEntity entity, RunResponse run) {
        return new WorkflowRunRecordResponse(entity.getRunId(), entity.getDefinitionId(),
                entity.getDefinitionVersion(), entity.getStartedAt(), run.status(), run.output(), run.errorMessage(),
                run.endedAt());
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
