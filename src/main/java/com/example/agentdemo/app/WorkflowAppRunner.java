package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppRunRequest;
import com.example.agentdemo.app.dto.AppRunResultResponse;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.workflow.WorkflowRunRequest;
import com.example.agentdemo.workflow.WorkflowRunResponse;
import com.example.agentdemo.workflow.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

@Service
public class WorkflowAppRunner {

    private final WorkflowService workflowService;

    public WorkflowAppRunner(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public AppRunResultResponse run(String appId, AppSnapshot snapshot, AppRunRequest request) {
        if (!StringUtils.hasText(snapshot.workflowDefinitionId())) {
            throw new BusinessException("APP_WORKFLOW_BINDING_REQUIRED", "App has no bound workflow");
        }
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        RunContext.setAppId(appId);
        try {
            WorkflowRunResponse response = workflowService.run(new WorkflowRunRequest(null,
                    snapshot.workflowDefinitionId(), snapshot.workflowDefinitionVersion(), input));
            return new AppRunResultResponse(response.output(), response.runId(), appId, response.definitionId(),
                    response.definitionVersion());
        }
        finally {
            RunContext.clear();
        }
    }

}
