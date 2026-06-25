package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.ChatRequest;
import com.example.agentdemo.chat.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerWebTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatRouteReturnsConversationId() throws Exception {
        ChatService chatService = mock(ChatService.class);
        AlibabaHealthService alibabaHealthService = mock(AlibabaHealthService.class);
        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("hello", "conv-1", "run-1"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService, alibabaHealthService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatRequest("conv-1", "hi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("hello"))
                .andExpect(jsonPath("$.data.conversationId").value("conv-1"));
    }

}
