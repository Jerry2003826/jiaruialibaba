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

    public WorkflowService(WorkflowCompiler workflowCompiler, WorkflowRuntime workflowRuntime,
            TraceService traceService) {
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntime = workflowRuntime;
        this.traceService = traceService;
    }

    public WorkflowRunResponse run(WorkflowRunRequest request) {
        RunEntity run = traceService.createRun(RunType.WORKFLOW, request);
        try {
            List<WorkflowNode> orderedNodes = workflowCompiler.compile(request.workflowDefinition());
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

}
