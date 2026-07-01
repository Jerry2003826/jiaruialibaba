package com.example.agentdemo.tool;

/**
 * Console view of a tool: descriptor fields plus whether it is currently executable under the
 * allowlist policy (remote tools must be allowlisted; local tools are always executable).
 */
public record ToolView(String name, String description, String provider, boolean remote, String serverName,
        String inputSchema, boolean executable) {
}
