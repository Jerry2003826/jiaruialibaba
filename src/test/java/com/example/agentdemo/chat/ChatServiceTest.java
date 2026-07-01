package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.ChatRequest;
import com.example.agentdemo.chat.dto.ChatResponse;
import com.example.agentdemo.chat.dto.StreamDone;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.config.SseConfig;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    @Test
    void chatPersistsConversationMemory() {
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        Executor executor = Runnable::run;
        SseConfig.SseProperties sseProperties = new SseConfig.SseProperties(120_000L);

        when(conversationMemoryService.resolveConversationId("conv-1")).thenReturn("conv-1");
        when(conversationMemoryService.loadRecentMessages("conv-1")).thenReturn(List.of());
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "dashscope_chat"));
        when(aiModelService.generate(anyString(), any(), anyString()))
                .thenReturn(AiModelResult.ok("hello"));

        ChatService chatService = new ChatService(aiModelService, conversationMemoryService, traceService, executor,
                sseProperties);
        ChatResponse response = chatService.chat(new ChatRequest("conv-1", "hi"));

        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.answer()).isEqualTo("hello");
        verify(conversationMemoryService).appendUserMessage("conv-1", "hi");
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "hello");
    }

    @Test
    void streamReusesResolvedConversationIdWhenMissingFromRequest() {
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        Executor executor = Runnable::run;
        SseConfig.SseProperties sseProperties = new SseConfig.SseProperties(120_000L);

        when(conversationMemoryService.resolveConversationId(null)).thenReturn("generated-conv");
        when(conversationMemoryService.loadRecentMessages("generated-conv")).thenReturn(List.of());
        when(traceService.startRun(eq(RunType.CHAT), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "dashscope_stream_chat"));
        doAnswer(invocation -> {
            java.util.function.Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("streamed");
            return null;
        }).when(aiModelService).stream(anyString(), any(), anyString(), any());

        ChatService chatService = new ChatService(aiModelService, conversationMemoryService, traceService, executor,
                sseProperties);
        SseEmitter emitter = chatService.stream(new ChatRequest(null, "hi"));

        assertThat(emitter).isNotNull();
        verify(conversationMemoryService, times(1)).resolveConversationId(null);
        verify(conversationMemoryService).appendUserMessage("generated-conv", "hi");
        verify(conversationMemoryService).appendAssistantMessage("generated-conv", "streamed");
        verify(traceService).markRunSucceeded(eq("run-1"), eq(new ChatResponse("streamed", "generated-conv", "run-1")));
    }

    @Test
    void chatRejectsModelFallbackAnswers() {
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        Executor executor = Runnable::run;
        SseConfig.SseProperties sseProperties = new SseConfig.SseProperties(120_000L);

        when(conversationMemoryService.resolveConversationId("conv-1")).thenReturn("conv-1");
        when(conversationMemoryService.loadRecentMessages("conv-1")).thenReturn(List.of());
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "dashscope_chat"));
        when(aiModelService.generate(anyString(), any(), anyString()))
                .thenReturn(AiModelResult.fallback("mock fallback payload", "model unavailable"));

        ChatService chatService = new ChatService(aiModelService, conversationMemoryService, traceService, executor,
                sseProperties);

        assertThatThrownBy(() -> chatService.chat(new ChatRequest("conv-1", "hi")))
                .isInstanceOf(com.example.agentdemo.common.BusinessException.class)
                .extracting(ex -> ((com.example.agentdemo.common.BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void clearConversationDelegatesToConversationMemory() {
        AiModelService aiModelService = mock(AiModelService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        Executor executor = Runnable::run;
        SseConfig.SseProperties sseProperties = new SseConfig.SseProperties(120_000L);
        when(conversationMemoryService.clearConversation("workbench-assistant")).thenReturn(6L);

        ChatService chatService = new ChatService(aiModelService, conversationMemoryService, traceService, executor,
                sseProperties);

        assertThat(chatService.clearConversation("workbench-assistant")).isEqualTo(6L);
        verify(conversationMemoryService).clearConversation("workbench-assistant");
    }

}
