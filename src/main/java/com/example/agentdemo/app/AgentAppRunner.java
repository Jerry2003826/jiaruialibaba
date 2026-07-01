package com.example.agentdemo.app;

import com.example.agentdemo.agent.ToolCallingAgentService;
import com.example.agentdemo.agent.dto.AssistantChatResponse;
import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.trace.RunContext;
import org.springframework.stereotype.Service;

@Service
public class AgentAppRunner {

    private final ToolCallingAgentService toolCallingAgentService;

    public AgentAppRunner(ToolCallingAgentService toolCallingAgentService) {
        this.toolCallingAgentService = toolCallingAgentService;
    }

    public AppChatResponse chat(String appId, AppChatRequest request) {
        RunContext.setAppId(appId);
        try {
            AssistantChatResponse response = toolCallingAgentService.assistantChat(
                    new ToolChatRequest(request.conversationId(), request.message()));
            return new AppChatResponse(response.answer(), response.conversationId(), response.runId(), appId);
        }
        finally {
            RunContext.clear();
        }
    }

}
