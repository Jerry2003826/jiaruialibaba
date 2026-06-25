package com.example.agentdemo.rag.dto;

import java.util.List;

public record RagChatResponse(String answer, String conversationId, String runId,
        List<RetrievedContext> retrievedContext) {
}
