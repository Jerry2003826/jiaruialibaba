package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/run")
    public ApiResponse<WorkflowRunResponse> run(@Valid @RequestBody WorkflowRunRequest request) {
        return ApiResponse.ok(workflowService.run(request));
    }

}
