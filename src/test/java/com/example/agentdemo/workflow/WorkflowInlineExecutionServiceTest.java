package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.support.WorkflowRuntimeTestSupport;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowInlineExecutionServiceTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("inline-exec-test-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void executeDynamicRejectsNonListItemsFrom() {
        InlineServiceStack stack = inlineServiceStack();
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("dyn_1", "dynamic", Map.of("itemsFrom", "{{input.tools}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));
        stack.inlineService().bindPlan(plan);
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("tools", "not-a-list"));
        WorkflowNode dynamicNode = plan.node("dyn_1");

        try {
            assertThatThrownBy(() -> stack.inlineService().executeDynamic("run-1", dynamicNode, state))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("itemsFrom must resolve to a list");
        }
        finally {
            stack.inlineService().clearPlan();
        }
    }

    @Test
    void executeDynamicRejectsUnsupportedAction() {
        InlineServiceStack stack = inlineServiceStack();
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("dyn_1", "dynamic", Map.of(
                                "itemsFrom", "{{input.tools}}",
                                "action", "llm")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));
        stack.inlineService().bindPlan(plan);
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("tools", List.of("getCurrentTime")));

        try {
            assertThatThrownBy(() -> stack.inlineService().executeDynamic("run-1", plan.node("dyn_1"), state))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("only supports action=tool");
        }
        finally {
            stack.inlineService().clearPlan();
        }
    }

    @Test
    void executeDynamicRejectsItemsOverBudget() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxDynamicItems(1);
        InlineServiceStack stack = inlineServiceStack(properties);
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("dyn_1", "dynamic", Map.of("itemsFrom", "{{input.tools}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));
        stack.inlineService().bindPlan(plan);
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("tools",
                List.of("getCurrentTime", "getCurrentTime")));

        try {
            assertThatThrownBy(() -> stack.inlineService().executeDynamic("run-1", plan.node("dyn_1"), state))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Dynamic item count exceeds limit");
        }
        finally {
            stack.inlineService().clearPlan();
        }
    }

    @Test
    void executeSubgraphRequiresDefinitionId() {
        InlineServiceStack stack = inlineServiceStack();
        WorkflowNode node = new WorkflowNode("sub_1", "subgraph", Map.of());

        assertThatThrownBy(() -> stack.inlineService().executeSubgraph("run-1", node, new WorkflowExecutionState(Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("requires config.definitionId");
    }

    @Test
    void executeSubgraphRejectsExcessiveNestingDepth() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        registerSubgraphChain(definitionService, 11);

        WorkflowRuntimeTestSupport.RuntimeStack runtimeStack = runtimeStack(definitionService);
        WorkflowExecutionPlan plan = compiler.compile(wrapSubgraphDefinition("wf-0"));
        assertThatThrownBy(() -> runtimeStack.runtime().run("run-depth", plan, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Subgraph nesting depth exceeds limit");
    }

    @Test
    void executeSubgraphRejectsNamespacedNodeIdsExceedingTraceLimit() {
        String longNodeId = "n".repeat(WorkflowInlineExecutionService.MAX_TRACE_NODE_ID_LENGTH);
        WorkflowDefinition childDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode(longNodeId, "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", longNodeId),
                        new WorkflowEdge(longNodeId, "end")));
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        when(definitionService.resolveDefinition("child-long", null))
                .thenReturn(new WorkflowDefinitionResolution("child-long", 1, childDefinition));

        WorkflowRuntimeTestSupport.RuntimeStack runtimeStack = runtimeStack(definitionService);
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", "child-long")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "sub_1"),
                        new WorkflowEdge("sub_1", "end"))));

        assertThatThrownBy(() -> runtimeStack.runtime().run("run-long-id", plan, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds trace storage limit");
    }

    @Test
    void executeSubgraphAllowsMaxNestingDepth() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        registerSubgraphChain(definitionService, 9);

        WorkflowRuntimeTestSupport.RuntimeStack runtimeStack = runtimeStack(definitionService);
        WorkflowExecutionPlan plan = compiler.compile(wrapSubgraphDefinition("wf-0"));
        WorkflowRuntime.WorkflowExecutionResult result = runtimeStack.runtime().run("run-depth-ok", plan, Map.of());

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("sub_1");
    }

    private WorkflowRuntimeTestSupport.RuntimeStack runtimeStack(WorkflowDefinitionService definitionService) {
        return WorkflowRuntimeTestSupport.simpleRuntimeStack(
                definitionService,
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);
    }

    private void registerSubgraphChain(WorkflowDefinitionService definitionService, int wrapperCount) {
        WorkflowDefinition leaf = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end")));
        when(definitionService.resolveDefinition("wf-" + wrapperCount, null))
                .thenReturn(new WorkflowDefinitionResolution("wf-" + wrapperCount, 1, leaf));

        for (int index = wrapperCount - 1; index >= 0; index--) {
            String childId = "wf-" + (index + 1);
            WorkflowDefinition wrapper = wrapSubgraphDefinition(childId);
            when(definitionService.resolveDefinition("wf-" + index, null))
                    .thenReturn(new WorkflowDefinitionResolution("wf-" + index, 1, wrapper));
        }
    }

    private WorkflowDefinition wrapSubgraphDefinition(String subgraphDefinitionId) {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", subgraphDefinitionId)),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "sub_1"),
                        new WorkflowEdge("sub_1", "end")));
    }

    @SuppressWarnings("unchecked")
    private InlineServiceStack inlineServiceStack() {
        return inlineServiceStack(new WorkflowRuntimeProperties());
    }

    @SuppressWarnings("unchecked")
    private InlineServiceStack inlineServiceStack(WorkflowRuntimeProperties workflowRuntimeProperties) {
        TraceService traceService = WorkflowRuntimeTestSupport.mockPermissiveTraceService();
        ToolGatewayService toolGatewayService = WorkflowRuntimeTestSupport.localToolGateway();
        AtomicReference<WorkflowNodeExecutor> nodeExecutorRef = new AtomicReference<>();
        ObjectProvider<WorkflowNodeExecutor> nodeExecutorProvider = mock(ObjectProvider.class);
        when(nodeExecutorProvider.getObject()).thenAnswer(ignored -> nodeExecutorRef.get());

        WorkflowInlineExecutionService inlineService = new WorkflowInlineExecutionService(
                mock(WorkflowDefinitionService.class),
                compiler,
                mock(ObjectProvider.class),
                nodeExecutorProvider,
                new WorkflowVariableResolver(),
                traceService,
                executorService,
                workflowRuntimeProperties);
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(
                mock(RagService.class),
                mock(AiModelService.class),
                toolGatewayService,
                new WorkflowVariableResolver(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                inlineService);
        nodeExecutorRef.set(nodeExecutor);
        return new InlineServiceStack(inlineService, nodeExecutor);
    }

    private record InlineServiceStack(
            WorkflowInlineExecutionService inlineService,
            WorkflowNodeExecutor nodeExecutor) {
    }

}
