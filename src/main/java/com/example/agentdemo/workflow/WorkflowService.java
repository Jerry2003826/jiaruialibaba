package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.PageRequestValidator;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowService {

    private final WorkflowCompiler workflowCompiler;
    private final WorkflowRuntime workflowRuntime;
    private final TraceService traceService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowRunRecordRepository workflowRunRecordRepository;
    private final WorkflowRunBudgetRegistry workflowRunBudgetRegistry;
    private final WorkflowRuntimeProperties workflowRuntimeProperties;
    private final PageRequestValidator pageRequestValidator;

    @Autowired
    public WorkflowService(WorkflowCompiler workflowCompiler, WorkflowRuntime workflowRuntime,
            TraceService traceService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRunBudgetRegistry workflowRunBudgetRegistry,
            WorkflowRuntimeProperties workflowRuntimeProperties, PageRequestValidator pageRequestValidator) {
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntime = workflowRuntime;
        this.traceService = traceService;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowRunRecordRepository = workflowRunRecordRepository;
        this.workflowRunBudgetRegistry = workflowRunBudgetRegistry;
        this.workflowRuntimeProperties = workflowRuntimeProperties;
        this.pageRequestValidator = pageRequestValidator;
    }

    public WorkflowService(WorkflowCompiler workflowCompiler, WorkflowRuntime workflowRuntime,
            TraceService traceService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowRunRecordRepository workflowRunRecordRepository) {
        this(workflowCompiler, workflowRuntime, traceService, workflowDefinitionService, workflowRunRecordRepository,
                new WorkflowRunBudgetRegistry(), new WorkflowRuntimeProperties(), new PageRequestValidator());
    }

    public WorkflowService(WorkflowCompiler workflowCompiler, WorkflowRuntime workflowRuntime,
            TraceService traceService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRunBudgetRegistry workflowRunBudgetRegistry,
            WorkflowRuntimeProperties workflowRuntimeProperties) {
        this(workflowCompiler, workflowRuntime, traceService, workflowDefinitionService, workflowRunRecordRepository,
                workflowRunBudgetRegistry, workflowRuntimeProperties, new PageRequestValidator());
    }

    public WorkflowRunResponse run(WorkflowRunRequest request) {
        requireDefinitionReference(request);
        WorkflowDefinitionResolution definitionResolution = resolveDefinition(request);
        TraceRun run = traceService.startRun(RunType.WORKFLOW,
                new WorkflowRunTraceInput(request, definitionResolution.definitionId(), definitionResolution.version()));
        workflowRunBudgetRegistry.open(run.runId(), workflowRuntimeProperties.getMaxStepExecutions(),
                workflowRuntimeProperties.getRunDeadlineMs());
        try {
            recordRunMetadata(run, definitionResolution);
            WorkflowDefinition definition = definitionResolution.workflowDefinition();
            WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
            WorkflowRuntime.WorkflowExecutionResult result = workflowRuntime.run(run.runId(), executionPlan,
                    request.input());
            WorkflowRunResponse response = new WorkflowRunResponse(result.output(), run.runId(), result.steps(),
                    definitionResolution.definitionId(), definitionResolution.version());
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (WorkflowCanceledException ex) {
            traceService.markRunCanceled(run.runId());
            throw ex;
        }
        catch (RuntimeException ex) {
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
        finally {
            workflowRunBudgetRegistry.close(run.runId());
        }
    }

    /**
     * Requests best-effort cancellation of a run. A still-{@code RUNNING} run is flagged so the
     * runtime aborts at its next node boundary and marks the run {@code CANCELED}; a run that has
     * already reached a terminal state is returned unchanged (idempotent).
     */
    public WorkflowRunCancelResponse cancelRun(String runId) {
        RunResponse run = traceService.getRun(runId);
        if (run.status() != RunStatus.RUNNING) {
            return new WorkflowRunCancelResponse(runId, run.status(), false);
        }
        boolean requested = workflowRunBudgetRegistry.cancel(runId);
        return new WorkflowRunCancelResponse(runId, RunStatus.RUNNING, requested);
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
        PageRequest pageable = pageRequestValidator.build(page, size, "WORKFLOW_RUN_QUERY_INVALID",
                Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<WorkflowRunRecordEntity> recordPage =
                workflowRunRecordRepository.searchRuns(definitionId, SecurityIdentity.currentOwnerId(),
                        definitionVersion, status, pageable);
        List<WorkflowRunRecordEntity> records = recordPage.getContent();
        Map<String, RunResponse> runsById = findRunsById(records);
        List<WorkflowRunRecordResponse> content =
                records.stream().map(entity -> toResponse(entity, runsById.get(entity.getRunId()))).toList();
        return new WorkflowRunPageResponse(content, recordPage.getNumber(), recordPage.getSize(),
                recordPage.getTotalElements(), recordPage.getTotalPages());
    }

    public WorkflowRunDetailResponse getRunDetail(String runId) {
        WorkflowRunRecordEntity record = workflowRunRecordRepository.findByRunIdAndOwnerId(runId,
                        SecurityIdentity.currentOwnerId())
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
            if (workflowRuntimeProperties.isRequirePublishedForRun()
                    && !workflowRuntimeProperties.isAllowInlineRun()) {
                throw new BusinessException("WORKFLOW_INLINE_RUN_DISABLED",
                        "Inline workflow runs are disabled when published definitions are required");
            }
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
        pageRequestValidator.build(page, size, "WORKFLOW_RUN_QUERY_INVALID", Sort.unsorted());
    }

    private void recordRunMetadata(TraceRun run, WorkflowDefinitionResolution definitionResolution) {
        if (definitionResolution.definitionId() == null || definitionResolution.version() == null) {
            return;
        }
        workflowRunRecordRepository.save(new WorkflowRunRecordEntity(run.runId(),
                definitionResolution.definitionId(), definitionResolution.version(), run.startedAt()));
    }

    private Map<String, RunResponse> findRunsById(List<WorkflowRunRecordEntity> records) {
        List<String> runIds = records.stream().map(WorkflowRunRecordEntity::getRunId).toList();
        return traceService.findRunsById(runIds);
    }

    private WorkflowRunRecordResponse toResponse(WorkflowRunRecordEntity entity, RunResponse run) {
        if (run == null) {
            return new WorkflowRunRecordResponse(entity.getRunId(), entity.getDefinitionId(),
                    entity.getDefinitionVersion(), entity.getStartedAt(), null, null, null, null);
        }
        return new WorkflowRunRecordResponse(entity.getRunId(), entity.getDefinitionId(),
                entity.getDefinitionVersion(), entity.getStartedAt(), run.status(), run.output(), run.errorMessage(),
                run.endedAt());
    }

}
