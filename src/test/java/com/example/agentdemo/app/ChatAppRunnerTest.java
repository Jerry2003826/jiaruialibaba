package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.TokenUsage;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.usage.UsageRecordingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAppRunnerTest {

    @Test
    void injectsKnowledgeContextBeforeCallingModel() {
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        UsageRecordingService usageRecordingService = mock(UsageRecordingService.class);
        AppKnowledgeContextService knowledgeContextService = mock(AppKnowledgeContextService.class);
        ChatAppRunner runner = new ChatAppRunner(aiModelService, memoryService, traceService,
                usageRecordingService, knowledgeContextService);
        AppConfig config = new AppConfig("System", "qwen-plus", true, 5, List.of("kb-1"));
        AppSnapshot snapshot = new AppSnapshot("Chat", null, AppType.CHAT, config, null, null);
        List<Citation> citations = List.of(new Citation(1L, "Policy", 0, "Refunds", 0.8));
        when(memoryService.resolveConversationId(null)).thenReturn("conv-1");
        when(memoryService.loadRecentMessages("conv-1")).thenReturn(List.of());
        when(traceService.startRun(com.example.agentdemo.trace.RunType.CHAT,
                Map.of("appId", "app-1", "conversationId", "conv-1", "message", "refund?")))
                .thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep("run-1", "app_chat",
                Map.of("appId", "app-1", "conversationId", "conv-1", "historySize", 0)))
                .thenReturn(new TraceStep("step-1", "run-1", "app_chat"));
        when(knowledgeContextService.retrieve(config, "refund?", "run-1")).thenReturn(citations);
        when(knowledgeContextService.augmentMessage("refund?", citations)).thenReturn("augmented prompt");
        TokenUsage tokenUsage = new TokenUsage("alibaba", "qwen-plus", 10, 5, 15, null);
        when(aiModelService.generate("System", List.of(), "augmented prompt", "qwen-plus")).thenAnswer(invocation -> {
            assertThat(RunContext.currentAppId()).isEqualTo("app-1");
            return AiModelResult.ok("answer", tokenUsage);
        });
        doAnswer(invocation -> {
            assertThat(RunContext.currentAppId()).isEqualTo("app-1");
            return null;
        }).when(usageRecordingService).record("run-1", "app-1", tokenUsage);

        AppChatResponse response = runner.chat("app-1", snapshot, new AppChatRequest(null, "refund?"));

        assertThat(response.answer()).isEqualTo("answer");
        assertThat(response.citations()).containsExactlyElementsOf(citations);
        assertThat(RunContext.currentAppId()).isNull();
        verify(aiModelService).generate("System", List.of(), "augmented prompt", "qwen-plus");
        verify(usageRecordingService).record("run-1", "app-1", tokenUsage);
    }

    @Test
    void clearsRunContextWhenChatFails() {
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        UsageRecordingService usageRecordingService = mock(UsageRecordingService.class);
        AppKnowledgeContextService knowledgeContextService = mock(AppKnowledgeContextService.class);
        ChatAppRunner runner = new ChatAppRunner(aiModelService, memoryService, traceService,
                usageRecordingService, knowledgeContextService);
        AppConfig config = new AppConfig("System", "qwen-plus", true, 5, List.of());
        AppSnapshot snapshot = new AppSnapshot("Chat", null, AppType.CHAT, config, null, null);
        RuntimeException failure = new RuntimeException("model failed");
        when(memoryService.resolveConversationId(null)).thenReturn("conv-1");
        when(memoryService.loadRecentMessages("conv-1")).thenReturn(List.of());
        when(traceService.startRun(com.example.agentdemo.trace.RunType.CHAT,
                Map.of("appId", "app-1", "conversationId", "conv-1", "message", "hello")))
                .thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep("run-1", "app_chat",
                Map.of("appId", "app-1", "conversationId", "conv-1", "historySize", 0)))
                .thenReturn(new TraceStep("step-1", "run-1", "app_chat"));
        when(knowledgeContextService.retrieve(config, "hello", "run-1")).thenReturn(List.of());
        when(knowledgeContextService.augmentMessage("hello", List.of())).thenReturn("hello");
        when(aiModelService.generate("System", List.of(), "hello", "qwen-plus")).thenAnswer(invocation -> {
            assertThat(RunContext.currentAppId()).isEqualTo("app-1");
            throw failure;
        });

        assertThatThrownBy(() -> runner.chat("app-1", snapshot, new AppChatRequest(null, "hello")))
                .isSameAs(failure);

        assertThat(RunContext.currentAppId()).isNull();
        verify(traceService).failStep("step-1", failure);
        verify(traceService).markRunFailed("run-1", failure);
    }

}
