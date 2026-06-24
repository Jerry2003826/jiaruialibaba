package com.example.agentdemo.tool;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
@Order(0)
public class LocalToolProvider implements ToolProvider {

    private static final String GET_CURRENT_TIME = "getCurrentTime";
    private static final String CALCULATE = "calculate";

    private final ToolService toolService;

    public LocalToolProvider(ToolService toolService) {
        this.toolService = toolService;
    }

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public boolean supports(String toolName) {
        return GET_CURRENT_TIME.equals(toolName) || CALCULATE.equals(toolName);
    }

    @Override
    public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case GET_CURRENT_TIME -> toolService.executeGetCurrentTime();
            case CALCULATE -> toolService.executeCalculate(stringArgument(arguments, "expression"));
            default -> ToolGatewayService.toolNotFound(toolName, arguments);
        };
    }

    @Override
    public List<ToolDescriptor> tools() {
        return List.of(
                new ToolDescriptor(GET_CURRENT_TIME, "Return current server time in ISO-8601 format.",
                        providerName(), false),
                new ToolDescriptor(CALCULATE, "Calculate a safe arithmetic expression with +, -, *, / and parentheses.",
                        providerName(), false)
        );
    }

    private String stringArgument(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        String text = value == null ? "" : String.valueOf(value);
        return StringUtils.hasText(text) ? text : "";
    }

}
