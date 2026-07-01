package com.example.agentdemo.tool;

import java.util.Map;

/**
 * Console dry-run request for a tool.
 *
 * @param arguments the tool arguments (may be null/empty)
 */
public record ToolTestRequest(Map<String, Object> arguments) {
}
