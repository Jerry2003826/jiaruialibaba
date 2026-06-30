package com.example.agentdemo.support;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.workflow.GraphWorkflowRuntime;
import com.example.agentdemo.workflow.SimpleWorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowCompiler;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.example.agentdemo.workflow.WorkflowInlineExecutionService;
import com.example.agentdemo.workflow.WorkflowNodeExecutor;
import com.example.agentdemo.workflow.WorkflowNodeSchemaRegistry;
import com.example.agentdemo.workflow.WorkflowRunBudgetRegistry;
import com.example.agentdemo.workflow.WorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowVariableResolver;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class WorkflowRuntimeTestSupport {

    private WorkflowRuntimeTestSupport() {
    }

    public static TraceStep traceStep(String runId, String nodeName) {
        return new TraceStep("step-" + nodeName, runId, nodeName);
    }

    public record RuntimeStack(
            SimpleWorkflowRuntime runtime,
            WorkflowNodeExecutor nodeExecutor,
            WorkflowInlineExecutionService inlineExecutionService,
            WorkflowRunBudgetRegistry budgetRegistry) {
    }

    public record GraphRuntimeStack(
            GraphWorkflowRuntime runtime,
            WorkflowNodeExecutor nodeExecutor,
            WorkflowInlineExecutionService inlineExecutionService,
            WorkflowRunBudgetRegistry budgetRegistry) {
    }

    public static TraceService mockPermissiveTraceService() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(any(), any(), any()))
                .thenAnswer(invocation -> traceStep(
                        invocation.getArgument(0),
                        invocation.getArgument(1)));
        return traceService;
    }

    public static ToolGatewayService localToolGateway() {
        return new ToolGatewayService(List.of(new com.example.agentdemo.tool.LocalToolProvider(
                TestToolServices.toolService())));
    }

    @SuppressWarnings("unchecked")
    public static RuntimeStack simpleRuntimeStack(WorkflowDefinitionService definitionService,
            RagService ragService, AiModelService aiModelService, ToolGatewayService toolGatewayService,
            AlibabaRuntimePolicy alibabaRuntimePolicy, TraceService traceService,
            ExecutorService executorService) {
        AtomicReference<SimpleWorkflowRuntime> runtimeRef = new AtomicReference<>();
        ObjectProvider<WorkflowRuntime> workflowRuntimeProvider = mock(ObjectProvider.class);
        when(workflowRuntimeProvider.getObject()).thenAnswer(ignored -> runtimeRef.get());

        AtomicReference<WorkflowNodeExecutor> nodeExecutorRef = new AtomicReference<>();
        ObjectProvider<WorkflowNodeExecutor> nodeExecutorProvider = mock(ObjectProvider.class);
        when(nodeExecutorProvider.getObject()).thenAnswer(ignored -> nodeExecutorRef.get());

        WorkflowRunBudgetRegistry budgetRegistry = new WorkflowRunBudgetRegistry();
        WorkflowInlineExecutionService inlineExecutionService = new WorkflowInlineExecutionService(
                definitionService,
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                workflowRuntimeProvider,
                nodeExecutorProvider,
                new WorkflowVariableResolver(),
                traceService,
                executorService,
                budgetRegistry);
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(
                ragService,
                aiModelService,
                toolGatewayService,
                new WorkflowVariableResolver(),
                alibabaRuntimePolicy,
                inlineExecutionService);
        nodeExecutorRef.set(nodeExecutor);
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                nodeExecutor, traceService, executorService, inlineExecutionService, budgetRegistry);
        runtimeRef.set(runtime);
        return new RuntimeStack(runtime, nodeExecutor, inlineExecutionService, budgetRegistry);
    }

    @SuppressWarnings("unchecked")
    public static GraphRuntimeStack graphRuntimeStack(WorkflowDefinitionService definitionService,
            RagService ragService, AiModelService aiModelService, ToolGatewayService toolGatewayService,
            AlibabaRuntimePolicy alibabaRuntimePolicy, TraceService traceService,
            ExecutorService executorService) {
        AtomicReference<GraphWorkflowRuntime> runtimeRef = new AtomicReference<>();
        ObjectProvider<WorkflowRuntime> workflowRuntimeProvider = mock(ObjectProvider.class);
        when(workflowRuntimeProvider.getObject()).thenAnswer(ignored -> runtimeRef.get());

        AtomicReference<WorkflowNodeExecutor> nodeExecutorRef = new AtomicReference<>();
        ObjectProvider<WorkflowNodeExecutor> nodeExecutorProvider = mock(ObjectProvider.class);
        when(nodeExecutorProvider.getObject()).thenAnswer(ignored -> nodeExecutorRef.get());

        WorkflowRunBudgetRegistry budgetRegistry = new WorkflowRunBudgetRegistry();
        WorkflowInlineExecutionService inlineExecutionService = new WorkflowInlineExecutionService(
                definitionService,
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                workflowRuntimeProvider,
                nodeExecutorProvider,
                new WorkflowVariableResolver(),
                traceService,
                executorService,
                budgetRegistry);
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(
                ragService,
                aiModelService,
                toolGatewayService,
                new WorkflowVariableResolver(),
                alibabaRuntimePolicy,
                inlineExecutionService);
        nodeExecutorRef.set(nodeExecutor);
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                nodeExecutor, traceService, executorService, inlineExecutionService, budgetRegistry);
        runtimeRef.set(runtime);
        return new GraphRuntimeStack(runtime, nodeExecutor, inlineExecutionService, budgetRegistry);
    }

    @SuppressWarnings("unchecked")
    public static WorkflowInlineExecutionService inlineExecutionService(WorkflowNodeExecutor nodeExecutor,
            TraceService traceService, ExecutorService executorService) {
        ObjectProvider<WorkflowRuntime> workflowRuntimeProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkflowNodeExecutor> nodeExecutorProvider = mock(ObjectProvider.class);
        when(nodeExecutorProvider.getObject()).thenReturn(nodeExecutor);
        return new WorkflowInlineExecutionService(
                mock(WorkflowDefinitionService.class),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                workflowRuntimeProvider,
                nodeExecutorProvider,
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
