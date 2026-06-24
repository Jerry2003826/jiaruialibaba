package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowService {

    private final WorkflowCompiler workflowCompiler;
    private final WorkflowRuntime workflowRuntime;
    private final TraceService traceService;
    private final WorkflowDefinitionService workflowDefinitionService;

    public WorkflowService(WorkflowCompiler workflowCompiler, WorkflowRuntime workflowRuntime,
            TraceService traceService, WorkflowDefinitionService workflowDefinitionService) {
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntime = workflowRuntime;
        this.traceService = traceService;
        this.workflowDefinitionService = workflowDefinitionService;
    }

    public WorkflowRunResponse run(WorkflowRunRequest request) {
        requireDefinitionReference(request);
        RunEntity run = traceService.createRun(RunType.WORKFLOW, request);
        try {
            WorkflowDefinition definition = resolveDefinition(request);
            List<WorkflowNode> orderedNodes = workflowCompiler.compile(definition);
            WorkflowRuntime.WorkflowExecutionResult result = workflowRuntime.run(run.getRunId(), orderedNodes,
                    request.input());
            WorkflowRunResponse response = new WorkflowRunResponse(result.output(), run.getRunId(), result.steps());
            traceService.markRunSucceeded(run.getRunId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.markRunFailed(run.getRunId(), ex);
            throw ex;
        }
    }

    private void requireDefinitionReference(WorkflowRunRequest request) {
        if (request.workflowDefinition() == null
                && !org.springframework.util.StringUtils.hasText(request.definitionId())) {
            throw new com.example.agentdemo.common.BusinessException("WORKFLOW_DEFINITION_REQUIRED",
                    "workflowDefinition or definitionId is required");
        }
    }

    private WorkflowDefinition resolveDefinition(WorkflowRunRequest request) {
        if (request.workflowDefinition() != null) {
            return request.workflowDefinition();
        }
        if (org.springframework.util.StringUtils.hasText(request.definitionId())) {
            return workflowDefinitionService.resolveDefinition(request.definitionId());
        }
        throw new IllegalStateException("Workflow definition reference should have been validated before run creation");
    }

}
