package com.example.agentdemo.tool;

import java.util.List;
import java.util.Map;

public interface ToolProvider {

    String providerName();

    boolean supports(String toolName);

    ToolExecutionLog execute(String toolName, Map<String, Object> arguments);

    List<ToolDescriptor> tools();

}
