package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;

    public WorkflowController(WorkflowService workflowService, WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry) {
        this.workflowService = workflowService;
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
    }

    @GetMapping("/node-schemas")
    public ApiResponse<List<WorkflowNodeSchema>> listNodeSchemas() {
        return ApiResponse.ok(workflowNodeSchemaRegistry.listSchemas());
    }

    @PostMapping("/run")
    public ApiResponse<WorkflowRunResponse> run(@Valid @RequestBody WorkflowRunRequest request) {
        return ApiResponse.ok(workflowService.run(request));
    }

}
