package com.example.agentdemo.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @Size(max = 128) String conversationId,
        @NotBlank @Size(max = 4000) String message) {
}
