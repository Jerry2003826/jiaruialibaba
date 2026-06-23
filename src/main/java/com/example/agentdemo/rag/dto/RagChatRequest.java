package com.example.agentdemo.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagChatRequest(
        @Size(max = 128) String conversationId,
        @NotBlank @Size(max = 4000) String message) {
}
