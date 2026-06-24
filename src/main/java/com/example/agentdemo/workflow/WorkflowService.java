package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

}
