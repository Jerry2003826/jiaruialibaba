package com.example.agentdemo.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ToolChatRequest(
        @Size(max = 128) String conversationId,
        @NotBlank @Size(max = 4000) String message) {
}
