package com.example.agentdemo.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Runtime request to chat with a CHAT/AGENT app.
 *
 * @param conversationId optional conversation id (server assigns one when blank)
 * @param message        the user message (required)
 */
public record AppChatRequest(
        @Size(max = 128) String conversationId,
        @NotBlank @Size(max = 8000) String message) {
}
