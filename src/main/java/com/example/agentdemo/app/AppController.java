package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.app.dto.AppPageResponse;
import com.example.agentdemo.app.dto.AppResponse;
import com.example.agentdemo.app.dto.AppRevisionResponse;
import com.example.agentdemo.app.dto.AppRunRequest;
import com.example.agentdemo.app.dto.AppRunResultResponse;
import com.example.agentdemo.app.dto.CreateAppRequest;
import com.example.agentdemo.app.dto.UpdateAppRequest;
import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Console + runtime API for apps. Management endpoints require {@code app.read}/{@code app.write};
 * runtime endpoints ({@code /run}, {@code /chat}, {@code /chat/stream}) require {@code app.run} and
 * are the only endpoints reachable with a runtime app API key.
 */
@RestController
@RequestMapping("/api/apps")
public class AppController {

    private final AppService appService;
    private final AppRuntimeService appRuntimeService;

    public AppController(AppService appService, AppRuntimeService appRuntimeService) {
        this.appService = appService;
        this.appRuntimeService = appRuntimeService;
    }

    @PostMapping
    public ApiResponse<AppResponse> create(@Valid @RequestBody CreateAppRequest request) {
        return ApiResponse.ok(appService.create(request));
    }

    @GetMapping
    public ApiResponse<AppPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(appService.list(page, size));
    }

    @GetMapping("/{appId}")
    public ApiResponse<AppResponse> get(@PathVariable String appId) {
        return ApiResponse.ok(appService.get(appId));
    }

    @GetMapping("/{appId}/revisions")
    public ApiResponse<List<AppRevisionResponse>> revisions(@PathVariable String appId) {
        List<AppRevisionResponse> revisions = appService.revisions(appId).stream()
                .map(revision -> new AppRevisionResponse(revision.getAppId(), revision.getVersion(),
                        revision.getStatus(), revision.getCreatedAt()))
                .toList();
        return ApiResponse.ok(revisions);
    }

    @PutMapping("/{appId}")
    public ApiResponse<AppResponse> update(@PathVariable String appId,
            @Valid @RequestBody UpdateAppRequest request) {
        return ApiResponse.ok(appService.update(appId, request));
    }

    @PostMapping("/{appId}/publish")
    public ApiResponse<AppResponse> publish(@PathVariable String appId) {
        return ApiResponse.ok(appService.publish(appId));
    }

    @PostMapping("/{appId}/rollback/{version}")
    public ApiResponse<AppResponse> rollback(@PathVariable String appId, @PathVariable Integer version) {
        return ApiResponse.ok(appService.rollback(appId, version));
    }

    @DeleteMapping("/{appId}")
    public ApiResponse<Void> delete(@PathVariable String appId) {
        appService.delete(appId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{appId}/run")
    public ApiResponse<AppRunResultResponse> run(@PathVariable String appId,
            @Valid @RequestBody(required = false) AppRunRequest request) {
        return ApiResponse.ok(appRuntimeService.run(appId, request));
    }

    @PostMapping("/{appId}/chat")
    public ApiResponse<AppChatResponse> chat(@PathVariable String appId,
            @Valid @RequestBody AppChatRequest request) {
        return ApiResponse.ok(appRuntimeService.chat(appId, request));
    }

    @PostMapping(value = "/{appId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String appId, @Valid @RequestBody AppChatRequest request) {
        return appRuntimeService.stream(appId, request);
    }

}
