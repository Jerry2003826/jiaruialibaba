package com.example.agentdemo.chat.memory;

/**
 * In-memory view of a persisted conversation message.
 */
public record ConversationMessage(ConversationRole role, String content) {
}
