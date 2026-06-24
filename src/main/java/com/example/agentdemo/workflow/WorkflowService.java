package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowService {

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
            List<WorkflowNode> orderedNodes = workflowCompiler.compile(definition);
            WorkflowRuntime.WorkflowExecutionResult result = workflowRuntime.run(run.getRunId(), orderedNodes,
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

    public List<WorkflowRunRecordResponse> listRuns(String definitionId, Integer definitionVersion) {
        List<WorkflowRunRecordEntity> records = definitionVersion == null
                ? workflowRunRecordRepository.findAllByDefinitionIdOrderByStartedAtDesc(definitionId)
                : workflowRunRecordRepository.findAllByDefinitionIdAndDefinitionVersionOrderByStartedAtDesc(
                        definitionId, definitionVersion);
        Map<String, RunEntity> runsById = findRunsById(records);
        return records.stream().map(entity -> toResponse(entity, runsById.get(entity.getRunId()))).toList();
    }

    private void requireDefinitionReference(WorkflowRunRequest request) {
        if (request.workflowDefinition() == null
                && !org.springframework.util.StringUtils.hasText(request.definitionId())) {
            throw new com.example.agentdemo.common.BusinessException("WORKFLOW_DEFINITION_REQUIRED",
                    "workflowDefinition or definitionId is required");
        }
    }

    private WorkflowDefinitionResolution resolveDefinition(WorkflowRunRequest request) {
        if (request.workflowDefinition() != null) {
            return new WorkflowDefinitionResolution(null, null, request.workflowDefinition());
        }
        if (org.springframework.util.StringUtils.hasText(request.definitionId())) {
            return workflowDefinitionService.resolveDefinition(request.definitionId(), request.definitionVersion());
        }
        throw new IllegalStateException("Workflow definition reference should have been validated before run creation");
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

}
