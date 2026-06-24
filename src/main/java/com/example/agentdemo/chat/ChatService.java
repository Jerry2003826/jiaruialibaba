package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.ChatRequest;
import com.example.agentdemo.chat.dto.ChatResponse;
import com.example.agentdemo.chat.dto.StreamChunk;
import com.example.agentdemo.chat.dto.StreamDone;
import com.example.agentdemo.chat.dto.StreamError;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
    private final TraceService traceService;
    private final Executor sseExecutor;
    private final SseConfig.SseProperties sseProperties;

    public ChatService(AiModelService aiModelService, TraceService traceService, Executor sseExecutor,
            SseConfig.SseProperties sseProperties) {
        this.aiModelService = aiModelService;
        this.traceService = traceService;
        this.sseExecutor = sseExecutor;
        this.sseProperties = sseProperties;
    }

    public ChatResponse chat(ChatRequest request) {
        TraceRun run = traceService.startRun(RunType.CHAT, request);
        TraceStep step = traceService.startTraceStep(run.runId(), "dashscope_chat",
                Map.of("message", request.message(), "conversationId", nullable(request.conversationId())));
        try {
            AiModelResult result = aiModelService.generate(SYSTEM_PROMPT, request.message());
            ChatResponse response = new ChatResponse(result.answer(), run.runId());
            traceService.completeStep(step.stepId(),
                    Map.of("answer", result.answer(), "fallback", result.fallback(),
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

    public SseEmitter stream(ChatRequest request) {
        TraceRun run = traceService.startRun(RunType.CHAT, request);
        SseEmitter emitter = new SseEmitter(sseProperties.timeoutMs());
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicReference<TraceStep> stepRef = new AtomicReference<>();
        emitter.onTimeout(() -> handleEmitterTimeout(run, emitter, terminal, stepRef));
        emitter.onError(error -> handleEmitterError(run, terminal, stepRef, error));
        try {
            sseExecutor.execute(() -> streamInBackground(request, run, emitter, terminal, stepRef));
        }
        catch (RejectedExecutionException ex) {
            handleStreamFailure(run, emitter, terminal, stepRef, ex);
        }
        return emitter;
    }

    private void streamInBackground(ChatRequest request, TraceRun run, SseEmitter emitter, AtomicBoolean terminal,
            AtomicReference<TraceStep> stepRef) {
        StringBuilder answer = new StringBuilder();
        try {
            TraceStep step = traceService.startTraceStep(run.runId(), "dashscope_stream_chat",
                    Map.of("message", request.message(), "conversationId", nullable(request.conversationId())));
            stepRef.set(step);
            boolean fallback = aiModelService.stream(SYSTEM_PROMPT, request.message(), chunk -> {
                answer.append(chunk);
                send(emitter, "message", new StreamChunk(run.runId(), chunk));
            });
            if (terminal.compareAndSet(false, true)) {
                traceService.completeStep(step.stepId(),
                        Map.of("answer", answer.toString(), "fallback", fallback));
                stepRef.set(null);
                traceService.markRunSucceeded(run.runId(), new ChatResponse(answer.toString(), run.runId()));
                try {
                    send(emitter, "done", new StreamDone(run.runId(), answer.toString()));
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

    private String nullable(String value) {
        return value == null ? "" : value;
    }

}
