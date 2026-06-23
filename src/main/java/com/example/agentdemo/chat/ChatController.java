package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.ChatRequest;
import com.example.agentdemo.chat.dto.ChatResponse;
import com.example.agentdemo.chat.dto.HealthResponse;
import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final AiModelService aiModelService;

    public ChatController(ChatService chatService, AiModelService aiModelService) {
        this.chatService = chatService;
        this.aiModelService = aiModelService;
    }

    @GetMapping("/health")
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.ok(new HealthResponse("UP", Instant.now(), aiModelService.isModelConfigured(),
                aiModelService.modelName()));
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chatService.chat(request));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request);
    }

}
