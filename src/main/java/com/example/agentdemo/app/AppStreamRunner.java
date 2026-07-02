package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.dto.StreamChunk;
import com.example.agentdemo.chat.dto.StreamDone;
import com.example.agentdemo.chat.dto.StreamError;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AppStreamRunner {

    private static final Logger log = LoggerFactory.getLogger(AppStreamRunner.class);

    private final AgentAppRunner agentAppRunner;
    private final ChatAppRunner chatAppRunner;
    private final AiModelService aiModelService;
    private final ConversationMemoryService conversationMemoryService;
    private final TraceService traceService;
    private final AppKnowledgeContextService knowledgeContextService;
    private final Executor sseExecutor;
    private final SseConfig.SseProperties sseProperties;

    public AppStreamRunner(AgentAppRunner agentAppRunner, ChatAppRunner chatAppRunner, AiModelService aiModelService,
            ConversationMemoryService conversationMemoryService, TraceService traceService,
            AppKnowledgeContextService knowledgeContextService, @Qualifier("sseExecutor") Executor sseExecutor,
            SseConfig.SseProperties sseProperties) {
        this.agentAppRunner = agentAppRunner;
        this.chatAppRunner = chatAppRunner;
        this.aiModelService = aiModelService;
        this.conversationMemoryService = conversationMemoryService;
        this.traceService = traceService;
        this.knowledgeContextService = knowledgeContextService;
        this.sseExecutor = sseExecutor;
        this.sseProperties = sseProperties;
    }

    public SseEmitter singleShot(String appId, AppChatRequest request) {
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        try {
            sseExecutor.execute(() -> {
                try {
                    AppChatResponse response = agentAppRunner.chat(appId, request);
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
            });
        }
        catch (RejectedExecutionException ex) {
            completeWithError(emitter, terminal, null, ex.getMessage());
        }
        return emitter;
    }

    public SseEmitter chatStream(String appId, AppSnapshot snapshot, AppChatRequest request) {
        AppConfig config = chatAppRunner.config(snapshot);
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
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
            sseExecutor.execute(() -> streamInBackground(appId, config, conversationId, request, run, emitter, terminal,
                    stepRef));
        }
        catch (RejectedExecutionException ex) {
            completeWithError(emitter, terminal, stepRef, ex.getMessage());
            traceService.markRunFailed(run.runId(), ex);
        }
        return emitter;
    }

    private void streamInBackground(String appId, AppConfig config, String conversationId, AppChatRequest request,
            TraceRun run, SseEmitter emitter, AtomicBoolean terminal, AtomicReference<TraceStep> stepRef) {
        List<ConversationMessage> history = chatAppRunner.historyFor(config, conversationId);
        StringBuilder answer = new StringBuilder();
        try {
            List<Citation> citations = knowledgeContextService.retrieve(config, request.message(), run.runId());
            String userMessage = knowledgeContextService.augmentMessage(request.message(), citations);
            TraceStep step = traceService.startTraceStep(run.runId(), "app_stream_chat",
                    Map.of("conversationId", conversationId, "historySize", history.size(),
                            "citationCount", citations.size()));
            stepRef.set(step);
            aiModelService.stream(chatAppRunner.systemPrompt(config), history, userMessage, chunk -> {
                answer.append(chunk);
                send(emitter, "message", new StreamChunk(run.runId(), chunk));
            });
            if (terminal.compareAndSet(false, true)) {
                conversationMemoryService.appendUserMessage(conversationId, request.message());
                conversationMemoryService.appendAssistantMessage(conversationId, answer.toString());
                traceService.completeStep(step.stepId(), Map.of("answer", answer.toString()));
                stepRef.set(null);
                traceService.markRunSucceeded(run.runId(),
                        new AppChatResponse(answer.toString(), conversationId, run.runId(), appId, citations));
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

}
