package com.example.agentdemo.agent;

import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracingToolCallbackTest {

    @Test
    void recordsSuccessfulToolCallInTraceAndCollector() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_demoTool"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "tool_demoTool"));
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("demoTool");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call("{\"value\":1}")).thenReturn("ok");
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        TracingToolCallback callback = new TracingToolCallback(delegate, "run-1", traceService, toolCalls);

        assertThat(callback.call("{\"value\":1}")).isEqualTo("ok");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().toolName()).isEqualTo("demoTool");
        verify(traceService).completeStep(eq("step-1"), any(ToolExecutionLog.class));
    }

    @Test
    void recordsFailedToolCallInTraceAndCollector() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_demoTool"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "tool_demoTool"));
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("demoTool");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call("{}")).thenThrow(new IllegalArgumentException("bad args"));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        TracingToolCallback callback = new TracingToolCallback(delegate, "run-1", traceService, toolCalls);

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().succeeded()).isFalse();
        verify(traceService).failStep(eq("step-1"), any(IllegalArgumentException.class), any(ToolExecutionLog.class));
    }

}
