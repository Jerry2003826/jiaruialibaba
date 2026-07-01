package com.example.agentdemo.tool;

import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolGatewayService toolGatewayService;
    private final McpServerRegistryService mcpServerRegistryService;
    private final ToolTestService toolTestService;

    public ToolController(ToolGatewayService toolGatewayService, McpServerRegistryService mcpServerRegistryService) {
        this(toolGatewayService, mcpServerRegistryService, null);
    }

    @Autowired
    public ToolController(ToolGatewayService toolGatewayService, McpServerRegistryService mcpServerRegistryService,
            ToolTestService toolTestService) {
        this.toolGatewayService = toolGatewayService;
        this.mcpServerRegistryService = mcpServerRegistryService;
        this.toolTestService = toolTestService;
    }

    @GetMapping
    public ApiResponse<List<ToolDescriptor>> listTools() {
        return ApiResponse.ok(toolGatewayService.listTools());
    }

    @GetMapping("/catalog")
    public ApiResponse<List<ToolView>> catalog() {
        return ApiResponse.ok(toolGatewayService.listToolViews());
    }

    @GetMapping("/mcp/servers")
    public ApiResponse<List<McpServerSummary>> listMcpServers() {
        return ApiResponse.ok(mcpServerRegistryService.listServers());
    }

    @PostMapping("/{toolName}/test")
    public ApiResponse<ToolExecutionLog> test(@PathVariable String toolName,
            @Valid @RequestBody(required = false) ToolTestRequest request) {
        return ApiResponse.ok(toolTestService.test(toolName, request == null ? null : request.arguments()));
    }

}
