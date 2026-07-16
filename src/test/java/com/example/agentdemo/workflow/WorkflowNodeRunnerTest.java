package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class WorkflowNodeRunnerTest {

    @Test
    void restoresVariableContextBeforeRetryingHttpStatus() {
        WorkflowNodeExecutor executor = mock(WorkflowNodeExecutor.class);
        ExecutorService executorService = mock(ExecutorService.class);
        WorkflowNodeRunner runner = new WorkflowNodeRunner(executor, executorService);
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"), "owner-1");
        Map<String, Object> originalOutput = Map.of("message", "hello");
        state.setLastOutput(originalOutput);

        int[] attempts = {0};
        doAnswer(invocation -> {
            WorkflowExecutionState currentState = invocation.getArgument(2);
            assertThat(currentState.lastOutput()).isEqualTo(originalOutput);
            Map<String, Object> response = attempts[0]++ == 0
                    ? Map.of("statusCode", 503, "succeeded", false)
                    : Map.of("statusCode", 200, "succeeded", true);
            currentState.setLastOutput(response);
            return response;
        }).when(executor).execute(eq("run-1"), any(WorkflowNode.class), eq(state));

        WorkflowNodeExecutionResult result = runner.execute("run-1", new WorkflowNode(
                "request", "http_request", Map.of(
                        "method", "GET",
                        "url", "https://api.example.test",
                        "retryCount", 1)), state);

        assertThat(result.output()).isEqualTo(Map.of("statusCode", 200, "succeeded", true));
        assertThat(attempts[0]).isEqualTo(2);
    }
}
