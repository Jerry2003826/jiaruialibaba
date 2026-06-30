package com.example.agentdemo.agent;

import com.example.agentdemo.support.TestToolServices;
import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallingAgentServiceStrictTest {

    @Test
    void throwsWhenStrictModeEnabledAndLlmUnavailable() {
        ToolGatewayService toolGatewayService = new ToolGatewayService(List.of(new LocalToolProvider(TestToolServices.toolService())));
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        when(conversationMemoryService.resolveConversationId(any())).thenReturn("conv-1");
        when(conversationMemoryService.loadRecentMessages("conv-1")).thenReturn(List.of());
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.strictMode(), ragService);

        assertThatThrownBy(() -> service.toolChat(new ToolChatRequest(null, "calculate 1+1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_TOOL_CHAT_UNAVAILABLE");
    }

}
