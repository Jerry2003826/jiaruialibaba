package com.example.agentdemo.agent;

import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallingAgentServiceTest {

    @Test
    void usesRuleBasedFallbackWhenModelUnavailable() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "tool_calculate"),
                        new TraceStep("step-3", "run-1", "agent_final_answer"));
        when(aiModelService.generate(anyString(), any(), anyString())).thenReturn(AiModelResult.ok("The answer is 4"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed());

        ToolChatResponse response = service.toolChat(new ToolChatRequest(null, "Please calculate (12 + 8) / 5"));

        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().toolName()).isEqualTo("calculate");
        verify(conversationMemoryService).appendUserMessage("conv-1", "Please calculate (12 + 8) / 5");
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "The answer is 4");
    }

    @Test
    void usesLlmToolCallingWhenModelConfigured() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(aiModelService.isModelConfigured()).thenReturn(true);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("The answer is 4");
        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(eq("run-1"), eq("agent_llm_tool_chat"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_llm_tool_chat"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed());

        ToolChatResponse response = service.toolChat(new ToolChatRequest("conv-1", "Please calculate (12 + 8) / 5"));

        assertThat(response.answer()).isEqualTo("The answer is 4");
        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.toolCalls()).isEmpty();

        ArgumentCaptor<ToolCallback[]> callbacksCaptor = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec).toolCallbacks(callbacksCaptor.capture());
        assertThat(callbacksCaptor.getValue()).isNotEmpty();
        assertThat(callbacksCaptor.getValue()[0]).isInstanceOf(TracingToolCallback.class);
        verify(traceService).completeStep(eq("step-1"), eq(Map.of("answer", "The answer is 4", "mode", "llm", "toolCallCount", 0)));
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "The answer is 4");
    }

    @Test
    void llmToolCallingRecordsToolExecutionWhenCallbackRuns() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_calculate"), any()))
                .thenReturn(new TraceStep("tool-step", "run-1", "tool_calculate"));
        List<ToolExecutionLog> toolCalls = new java.util.ArrayList<>();

        ToolCallback calculate = callbackFactory.tracedToolCallbacks("run-1", traceService, toolCalls).stream()
                .filter(callback -> "calculate".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        String output = calculate.call("{\"expression\":\"(12 + 8) / 5\"}");

        assertThat(output).isEqualTo("4");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().toolName()).isEqualTo("calculate");
        verify(traceService, atLeastOnce()).completeStep(eq("tool-step"), any());
    }

    private static ToolGatewayService gatewayWithLocalTools() {
        return new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
    }

    private static void stubConversation(ConversationMemoryService conversationMemoryService) {
        when(conversationMemoryService.resolveConversationId(any())).thenReturn("conv-1");
        when(conversationMemoryService.loadRecentMessages("conv-1")).thenReturn(List.of());
    }

}
