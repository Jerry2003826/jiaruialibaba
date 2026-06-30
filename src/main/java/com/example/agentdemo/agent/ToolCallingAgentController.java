package com.example.agentdemo.agent;

import com.example.agentdemo.agent.dto.AssistantChatResponse;
import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class ToolCallingAgentController {

    private final ToolCallingAgentService toolCallingAgentService;

    public ToolCallingAgentController(ToolCallingAgentService toolCallingAgentService) {
        this.toolCallingAgentService = toolCallingAgentService;
    }

    @PostMapping("/tool-chat")
    public ApiResponse<ToolChatResponse> toolChat(@Valid @RequestBody ToolChatRequest request) {
        return ApiResponse.ok(toolCallingAgentService.toolChat(request));
    }

    @PostMapping("/assistant-chat")
    public ApiResponse<AssistantChatResponse> assistantChat(@Valid @RequestBody ToolChatRequest request) {
        return ApiResponse.ok(toolCallingAgentService.assistantChat(request));
    }

}
