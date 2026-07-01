package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.ChatRequest;
import com.example.agentdemo.chat.dto.ChatResponse;
import com.example.agentdemo.chat.dto.StreamChunk;
import com.example.agentdemo.chat.dto.StreamDone;
import com.example.agentdemo.chat.dto.StreamError;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.usage.UsageRecordingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            You are a concise backend demo assistant. Answer directly and avoid inventing external state.
            """;

    private final AiModelService aiModelService;
    private final ConversationMemoryService conversationMemoryService;
    private final TraceService traceService;
    private final Executor sseExecutor;
    private final SseConfig.SseProperties sseProperties;
    private final UsageRecordingService usageRecordingService;

    @Autowired
    public ChatService(AiModelService aiModelService, ConversationMemoryService conversationMemoryService,
            TraceService traceService, Executor sseExecutor, SseConfig.SseProperties sseProperties,
            UsageRecordingService usageRecordingService) {
        this.aiModelService = aiModelService;
        this.conversationMemoryService = conversationMemoryService;
        this.traceService = traceService;
        this.sseExecutor = sseExecutor;
        this.sseProperties = sseProperties;
        this.usageRecordingService = usageRecordingService;
    }

    public ChatService(AiModelService aiModelService, ConversationMemoryService conversationMemoryService,
            TraceService traceService, Executor sseExecutor, SseConfig.SseProperties sseProperties) {
        this(aiModelService, conversationMemoryService, traceService, sseExecutor, sseProperties, null);
    }

    public ChatResponse chat(ChatRequest request) {
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        TraceRun run = traceService.startRun(RunType.CHAT, requestWithConversation(request, conversationId));
        TraceStep step = traceService.startTraceStep(run.runId(), "dashscope_chat",
                Map.of("message", request.message(), "conversationId", conversationId, "historySize", history.size()));
        try {
            AiModelResult result = aiModelService.generate(SYSTEM_PROMPT, history, request.message());
            String answer = requireModelAnswer(result, "chat");
            recordUsage(run.runId(), result);
            conversationMemoryService.appendUserMessage(conversationId, request.message());
            conversationMemoryService.appendAssistantMessage(conversationId, answer);
            ChatResponse response = new ChatResponse(answer, conversationId, run.runId());
            traceService.completeStep(step.stepId(),
                    Map.of("answer", answer, "fallback", result.fallback(),
                            "errorMessage", nullable(result.errorMessage())));
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    public long clearConversation(String conversationId) {
        return conversationMemoryService.clearConversation(conversationId);
    }

    public SseEmitter stream(ChatRequest request) {
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        TraceRun run = traceService.startRun(RunType.CHAT, requestWithConversation(request, conversationId));
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicReference<TraceStep> stepRef = new AtomicReference<>();
        emitter.onTimeout(() -> handleEmitterTimeout(run, emitter, terminal, stepRef));
        emitter.onError(error -> handleEmitterError(run, terminal, stepRef, error));
        try {
            sseExecutor.execute(() -> streamInBackground(conversationId, request, run, emitter, terminal, stepRef));
        }
        catch (RejectedExecutionException ex) {
            handleStreamFailure(run, emitter, terminal, stepRef, ex);
        }
        return emitter;
    }

    private void streamInBackground(String conversationId, ChatRequest request, TraceRun run, SseEmitter emitter,
            AtomicBoolean terminal, AtomicReference<TraceStep> stepRef) {
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        StringBuilder answer = new StringBuilder();
        try {
            TraceStep step = traceService.startTraceStep(run.runId(), "dashscope_stream_chat",
                    Map.of("message", request.message(), "conversationId", conversationId, "historySize",
                            history.size()));
            stepRef.set(step);
            aiModelService.stream(SYSTEM_PROMPT, history, request.message(), chunk -> {
                answer.append(chunk);
                send(emitter, "message", new StreamChunk(run.runId(), chunk));
            });
            if (terminal.compareAndSet(false, true)) {
                conversationMemoryService.appendUserMessage(conversationId, request.message());
                conversationMemoryService.appendAssistantMessage(conversationId, answer.toString());
                traceService.completeStep(step.stepId(),
                        Map.of("answer", answer.toString()));
                stepRef.set(null);
                traceService.markRunSucceeded(run.runId(),
                        new ChatResponse(answer.toString(), conversationId, run.runId()));
                try {
                    send(emitter, "done", new StreamDone(run.runId(), conversationId, answer.toString()));
                    emitter.complete();
                }
                catch (RuntimeException sendFailure) {
                    emitter.completeWithError(sendFailure);
                }
            }
        }
        catch (RuntimeException ex) {
            log.warn("SSE chat failed", ex);
            handleStreamFailure(run, emitter, terminal, stepRef, ex);
        }
    }

    private void handleEmitterTimeout(TraceRun run, SseEmitter emitter, AtomicBoolean terminal,
            AtomicReference<TraceStep> stepRef) {
        RuntimeException timeout = new IllegalStateException("SSE stream timed out after "
                + sseProperties.timeoutMs() + " ms");
        if (terminal.compareAndSet(false, true)) {
            failStepIfPresent(stepRef, timeout);
            traceService.markRunFailed(run.runId(), timeout);
            try {
                send(emitter, "error", new StreamError(run.runId(), timeout.getMessage()));
                emitter.complete();
            }
            catch (RuntimeException ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    private void handleEmitterError(TraceRun run, AtomicBoolean terminal, AtomicReference<TraceStep> stepRef,
            Throwable error) {
        if (terminal.compareAndSet(false, true)) {
            failStepIfPresent(stepRef, error);
            traceService.markRunFailed(run.runId(), error);
        }
    }

    private void handleStreamFailure(TraceRun run, SseEmitter emitter, AtomicBoolean terminal,
            AtomicReference<TraceStep> stepRef, RuntimeException ex) {
        if (terminal.compareAndSet(false, true)) {
            failStepIfPresent(stepRef, ex);
            traceService.markRunFailed(run.runId(), ex);
            try {
                send(emitter, "error", new StreamError(run.runId(), ex.getMessage()));
                emitter.complete();
            }
            catch (RuntimeException sendFailure) {
                emitter.completeWithError(sendFailure);
            }
        }
    }

    private void failStepIfPresent(AtomicReference<TraceStep> stepRef, Throwable error) {
        TraceStep step = stepRef.getAndSet(null);
        if (step != null) {
            traceService.failStep(step.stepId(), error);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to send SSE event", ex);
        }
    }

    private ChatRequest requestWithConversation(ChatRequest request, String conversationId) {
        return new ChatRequest(conversationId, request.message());
    }

    private String requireModelAnswer(AiModelResult result, String context) {
        if (result.fallback()) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM returned a fallback answer for " + context);
        }
        if (!StringUtils.hasText(result.answer())) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM returned an empty answer for " + context);
        }
        return result.answer();
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private void recordUsage(String runId, AiModelResult result) {
        if (usageRecordingService != null && result != null) {
            usageRecordingService.record(runId, RunContext.currentAppId(), result.tokenUsage());
        }
    }

}
