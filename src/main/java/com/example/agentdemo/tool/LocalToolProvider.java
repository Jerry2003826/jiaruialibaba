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
    private static final String QUERY_ORDER_API = "queryOrderAPI";
    private static final String GET_CURRENT_TIME_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}
            """;
    private static final String CALCULATE_SCHEMA = """
            {
              "type":"object",
              "properties":{"expression":{"type":"string","minLength":1,"maxLength":128}},
              "required":["expression"],
              "additionalProperties":false
            }
            """;
    private static final String QUERY_ORDER_API_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "user_query":{"type":"string","minLength":1},
                "query":{"type":"string","minLength":1},
                "orderId":{"type":"string","minLength":1}
              },
              "anyOf":[
                {"required":["user_query"]},
                {"required":["query"]},
                {"required":["orderId"]}
              ],
              "additionalProperties":false
            }
            """;

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
        return GET_CURRENT_TIME.equals(toolName) || CALCULATE.equals(toolName) || QUERY_ORDER_API.equals(toolName);
    }

    @Override
    public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case GET_CURRENT_TIME -> toolService.executeGetCurrentTime();
            case CALCULATE -> toolService.executeCalculate(stringArgument(arguments, "expression"));
            case QUERY_ORDER_API -> toolService.executeQueryOrderAPI(
                    stringArgument(arguments, "user_query", "query", "orderId"));
            default -> ToolGatewayService.toolNotFound(toolName, arguments);
        };
    }

    @Override
    public List<ToolDescriptor> tools() {
        return List.of(
                new ToolDescriptor(GET_CURRENT_TIME, "Return current server time in ISO-8601 format.",
                        providerName(), false, providerName(), GET_CURRENT_TIME_SCHEMA),
                new ToolDescriptor(CALCULATE, "Calculate a safe arithmetic expression with +, -, *, / and parentheses.",
                        providerName(), false, providerName(), CALCULATE_SCHEMA),
                new ToolDescriptor(QUERY_ORDER_API, "Demo customer-service order lookup API.",
                        providerName(), false, providerName(), QUERY_ORDER_API_SCHEMA)
        );
    }

    private String stringArgument(Map<String, Object> arguments, String... keys) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            String text = value == null ? "" : String.valueOf(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

}
