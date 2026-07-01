package com.example.agentdemo.tool;

import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolTestServiceTest {

    @Test
    void dryRunUsesSharedSchemaValidatorBeforeExecutingTool() {
        ToolGatewayService gateway = mock(ToolGatewayService.class);
        TraceService traceService = mock(TraceService.class);
        when(traceService.startRun(eq(RunType.TOOL_CHAT), any()))
                .thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(eq("run-1"), eq("tool_test_remote_enum"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "tool_test_remote_enum"));
        when(gateway.findTool("remote_enum"))
                .thenReturn(Optional.of(new ToolDescriptor("remote_enum", "enum tool", "mcp", true, "github",
                        """
                                {
                                  "type": "object",
                                  "properties": {
                                    "mode": {"type": "string", "enum": ["read", "write"]}
                                  },
                                  "required": ["mode"]
                                }
                                """)));
        ToolTestService service = new ToolTestService(gateway, traceService,
                new ToolSchemaValidator(new com.fasterxml.jackson.databind.ObjectMapper()));

        ToolExecutionLog log = service.test("remote_enum", Map.of("mode", "delete"));

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_VALIDATION);
        assertThat(log.errorMessage()).isEqualTo("Tool argument mode must be one of [\"read\",\"write\"]");
        verify(gateway, never()).execute(any(), any());
    }

}
