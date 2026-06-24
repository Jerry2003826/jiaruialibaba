package com.example.agentdemo.tool;

public record ToolDescriptor(String name, String description, String provider, boolean remote, String serverName,
        String inputSchema) {

    public ToolDescriptor(String name, String description, String provider, boolean remote) {
        this(name, description, provider, remote, provider, "");
    }

}
