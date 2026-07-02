package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppStreamRunnerTest {

    @Test
    void chatStreamTimeoutMarksRunFailed() throws Exception {
        TraceService traceService = mock(TraceService.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ChatAppRunner chatAppRunner = mock(ChatAppRunner.class);
        Executor queuedExecutor = command -> {
        };
        AppStreamRunner runner = runner(traceService, memoryService, chatAppRunner, queuedExecutor);
        AppSnapshot snapshot = snapshot();
        when(chatAppRunner.config(snapshot)).thenReturn(AppConfig.empty());
        when(memoryService.resolveConversationId(null)).thenReturn("conv-1");
        when(traceService.startRun(eq(RunType.CHAT), any())).thenReturn(new TraceRun("run-1", Instant.now()));

        SseEmitter emitter = runner.chatStream("app-1", snapshot, new AppChatRequest(null, "hello"));
        triggerTimeout(emitter);

        verify(traceService).markRunFailed(eq("run-1"), any(IllegalStateException.class));
        verify(traceService, never()).markRunSucceeded(eq("run-1"), any());
    }

    @Test
    void chatStreamRejectedExecutionMarksRunFailed() {
        TraceService traceService = mock(TraceService.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ChatAppRunner chatAppRunner = mock(ChatAppRunner.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        AppStreamRunner runner = runner(traceService, memoryService, chatAppRunner, rejectingExecutor);
        AppSnapshot snapshot = snapshot();
        when(chatAppRunner.config(snapshot)).thenReturn(AppConfig.empty());
        when(memoryService.resolveConversationId(null)).thenReturn("conv-1");
        when(traceService.startRun(eq(RunType.CHAT), any())).thenReturn(new TraceRun("run-1", Instant.now()));

        runner.chatStream("app-1", snapshot, new AppChatRequest(null, "hello"));

        verify(traceService).markRunFailed(eq("run-1"), any(RejectedExecutionException.class));
        verify(traceService, never()).markRunSucceeded(eq("run-1"), any());
    }

    @Test
    void chatStreamDoesNotStartModelStepAfterTimeoutAlreadyWonTerminal() throws Exception {
        TraceService traceService = mock(TraceService.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ChatAppRunner chatAppRunner = mock(ChatAppRunner.class);
        AiModelService aiModelService = mock(AiModelService.class);
        AppKnowledgeContextService knowledgeContextService = mock(AppKnowledgeContextService.class);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        AppStreamRunner runner = runner(traceService, memoryService, chatAppRunner, aiModelService,
                knowledgeContextService, queuedTask::set);
        AppSnapshot snapshot = snapshot();
        AppConfig config = AppConfig.empty();
        when(chatAppRunner.config(snapshot)).thenReturn(config);
        when(memoryService.resolveConversationId(null)).thenReturn("conv-1");
        when(chatAppRunner.historyFor(config, "conv-1")).thenReturn(List.of());
        when(traceService.startRun(eq(RunType.CHAT), any())).thenReturn(new TraceRun("run-1", Instant.now()));

        SseEmitter emitter = runner.chatStream("app-1", snapshot, new AppChatRequest(null, "hello"));
        triggerTimeout(emitter);
        queuedTask.get().run();

        verify(traceService).markRunFailed(eq("run-1"), any(IllegalStateException.class));
        verify(knowledgeContextService, never()).retrieve(config, "hello", "run-1");
        verify(traceService, never()).startTraceStep(anyString(), anyString(), any());
        verify(aiModelService, never()).stream(anyString(), any(), anyString(), any());
        verify(traceService, never()).markRunSucceeded(eq("run-1"), any());
    }

    private AppStreamRunner runner(TraceService traceService, ConversationMemoryService memoryService,
            ChatAppRunner chatAppRunner, Executor executor) {
        return runner(traceService, memoryService, chatAppRunner, mock(AiModelService.class),
                mock(AppKnowledgeContextService.class), executor);
    }

    private AppStreamRunner runner(TraceService traceService, ConversationMemoryService memoryService,
            ChatAppRunner chatAppRunner, AiModelService aiModelService,
            AppKnowledgeContextService knowledgeContextService, Executor executor) {
        return new AppStreamRunner(mock(AgentAppRunner.class), chatAppRunner, aiModelService,
                memoryService, traceService, knowledgeContextService, executor, new SseConfig.SseProperties(1_000L));
    }

    private AppSnapshot snapshot() {
        return new AppSnapshot("Chat", null, AppType.CHAT, AppConfig.empty(), null, null);
    }

    private void triggerTimeout(SseEmitter emitter) throws ReflectiveOperationException {
        Field field = ResponseBodyEmitter.class.getDeclaredField("timeoutCallback");
        field.setAccessible(true);
        ((Runnable) field.get(emitter)).run();
    }

}
