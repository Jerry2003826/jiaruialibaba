package com.example.agentdemo.agent.dto;

import com.example.agentdemo.tool.ToolExecutionLog;

import java.util.List;

public record ToolChatResponse(String answer, String runId, List<ToolExecutionLog> toolCalls) {
}
