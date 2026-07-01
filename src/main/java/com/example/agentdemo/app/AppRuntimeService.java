package com.example.agentdemo.app;

import com.example.agentdemo.agent.ToolCallingAgentService;
import com.example.agentdemo.agent.dto.AssistantChatResponse;
import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.app.dto.AppRunRequest;
import com.example.agentdemo.app.dto.AppRunResultResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.dto.StreamChunk;
import com.example.agentdemo.chat.dto.StreamDone;
import com.example.agentdemo.chat.dto.StreamError;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.security.SecurityIdentity;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.usage.UsageRecordingService;
import com.example.agentdemo.workflow.WorkflowRunRequest;
import com.example.agentdemo.workflow.WorkflowRunResponse;
import com.example.agentdemo.workflow.WorkflowService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes published apps against the runtime API. A published app always runs from its immutable
 * revision snapshot (reproducibility); when {@code demo.app.require-published-for-run=false} the
 * current draft may be run for local iteration. WORKFLOW apps delegate to {@link WorkflowService},
 * AGENT apps to {@link ToolCallingAgentService}, and CHAT apps generate directly with the app's
 * configured system prompt, model and memory. Every run is tagged with its {@code appId} via
 * {@link RunContext}.
 */
@Service
public class AppRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AppRuntimeService.class);

    private static final String DEFAULT_CHAT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer clearly and concisely and do not invent external state.
            """;

    private final AppRepository appRepository;
    private final AppRevisionRepository appRevisionRepository;
    private final AppProperties appProperties;
    private final WorkflowService workflowService;
    private final ToolCallingAgentService toolCallingAgentService;
    private final AiModelService aiModelService;
    private final ConversationMemoryService conversationMemoryService;
    private final TraceService traceService;
    private final UsageRecordingService usageRecordingService;
    private final Executor sseExecutor;
    private final SseConfig.SseProperties sseProperties;
    private final ObjectMapper objectMapper;

    public AppRuntimeService(AppRepository appRepository, AppRevisionRepository appRevisionRepository,
            AppProperties appProperties, WorkflowService workflowService,
            ToolCallingAgentService toolCallingAgentService, AiModelService aiModelService,
            ConversationMemoryService conversationMemoryService, TraceService traceService,
            UsageRecordingService usageRecordingService, Executor sseExecutor,
            SseConfig.SseProperties sseProperties, ObjectMapper objectMapper) {
        this.appRepository = appRepository;
        this.appRevisionRepository = appRevisionRepository;
        this.appProperties = appProperties;
        this.workflowService = workflowService;
        this.toolCallingAgentService = toolCallingAgentService;
        this.aiModelService = aiModelService;
        this.conversationMemoryService = conversationMemoryService;
        this.traceService = traceService;
        this.usageRecordingService = usageRecordingService;
        this.sseExecutor = sseExecutor;
        this.sseProperties = sseProperties;
        this.objectMapper = objectMapper;
    }

    public AppRunResultResponse run(String appId, AppRunRequest request) {
        AppSnapshot snapshot = resolveRuntimeSnapshot(appId);
        if (snapshot.type() != AppType.WORKFLOW) {
            throw new BusinessException("APP_TYPE_NOT_RUNNABLE",
                    "run is only supported for WORKFLOW apps; use chat for " + snapshot.type() + " apps");
        }
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

    public AppChatResponse chat(String appId, AppChatRequest request) {
        AppSnapshot snapshot = resolveRuntimeSnapshot(appId);
        RunContext.setAppId(appId);
        try {
            return switch (snapshot.type()) {
                case CHAT -> chatWithModel(appId, snapshot, request);
                case AGENT -> agentChat(appId, request);
                case WORKFLOW -> throw new BusinessException("APP_TYPE_NOT_CHATTABLE",
                        "chat is not supported for WORKFLOW apps; use run instead");
            };
        }
        finally {
            RunContext.clear();
        }
    }

    private AppChatResponse chatWithModel(String appId, AppSnapshot snapshot, AppChatRequest request) {
        AppConfig config = snapshot.config() == null ? AppConfig.empty() : snapshot.config();
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = historyFor(config, conversationId);
        TraceRun run = traceService.startRun(RunType.CHAT,
                Map.of("appId", appId, "conversationId", conversationId, "message", request.message()));
        TraceStep step = traceService.startTraceStep(run.runId(), "app_chat",
                Map.of("appId", appId, "conversationId", conversationId, "historySize", history.size()));
        try {
            AiModelResult result = aiModelService.generate(systemPrompt(config), history, request.message(),
                    config.model());
            String answer = requireAnswer(result);
            usageRecordingService.record(run.runId(), appId, result.tokenUsage());
            conversationMemoryService.appendUserMessage(conversationId, request.message());
            conversationMemoryService.appendAssistantMessage(conversationId, answer);
            AppChatResponse response = new AppChatResponse(answer, conversationId, run.runId(), appId);
            traceService.completeStep(step.stepId(), Map.of("answer", answer));
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    private AppChatResponse agentChat(String appId, AppChatRequest request) {
        AssistantChatResponse response = toolCallingAgentService.assistantChat(
                new ToolChatRequest(request.conversationId(), request.message()));
        return new AppChatResponse(response.answer(), response.conversationId(), response.runId(), appId);
    }

    public SseEmitter stream(String appId, AppChatRequest request) {
        AppSnapshot snapshot = resolveRuntimeSnapshot(appId);
        if (snapshot.type() == AppType.WORKFLOW) {
            throw new BusinessException("APP_TYPE_NOT_CHATTABLE",
                    "chat/stream is not supported for WORKFLOW apps; use run instead");
        }
        // AGENT streaming is single-shot: delegate to the assistant, then emit the full answer.
        if (snapshot.type() == AppType.AGENT) {
            return singleShotStream(appId, request);
        }
        return chatStream(appId, snapshot, request);
    }

    private SseEmitter singleShotStream(String appId, AppChatRequest request) {
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        try {
            sseExecutor.execute(() -> {
                RunContext.setAppId(appId);
                try {
                    AppChatResponse response = agentChat(appId, request);
                    if (terminal.compareAndSet(false, true)) {
                        send(emitter, "message", new StreamChunk(response.runId(), response.answer()));
                        send(emitter, "done", new StreamDone(response.runId(), response.conversationId(),
                                response.answer()));
                        emitter.complete();
                    }
                }
                catch (RuntimeException ex) {
                    completeWithError(emitter, terminal, null, ex.getMessage());
                }
                finally {
                    RunContext.clear();
                }
            });
        }
        catch (RejectedExecutionException ex) {
            completeWithError(emitter, terminal, null, ex.getMessage());
        }
        return emitter;
    }

    private SseEmitter chatStream(String appId, AppSnapshot snapshot, AppChatRequest request) {
        AppConfig config = snapshot.config() == null ? AppConfig.empty() : snapshot.config();
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        // Create the run on the request thread so RunContext tags it with appId before dispatch.
        RunContext.setAppId(appId);
        TraceRun run;
        try {
            run = traceService.startRun(RunType.CHAT,
                    Map.of("appId", appId, "conversationId", conversationId, "message", request.message()));
        }
        finally {
            RunContext.clear();
        }
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicReference<TraceStep> stepRef = new AtomicReference<>();
        emitter.onTimeout(() -> completeWithError(emitter, terminal, stepRef, "SSE stream timed out"));
        emitter.onError(error -> failRun(run.runId(), terminal, stepRef, error));
        try {
            sseExecutor.execute(() -> streamInBackground(config, conversationId, request, run, emitter, terminal,
                    stepRef));
        }
        catch (RejectedExecutionException ex) {
            completeWithError(emitter, terminal, stepRef, ex.getMessage());
            traceService.markRunFailed(run.runId(), ex);
        }
        return emitter;
    }

    private void streamInBackground(AppConfig config, String conversationId, AppChatRequest request, TraceRun run,
            SseEmitter emitter, AtomicBoolean terminal, AtomicReference<TraceStep> stepRef) {
        List<ConversationMessage> history = historyFor(config, conversationId);
        StringBuilder answer = new StringBuilder();
        try {
            TraceStep step = traceService.startTraceStep(run.runId(), "app_stream_chat",
                    Map.of("conversationId", conversationId, "historySize", history.size()));
            stepRef.set(step);
            aiModelService.stream(systemPrompt(config), history, request.message(), chunk -> {
                answer.append(chunk);
                send(emitter, "message", new StreamChunk(run.runId(), chunk));
            });
            if (terminal.compareAndSet(false, true)) {
                conversationMemoryService.appendUserMessage(conversationId, request.message());
                conversationMemoryService.appendAssistantMessage(conversationId, answer.toString());
                traceService.completeStep(step.stepId(), Map.of("answer", answer.toString()));
                stepRef.set(null);
                traceService.markRunSucceeded(run.runId(),
                        new AppChatResponse(answer.toString(), conversationId, run.runId(), null));
                send(emitter, "done", new StreamDone(run.runId(), conversationId, answer.toString()));
                emitter.complete();
            }
        }
        catch (RuntimeException ex) {
            log.warn("App SSE chat failed", ex);
            completeWithError(emitter, terminal, stepRef, ex.getMessage());
            traceService.markRunFailed(run.runId(), ex);
        }
    }

    private AppSnapshot resolveRuntimeSnapshot(String appId) {
        AppEntity app = appRepository.findByAppIdAndOwnerId(appId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("APP_NOT_FOUND", "App not found: " + appId));
        if (app.getStatus() == AppStatus.ARCHIVED) {
            throw new BusinessException("APP_ARCHIVED", "App is archived and cannot be invoked: " + appId);
        }
        if (appProperties.isRequirePublishedForRun()) {
            if (app.getPublishedVersion() == null) {
                throw new BusinessException("APP_NOT_PUBLISHED",
                        "App must be published before it can be invoked: " + appId);
            }
            return snapshotForVersion(appId, app.getPublishedVersion());
        }
        if (app.getPublishedVersion() != null) {
            return snapshotForVersion(appId, app.getPublishedVersion());
        }
        return currentDraftSnapshot(app);
    }

    private AppSnapshot snapshotForVersion(String appId, Integer version) {
        return fromJson(appRevisionRepository
                .findByAppIdAndVersionAndOwnerId(appId, version, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("APP_REVISION_NOT_FOUND",
                        "App revision not found: " + appId + ":" + version))
                .getSnapshotJson());
    }

    private AppSnapshot currentDraftSnapshot(AppEntity app) {
        return new AppSnapshot(app.getName(), app.getDescription(), app.getType(),
                configFromJson(app.getConfigJson()), app.getWorkflowDefinitionId(),
                app.getWorkflowDefinitionVersion());
    }

    private List<ConversationMessage> historyFor(AppConfig config, String conversationId) {
        if (!config.memoryEnabledOrDefault()) {
            return List.of();
        }
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        Integer max = config.memoryMaxMessages();
        if (max != null && max >= 0 && history.size() > max) {
            return history.subList(history.size() - max, history.size());
        }
        return history;
    }

    private String systemPrompt(AppConfig config) {
        return StringUtils.hasText(config.systemPrompt()) ? config.systemPrompt() : DEFAULT_CHAT_SYSTEM_PROMPT;
    }

    private String requireAnswer(AiModelResult result) {
        if (result.fallback() || !StringUtils.hasText(result.answer())) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM is required for app chat and returned no usable answer");
        }
        return result.answer();
    }

    private void completeWithError(SseEmitter emitter, AtomicBoolean terminal, AtomicReference<TraceStep> stepRef,
            String message) {
        if (terminal.compareAndSet(false, true)) {
            if (stepRef != null) {
                TraceStep step = stepRef.getAndSet(null);
                if (step != null) {
                    traceService.failStep(step.stepId(), new IllegalStateException(message));
                }
            }
            try {
                send(emitter, "error", new StreamError(null, message == null ? "app chat failed" : message));
                emitter.complete();
            }
            catch (RuntimeException ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    private void failRun(String runId, AtomicBoolean terminal, AtomicReference<TraceStep> stepRef, Throwable error) {
        if (terminal.compareAndSet(false, true)) {
            TraceStep step = stepRef.getAndSet(null);
            if (step != null) {
                traceService.failStep(step.stepId(), error);
            }
            traceService.markRunFailed(runId, error);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to send app SSE event", ex);
        }
    }

    private AppConfig configFromJson(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return AppConfig.empty();
        }
        try {
            return objectMapper.readValue(configJson, AppConfig.class);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("APP_CONFIG_DESERIALIZATION_FAILED", "Failed to read app config", ex);
        }
    }

    private AppSnapshot fromJson(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, AppSnapshot.class);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("APP_SNAPSHOT_DESERIALIZATION_FAILED", "Failed to read app snapshot", ex);
        }
    }

}
