package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.trace.RunStatus;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;
    private final WorkflowGraphPreviewService workflowGraphPreviewService;
    private final WorkflowRunGraphService workflowRunGraphService;
    private final WorkflowGenerationService workflowGenerationService;
    private final WorkflowRunEventService workflowRunEventService;
    private final Executor sseExecutor;
    private final SseConfig.SseProperties sseProperties;

    public WorkflowController(WorkflowService workflowService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry,
            WorkflowGraphPreviewService workflowGraphPreviewService,
            WorkflowRunGraphService workflowRunGraphService,
            WorkflowGenerationService workflowGenerationService) {
        this(workflowService, workflowDefinitionService, workflowNodeSchemaRegistry, workflowGraphPreviewService,
                workflowRunGraphService, workflowGenerationService, null, Runnable::run,
                new SseConfig.SseProperties(120_000L));
    }

    @Autowired
    public WorkflowController(WorkflowService workflowService, WorkflowDefinitionService workflowDefinitionService,
            WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry,
            WorkflowGraphPreviewService workflowGraphPreviewService,
            WorkflowRunGraphService workflowRunGraphService,
            WorkflowGenerationService workflowGenerationService,
            WorkflowRunEventService workflowRunEventService,
            @Qualifier("sseExecutor") Executor sseExecutor,
            SseConfig.SseProperties sseProperties) {
        this.workflowService = workflowService;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
        this.workflowGraphPreviewService = workflowGraphPreviewService;
        this.workflowRunGraphService = workflowRunGraphService;
        this.workflowGenerationService = workflowGenerationService;
        this.workflowRunEventService = workflowRunEventService;
        this.sseExecutor = sseExecutor;
        this.sseProperties = sseProperties;
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

    @PostMapping("/generate")
    public ApiResponse<WorkflowGenerationResponse> generate(
            @Valid @RequestBody WorkflowGenerationRequest request) {
        return ApiResponse.ok(workflowGenerationService.generate(request));
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@Valid @RequestBody WorkflowGenerationRequest request) {
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        emitter.onTimeout(() -> completeWithError(emitter, terminal, "工作流生成超时"));
        emitter.onError(ignored -> terminal.set(true));
        try {
            sseExecutor.execute(() -> generateStreamInBackground(request, emitter, terminal));
        }
        catch (RejectedExecutionException ex) {
            completeWithError(emitter, terminal, "工作流生成队列已满：" + ex.getMessage());
        }
        return emitter;
    }

    private void generateStreamInBackground(WorkflowGenerationRequest request, SseEmitter emitter,
            AtomicBoolean terminal) {
        try {
            WorkflowGenerationResponse response = workflowGenerationService.generateStreaming(request,
                    (eventName, data) -> send(emitter, eventName, data));
            if (terminal.compareAndSet(false, true)) {
                send(emitter, "done", Map.of("response", response));
                emitter.complete();
            }
        }
        catch (RuntimeException ex) {
            completeWithError(emitter, terminal, ex.getMessage());
        }
    }

    private void completeWithError(SseEmitter emitter, AtomicBoolean terminal, String message) {
        if (terminal.compareAndSet(false, true)) {
            try {
                send(emitter, "error", Map.of("error", message == null ? "工作流生成失败" : message));
                emitter.complete();
            }
            catch (RuntimeException ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to send workflow generation SSE event", ex);
        }
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

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<WorkflowRunCancelResponse> cancelRun(@PathVariable String runId) {
        return ApiResponse.ok(workflowService.cancelRun(runId));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runEvents(@PathVariable String runId) {
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        try {
            sseExecutor.execute(() -> streamRunEvents(runId, emitter, terminal));
        }
        catch (RejectedExecutionException ex) {
            completeWithError(emitter, terminal, "run events queue full: " + ex.getMessage());
        }
        return emitter;
    }

    private void streamRunEvents(String runId, SseEmitter emitter, AtomicBoolean terminal) {
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        long deadlineMs = System.currentTimeMillis() + sseProperties.timeoutMs();
        try {
            while (true) {
                WorkflowRunEventsSnapshot snapshot = workflowRunEventService.delta(runId, cursor);
                for (WorkflowRunEvent event : snapshot.events()) {
                    send(emitter, event.event(), event.data());
                }
                if (snapshot.terminal()) {
                    terminal.set(true);
                    emitter.complete();
                    return;
                }
                if (System.currentTimeMillis() > deadlineMs) {
                    completeWithError(emitter, terminal, "run events stream timed out");
                    return;
                }
                Thread.sleep(400L);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            completeWithError(emitter, terminal, "run events stream interrupted");
        }
        catch (RuntimeException ex) {
            completeWithError(emitter, terminal, ex.getMessage());
        }
    }

    @GetMapping("/runs/{runId}/graph")
    public ApiResponse<WorkflowRunGraphResponse> getRunGraph(@PathVariable String runId) {
        return ApiResponse.ok(workflowRunGraphService.getRunGraph(runId));
    }

}
