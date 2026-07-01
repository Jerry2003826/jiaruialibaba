package com.example.agentdemo.app.dto;

/**
 * Runtime response from a CHAT/AGENT app.
 *
 * @param answer         the assistant answer
 * @param conversationId the conversation id
 * @param runId          the trace run id
 * @param appId          the originating app id
 */
public record AppChatResponse(String answer, String conversationId, String runId, String appId) {
}
