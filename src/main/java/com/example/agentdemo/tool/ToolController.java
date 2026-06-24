package com.example.agentdemo.tool;

import com.example.agentdemo.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolGatewayService toolGatewayService;

    public ToolController(ToolGatewayService toolGatewayService) {
        this.toolGatewayService = toolGatewayService;
    }

    @GetMapping
    public ApiResponse<List<ToolDescriptor>> listTools() {
        return ApiResponse.ok(toolGatewayService.listTools());
    }

}
