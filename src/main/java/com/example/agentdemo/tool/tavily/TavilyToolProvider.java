package com.example.agentdemo.tool.tavily;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Order(1)
public class TavilyToolProvider implements ToolProvider {

    public static final String TOOL_NAME = "tavilySearch";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "query":{"type":"string","minLength":1,"maxLength":4000},
                "search_depth":{"type":"string","enum":["basic","advanced","fast","ultra-fast"]},
                "topic":{"type":"string","enum":["general","news","finance"]},
                "max_results":{"type":"integer","minimum":1,"maximum":20},
                "include_answer":{"type":"boolean"},
                "include_raw_content":{"type":"boolean"},
                "time_range":{"type":"string","enum":["day","week","month","year","d","w","m","y"]},
                "include_domains":{"type":"array","items":{"type":"string"},"maxItems":20},
                "exclude_domains":{"type":"array","items":{"type":"string"},"maxItems":20}
              },
              "required":["query"],
              "additionalProperties":false
            }
            """;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            TOOL_NAME,
            "Search the public web with Tavily and return grounded sources.",
            "tavily",
            false,
            "tavily",
            INPUT_SCHEMA);

    private final TavilySearchService searchService;

    public TavilyToolProvider(TavilySearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String providerName() {
        return "tavily";
    }

    @Override
    public boolean supports(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    @Override
    public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
        if (!supports(toolName)) {
            Instant now = Instant.now();
            return ToolExecutionLog.failure(toolName, arguments, "Tool not found: " + toolName, now, now,
                    null, ToolExecutionLog.ERROR_TOOL_NOT_FOUND);
        }
        Instant startedAt = Instant.now();
        try {
            Map<String, Object> output = searchService.search(arguments);
            return ToolExecutionLog.success(toolName, arguments, output, startedAt, Instant.now(), DESCRIPTOR);
        }
        catch (BusinessException ex) {
            return ToolExecutionLog.failure(toolName, arguments, ex.getMessage(), startedAt, Instant.now(),
                    DESCRIPTOR, ex.getCode());
        }
        catch (RuntimeException ex) {
            return ToolExecutionLog.failure(toolName, arguments, "Tavily search failed", startedAt, Instant.now(),
                    DESCRIPTOR, ToolExecutionLog.ERROR_EXECUTION);
        }
    }

    @Override
    public List<ToolDescriptor> tools() {
        return List.of(DESCRIPTOR);
    }
}
