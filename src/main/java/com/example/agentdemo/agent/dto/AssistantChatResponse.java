package com.example.agentdemo.agent.dto;

import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;

import java.util.List;

public record AssistantChatResponse(String answer, String conversationId, String runId,
        List<ToolExecutionLog> toolCalls, List<RetrievedContext> retrievedContext) {
}
