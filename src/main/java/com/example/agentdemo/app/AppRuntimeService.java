package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.app.dto.AppRunRequest;
import com.example.agentdemo.app.dto.AppRunResultResponse;
import com.example.agentdemo.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AppRuntimeService {

    private final AppRuntimeSnapshotResolver snapshotResolver;
    private final WorkflowAppRunner workflowAppRunner;
    private final ChatAppRunner chatAppRunner;
    private final AgentAppRunner agentAppRunner;
    private final AppStreamRunner appStreamRunner;

    public AppRuntimeService(AppRuntimeSnapshotResolver snapshotResolver, WorkflowAppRunner workflowAppRunner,
            ChatAppRunner chatAppRunner, AgentAppRunner agentAppRunner, AppStreamRunner appStreamRunner) {
        this.snapshotResolver = snapshotResolver;
        this.workflowAppRunner = workflowAppRunner;
        this.chatAppRunner = chatAppRunner;
        this.agentAppRunner = agentAppRunner;
        this.appStreamRunner = appStreamRunner;
    }

    public AppRunResultResponse run(String appId, AppRunRequest request) {
        AppSnapshot snapshot = snapshotResolver.resolve(appId);
        if (snapshot.type() != AppType.WORKFLOW) {
            throw new BusinessException("APP_TYPE_NOT_RUNNABLE",
                    "run is only supported for WORKFLOW apps; use chat for " + snapshot.type() + " apps");
        }
        return workflowAppRunner.run(appId, snapshot, request);
    }

    public AppChatResponse chat(String appId, AppChatRequest request) {
        AppSnapshot snapshot = snapshotResolver.resolve(appId);
        return switch (snapshot.type()) {
            case CHAT -> chatAppRunner.chat(appId, snapshot, request);
            case AGENT -> agentAppRunner.chat(appId, request);
            case WORKFLOW -> throw new BusinessException("APP_TYPE_NOT_CHATTABLE",
                    "chat is not supported for WORKFLOW apps; use run instead");
        };
    }

    public SseEmitter stream(String appId, AppChatRequest request) {
        AppSnapshot snapshot = snapshotResolver.resolve(appId);
        return switch (snapshot.type()) {
            case CHAT -> appStreamRunner.chatStream(appId, snapshot, request);
            case AGENT -> appStreamRunner.singleShot(appId, request);
            case WORKFLOW -> throw new BusinessException("APP_TYPE_NOT_CHATTABLE",
                    "chat/stream is not supported for WORKFLOW apps; use run instead");
        };
    }

}
