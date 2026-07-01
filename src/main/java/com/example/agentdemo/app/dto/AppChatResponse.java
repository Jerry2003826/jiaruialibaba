package com.example.agentdemo.app.dto;

import com.example.agentdemo.knowledge.Citation;

import java.util.List;

/**
 * Runtime response from a CHAT/AGENT app.
 *
 * @param answer         the assistant answer
 * @param conversationId the conversation id
 * @param runId          the trace run id
 * @param appId          the originating app id
 * @param citations      knowledge-base citations used (empty when no KB is bound)
 */
public record AppChatResponse(String answer, String conversationId, String runId, String appId,
        List<Citation> citations) {

    /** Compatibility constructor for responses without citations. */
    public AppChatResponse(String answer, String conversationId, String runId, String appId) {
        this(answer, conversationId, runId, appId, List.of());
    }
}
