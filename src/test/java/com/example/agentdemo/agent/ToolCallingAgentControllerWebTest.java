package com.example.agentdemo.agent;

import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.agent.dto.AssistantChatResponse;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolCallingAgentControllerWebTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolChatRouteReturnsConversationIdAndToolCalls() throws Exception {
        ToolCallingAgentService agentService = mock(ToolCallingAgentService.class);
        when(agentService.toolChat(any(ToolChatRequest.class))).thenReturn(new ToolChatResponse(
                "The answer is 4", "conv-1", "run-1",
                List.of(ToolExecutionLog.success("calculate", java.util.Map.of("expression", "(12 + 8) / 5"), "4",
                        Instant.now(), Instant.now(), null))));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ToolCallingAgentController(agentService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/agent/tool-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ToolChatRequest("conv-1", "calculate (12+8)/5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("The answer is 4"))
                .andExpect(jsonPath("$.data.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data.toolCalls[0].toolName").value("calculate"));
    }

    @Test
    void assistantChatRouteReturnsToolCallsAndRetrievedContext() throws Exception {
        ToolCallingAgentService agentService = mock(ToolCallingAgentService.class);
        when(agentService.assistantChat(any(ToolChatRequest.class))).thenReturn(new AssistantChatResponse(
                "合并后的回答", "conv-1", "run-1",
                List.of(ToolExecutionLog.success("calculate", java.util.Map.of("expression", "(12 + 8) / 5"), "4",
                        Instant.now(), Instant.now(), null)),
                List.of(new RetrievedContext(7L, "工作台笔记", "支持工具调用和知识库检索", 0.88))));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ToolCallingAgentController(agentService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/agent/assistant-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ToolChatRequest("conv-1", "结合知识库并计算"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("合并后的回答"))
                .andExpect(jsonPath("$.data.toolCalls[0].toolName").value("calculate"))
                .andExpect(jsonPath("$.data.retrievedContext[0].title").value("工作台笔记"));
    }

}
