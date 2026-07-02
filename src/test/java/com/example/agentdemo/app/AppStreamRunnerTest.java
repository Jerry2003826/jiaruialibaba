package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppStreamRunnerTest {

    @Test
    void streamTraceOutputIncludesAppIdAndCitations() {
        AgentAppRunner agentAppRunner = mock(AgentAppRunner.class);
        ChatAppRunner chatAppRunner = mock(ChatAppRunner.class);
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        AppKnowledgeContextService knowledgeContextService = mock(AppKnowledgeContextService.class);
        AppStreamRunner runner = new AppStreamRunner(agentAppRunner, chatAppRunner, aiModelService,
                conversationMemoryService, traceService, knowledgeContextService, Runnable::run,
                new SseConfig.SseProperties(120000));
        AppConfig config = new AppConfig("System", "qwen-plus", true, 5, List.of("kb-1"));
        AppSnapshot snapshot = new AppSnapshot("Chat", null, AppType.CHAT, config, null, null);
        List<Citation> citations = List.of(new Citation(1L, "Policy", 0, "Refunds", 0.8));
        when(chatAppRunner.config(snapshot)).thenReturn(config);
        when(conversationMemoryService.resolveConversationId(null)).thenReturn("conv-1");
        when(chatAppRunner.historyFor(config, "conv-1")).thenReturn(List.of());
        when(traceService.startRun(RunType.CHAT,
                Map.of("appId", "app-1", "conversationId", "conv-1", "message", "refund?")))
                .thenReturn(new TraceRun("run-1", Instant.now()));
        when(knowledgeContextService.retrieve(config, "refund?", "run-1")).thenReturn(citations);
        when(knowledgeContextService.augmentMessage("refund?", citations)).thenReturn("augmented refund?");
        when(traceService.startTraceStep("run-1", "app_stream_chat",
                Map.of("conversationId", "conv-1", "historySize", 0, "citationCount", 1)))
                .thenReturn(new TraceStep("step-1", "run-1", "app_stream_chat"));
        when(chatAppRunner.systemPrompt(config)).thenReturn("System");
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("hello");
            return null;
        }).when(aiModelService).stream(anyString(), anyList(), anyString(), any());

        runner.chatStream("app-1", snapshot, new AppChatRequest(null, "refund?"));

        ArgumentCaptor<Object> outputCaptor = ArgumentCaptor.forClass(Object.class);
        verify(traceService).markRunSucceeded(eq("run-1"), outputCaptor.capture());
        assertThat(outputCaptor.getValue()).isInstanceOf(AppChatResponse.class);
        AppChatResponse response = (AppChatResponse) outputCaptor.getValue();
        assertThat(response.answer()).isEqualTo("hello");
        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.appId()).isEqualTo("app-1");
        assertThat(response.citations()).containsExactlyElementsOf(citations);
    }

}
