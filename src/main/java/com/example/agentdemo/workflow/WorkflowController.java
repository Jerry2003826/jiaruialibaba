package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.trace.RunStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;
    private final WorkflowGraphPreviewService workflowGraphPreviewService;

    public WorkflowController(WorkflowService workflowService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry,
            WorkflowGraphPreviewService workflowGraphPreviewService) {
        this.workflowService = workflowService;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
        this.workflowGraphPreviewService = workflowGraphPreviewService;
    }

    @PostMapping("/definitions")
    public ApiResponse<WorkflowDefinitionResponse> saveDefinition(
            @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
        return ApiResponse.ok(workflowDefinitionService.save(request));
    }

    @GetMapping("/definitions")
    public ApiResponse<List<WorkflowDefinitionResponse>> listDefinitions() {
        return ApiResponse.ok(workflowDefinitionService.list());
    }

    @GetMapping("/definitions/{definitionId}")
    public ApiResponse<WorkflowDefinitionResponse> getDefinition(@PathVariable String definitionId) {
        return ApiResponse.ok(workflowDefinitionService.get(definitionId));
    }

    @GetMapping("/definitions/{definitionId}/revisions")
    public ApiResponse<List<WorkflowDefinitionRevisionResponse>> listDefinitionRevisions(
            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowDefinitionService.listRevisions(definitionId));
    }

    @PutMapping("/definitions/{definitionId}")
    public ApiResponse<WorkflowDefinitionResponse> updateDefinition(@PathVariable String definitionId,
            @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
        return ApiResponse.ok(workflowDefinitionService.update(definitionId, request));
    }

    @PostMapping("/definitions/{definitionId}/publish")
    public ApiResponse<WorkflowDefinitionResponse> publishDefinition(@PathVariable String definitionId) {
        return ApiResponse.ok(workflowDefinitionService.publish(definitionId));
    }

    @PostMapping("/definitions/{definitionId}/rollback/{version}")
    public ApiResponse<WorkflowDefinitionResponse> rollbackDefinition(@PathVariable String definitionId,
            @PathVariable Integer version) {
        return ApiResponse.ok(workflowDefinitionService.rollback(definitionId, version));
    }

    @DeleteMapping("/definitions/{definitionId}")
    public ApiResponse<Void> deleteDefinition(@PathVariable String definitionId) {
        workflowDefinitionService.delete(definitionId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/node-schemas")
    public ApiResponse<List<WorkflowNodeSchema>> listNodeSchemas() {
        return ApiResponse.ok(workflowNodeSchemaRegistry.listSchemas());
    }

    @PostMapping("/validate")
    public ApiResponse<WorkflowValidationResponse> validate(
            @Valid @RequestBody WorkflowValidationRequest request) {
        return ApiResponse.ok(workflowService.validate(request));
    }

    @PostMapping("/preview-graph")
    public ApiResponse<WorkflowGraphPreviewResponse> previewGraph(
            @Valid @RequestBody WorkflowGraphPreviewRequest request) {
        return ApiResponse.ok(workflowGraphPreviewService.preview(request));
    }

    @PostMapping("/run")
    public ApiResponse<WorkflowRunResponse> run(@Valid @RequestBody WorkflowRunRequest request) {
        return ApiResponse.ok(workflowService.run(request));
    }

    @GetMapping("/runs")
    public ApiResponse<WorkflowRunPageResponse> listRuns(@RequestParam String definitionId,
            @RequestParam(required = false) Integer definitionVersion,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(workflowService.listRuns(definitionId, definitionVersion, status, page, size));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<WorkflowRunDetailResponse> getRunDetail(@PathVariable String runId) {
        return ApiResponse.ok(workflowService.getRunDetail(runId));
    }

}
