package com.example.agentdemo.support;

import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolProvider;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

import com.example.agentdemo.workflow.WorkflowCompiler;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.example.agentdemo.workflow.WorkflowInlineExecutionService;
import com.example.agentdemo.workflow.WorkflowNodeExecutor;
import com.example.agentdemo.workflow.WorkflowNodeSchemaRegistry;
import com.example.agentdemo.workflow.WorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowVariableResolver;

public final class WorkflowRuntimeTestSupport {

    private WorkflowRuntimeTestSupport() {
    }

    public static TraceStep traceStep(String runId, String nodeName) {
        return new TraceStep("step-" + nodeName, runId, nodeName);
    }

    @SuppressWarnings("unchecked")
    public static WorkflowInlineExecutionService inlineExecutionService(WorkflowNodeExecutor nodeExecutor,
            TraceService traceService, ExecutorService executorService) {
        ObjectProvider<WorkflowRuntime> workflowRuntimeProvider = mock(ObjectProvider.class);
        return new WorkflowInlineExecutionService(
                mock(WorkflowDefinitionService.class),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                workflowRuntimeProvider,
                nodeExecutor,
                new WorkflowVariableResolver(),
                traceService,
                executorService);
    }

    public static boolean hasAttemptCount(Object output, int expected) {
        return output instanceof Map<?, ?> map
                && map.get("attempts") instanceof List<?> attempts
                && attempts.size() == expected;
    }

    public static boolean hasFailedAttempt(Object output) {
        return output instanceof Map<?, ?> map
                && map.get("attempts") instanceof List<?> attempts
                && !attempts.isEmpty()
                && attempts.getFirst() instanceof Map<?, ?> attempt
                && "FAILED".equals(attempt.get("status"));
    }

    public static final class MapEchoProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "map_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return ToolExecutionLog.success(toolName, arguments, Map.of("text", arguments.get("text")), now, now,
                    new ToolDescriptor(toolName, "Map echo", providerName(), true));
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("map_echo", "Map echo", providerName(), true));
        }

    }

    public static final class FlakyProvider implements ToolProvider {

        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "flaky_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            int attempt = attempts.incrementAndGet();
            Instant now = Instant.now();
            ToolDescriptor descriptor = new ToolDescriptor(toolName, "Flaky echo", providerName(), true);
            if (attempt == 1) {
                return ToolExecutionLog.failure(toolName, arguments, "temporary failure", now, now,
                        descriptor, ToolExecutionLog.ERROR_REMOTE_TOOL);
            }
            return ToolExecutionLog.success(toolName, arguments, Map.of("text", "ok"), now, now, descriptor);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("flaky_echo", "Flaky echo", providerName(), true));
        }

        public int attemptCount() {
            return attempts.get();
        }

    }

    public static final class SlowProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "slow_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant startedAt = Instant.now();
            try {
                Thread.sleep(500);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            Instant endedAt = Instant.now();
            ToolDescriptor descriptor = new ToolDescriptor(toolName, "Slow echo", providerName(), true);
            return ToolExecutionLog.success(toolName, arguments, Map.of("text", "late"), startedAt, endedAt,
                    descriptor);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("slow_echo", "Slow echo", providerName(), true));
        }

    }

}
