package com.example.agentdemo.agent;

import com.example.agentdemo.support.TestToolServices;
import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.agent.dto.AssistantChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.chat.memory.ConversationRole;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.workflow.WorkflowRunRequest;
import com.example.agentdemo.workflow.WorkflowRunResponse;
import com.example.agentdemo.workflow.WorkflowService;
import com.example.agentdemo.workflow.WorkflowStepSummary;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallingAgentServiceTest {

    @Test
    void assistantChatRunsBoundWorkflowWhenWorkflowDefinitionIdIsProvided() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        WorkflowService workflowService = mock(WorkflowService.class);

        stubConversation(conversationMemoryService);
        ToolExecutionLog toolLog = ToolExecutionLog.success("queryOrderAPI", Map.of("user_query", "我要退货"),
                Map.of("status", "SHIPPED"), Instant.now(), Instant.now(), null);
        when(workflowService.run(any(WorkflowRunRequest.class))).thenReturn(new WorkflowRunResponse(
                Map.of(
                        "answer", "工作流生成的客服回答",
                        "toolCalls", List.of(toolLog)),
                "workflow-run-1",
                List.of(new WorkflowStepSummary("llm_1", "llm", "SUCCEEDED", Map.of("answer", "工作流生成的客服回答"))),
                "workflow-1",
                7));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.strictMode(), ragService, workflowService);

        AssistantChatResponse response = service.assistantChat(
                new ToolChatRequest("conv-1", "我要退货", "workflow-1", null));

        assertThat(response.answer()).isEqualTo("工作流生成的客服回答");
        assertThat(response.runId()).isEqualTo("workflow-run-1");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().toolName()).isEqualTo("queryOrderAPI");
        verify(workflowService).run(argThat(request -> "workflow-1".equals(request.definitionId())
                && request.definitionVersion() == null
                && "我要退货".equals(request.input().get("message"))));
        verify(traceService, never()).startRun(any(), any());
        verify(ragService, never()).retrieveForChat(anyString(), anyString());
        verify(conversationMemoryService).appendUserMessage("conv-1", "我要退货");
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "工作流生成的客服回答");
    }

    @Test
    void assistantChatCanRunInlineCanvasWorkflowDefinitionForDraftHotUpdate() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowDefinition inlineDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_1", "llm", Map.of("prompt", "回答 {{input.message}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_1"),
                        new WorkflowEdge("llm_1", "end")));

        stubConversation(conversationMemoryService);
        when(workflowService.run(any(WorkflowRunRequest.class))).thenReturn(new WorkflowRunResponse(
                Map.of("answer", "草稿画布工作流回答"),
                "workflow-run-draft",
                List.of(new WorkflowStepSummary("llm_1", "llm", "SUCCEEDED", Map.of("answer", "草稿画布工作流回答")))));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.strictMode(), ragService, workflowService);

        AssistantChatResponse response = service.assistantChat(
                new ToolChatRequest("conv-1", "按当前画布回答", inlineDefinition, null, null));

        assertThat(response.answer()).isEqualTo("草稿画布工作流回答");
        verify(workflowService).run(argThat(request -> request.workflowDefinition() == inlineDefinition
                && request.definitionId() == null
                && "按当前画布回答".equals(request.input().get("message"))));
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "草稿画布工作流回答");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assistantWorkflowInputResolvesHistoricalOrderReferencesForMultipleOrders() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        List<ConversationMessage> history = List.of(
                new ConversationMessage(ConversationRole.USER, "我的订单号是 99999999，帮我查物流"),
                new ConversationMessage(ConversationRole.ASSISTANT, "为您查询了订单号 99999999，但系统中未能找到该订单的相关记录。"),
                new ConversationMessage(ConversationRole.USER, "我的订单号是 20260630001，帮我查物流"),
                new ConversationMessage(ConversationRole.ASSISTANT, "订单 20260630001 已发货，运单号 SF20260630001。"),
                new ConversationMessage(ConversationRole.USER, "20260630002"),
                new ConversationMessage(ConversationRole.ASSISTANT, "订单 20260630002 的退货申请已提交，等待仓库审核。"));

        stubConversation(conversationMemoryService, history);
        when(workflowService.run(any(WorkflowRunRequest.class))).thenReturn(new WorkflowRunResponse(
                Map.of("answer", "已处理两个订单"),
                "workflow-run-orders",
                List.of(new WorkflowStepSummary("end", "end", "SUCCEEDED", Map.of("answer", "已处理两个订单"))),
                "workflow-1",
                3));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.strictMode(), ragService, workflowService);

        service.assistantChat(new ToolChatRequest("conv-1", "就是我刚才提到的两个订单", "workflow-1", 3));

        ArgumentCaptor<WorkflowRunRequest> requestCaptor = ArgumentCaptor.forClass(WorkflowRunRequest.class);
        verify(workflowService).run(requestCaptor.capture());
        Map<String, Object> workflowInput = requestCaptor.getValue().input();
        assertThat((List<String>) workflowInput.get("recentOrderIds"))
                .containsExactly("20260630001", "20260630002");
        assertThat((List<String>) workflowInput.get("referencedOrderIds"))
                .containsExactly("20260630001", "20260630002");
        assertThat(workflowInput.get("orderLookupReady")).isEqualTo(true);
        assertThat(workflowInput.get("orderLookupCount")).isEqualTo(2);
        List<Map<String, Object>> calls = (List<Map<String, Object>>) workflowInput.get("orderToolCalls");
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(call -> call.get("toolName"))
                .containsExactly("queryOrderAPI", "queryOrderAPI");
        assertThat(calls).extracting(call -> call.get("user_query").toString())
                .containsExactly(
                        "Order id: 20260630001\nUser message: 就是我刚才提到的两个订单",
                        "Order id: 20260630002\nUser message: 就是我刚才提到的两个订单");
    }

    @Test
    void assistantChatCombinesKnowledgeRetrievalAndToolCalls() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        String message = "请结合知识库，并计算 (12 + 8) / 5";
        RetrievedContext context = new RetrievedContext(7L, "工作台笔记", "支持工具调用和知识库检索", 0.88);

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "tool_calculate"),
                        new TraceStep("step-3", "run-1", "assistant_final_answer"));
        when(ragService.retrieveForChat("run-1", message)).thenReturn(List.of(context));
        when(aiModelService.generate(anyString(), any(), anyString())).thenReturn(AiModelResult.ok("合并后的回答"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService);

        AssistantChatResponse response = service.assistantChat(new ToolChatRequest(null, message));

        assertThat(response.answer()).isEqualTo("合并后的回答");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().toolName()).isEqualTo("calculate");
        assertThat(response.retrievedContext()).containsExactly(context);
        verify(traceService).startRun(eq(RunType.ASSISTANT_CHAT), any());
        verify(ragService).retrieveForChat("run-1", message);
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "合并后的回答");
    }

    @Test
    void assistantChatAsksForOrderNumberBeforeRetrievingKnowledge() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);

        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(eq("run-1"), eq("agent_order_number_clarification"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_order_number_clarification"));
        when(aiModelService.generate(anyString(), any(), anyString()))
                .thenReturn(AiModelResult.ok("请提供您的订单号，我会继续帮您处理退货。"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.strictMode(), ragService);

        AssistantChatResponse response = service.assistantChat(new ToolChatRequest(null, "我要退货"));

        assertThat(response.answer()).contains("订单号");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.retrievedContext()).isEmpty();
        verify(ragService, never()).retrieveForChat(anyString(), anyString());
        verify(aiModelService).generate(anyString(), any(), anyString());
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "请提供您的订单号，我会继续帮您处理退货。");
    }

    @Test
    void assistantChatClarificationPromptIncludesInvalidOrderIdCandidate() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);

        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(eq("run-1"), eq("agent_order_number_clarification"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_order_number_clarification"));
        when(aiModelService.generate(anyString(), any(), anyString()))
                .thenReturn(AiModelResult.ok("您提供的 abc123 不是有效完整订单号，请提供完整订单号。"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.strictMode(), ragService);

        AssistantChatResponse response = service.assistantChat(new ToolChatRequest(null, "我的订单号是 abc123，帮我查物流"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).generate(anyString(), any(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("abc123");
        assertThat(promptCaptor.getValue()).contains("Invalid-looking order id candidate supplied by user: abc123");
        assertThat(promptCaptor.getValue()).contains("at least 8 digits");
        assertThat(response.answer()).contains("abc123");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.retrievedContext()).isEmpty();
        verify(ragService, never()).retrieveForChat(anyString(), anyString());
    }

    @Test
    void assistantChatRunsOrderLookupWhenOrderIdIsProvided() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        String message = "我要退货，订单号 20260630001";

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "tool_queryOrderAPI"),
                        new TraceStep("step-3", "run-1", "assistant_final_answer"));
        when(aiModelService.generate(anyString(), any(), anyString())).thenReturn(AiModelResult.ok("订单已发货"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService);

        AssistantChatResponse response = service.assistantChat(new ToolChatRequest(null, message));

        assertThat(response.answer()).isEqualTo("订单已发货");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().toolName()).isEqualTo("queryOrderAPI");
        assertThat(response.retrievedContext()).isEmpty();
        verify(ragService, never()).retrieveForChat(anyString(), anyString());
        Map<?, ?> output = (Map<?, ?>) response.toolCalls().getFirst().output();
        assertThat(output.get("source")).isEqualTo("database:demo_orders");
        assertThat(output.get("customerName")).isEqualTo("Alice Chen");
    }

    @Test
    void assistantChatLetsAiExplainMissingOrderResult() {
        ToolGatewayService toolGatewayService = new ToolGatewayService(List.of(new LocalToolProvider(
                new ToolService(userQuery -> Map.of(
                        "found", false,
                        "orderId", "99999999",
                        "source", "database:demo_orders",
                        "userQuery", userQuery)))));
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        String message = "99999999";
        List<ConversationMessage> history = List.of(new ConversationMessage(ConversationRole.ASSISTANT,
                "麻烦您提供订单号，我来帮您查询物流信息。"));

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService, history);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "tool_queryOrderAPI"),
                        new TraceStep("step-3", "run-1", "assistant_final_answer"));
        when(aiModelService.generate(anyString(), any(), anyString()))
                .thenReturn(AiModelResult.ok("没有查到订单 99999999，请您核对订单号后再发给我。"));

        AssistantChatResponse response = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService)
                .assistantChat(new ToolChatRequest(null, message));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).generate(anyString(), any(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("99999999").contains("found=false");
        assertThat(response.answer()).isEqualTo("没有查到订单 99999999，请您核对订单号后再发给我。");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().succeeded()).isTrue();
        assertThat(response.toolCalls().getFirst().errorMessage()).isNull();
        assertThat(response.retrievedContext()).isEmpty();
        verify(ragService, never()).retrieveForChat(anyString(), anyString());
    }

    @Test
    void assistantChatUsesKnowledgeForGenericOrderPolicyQuestionEvenWithPreviousOrderId() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        String message = "已发货订单退货流程是什么？";
        RetrievedContext orderContext = new RetrievedContext(3L, "Demo customer order 20260630001",
                "Demo customer service order record. Order id: 20260630001 Status: SHIPPED.", 0.95);
        RetrievedContext context = new RetrievedContext(9L, "已发货订单退货流程",
                "已发货订单可在派送时拒收，或签收后提交退货退款申请。", 0.91);
        List<ConversationMessage> history = List.of(new ConversationMessage(ConversationRole.ASSISTANT,
                "您的订单 20260630001 当前由顺丰配送。"));

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService, history);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "assistant_final_answer"));
        when(ragService.retrieveForChat("run-1", message)).thenReturn(List.of(orderContext, context));
        when(aiModelService.generate(anyString(), any(), anyString()))
                .thenReturn(AiModelResult.ok("已发货订单可以拒收，或签收后申请退货退款。"));

        AssistantChatResponse response = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService)
                .assistantChat(new ToolChatRequest(null, message));

        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.retrievedContext()).containsExactly(context);
        assertThat(response.answer()).isEqualTo("已发货订单可以拒收，或签收后申请退货退款。");
        ArgumentCaptor<List<ConversationMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).generate(anyString(), historyCaptor.capture(), promptCaptor.capture());
        assertThat(historyCaptor.getValue()).isEmpty();
        assertThat(promptCaptor.getValue())
                .contains("已发货订单退货流程")
                .doesNotContain("Demo customer order 20260630001");
        verify(ragService).retrieveForChat("run-1", message);
    }

    @Test
    void assistantLlmToolCallbacksExcludeOrderLookupForGenericPolicyQuestion() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        String message = "已发货订单退货流程是什么？";
        RetrievedContext context = new RetrievedContext(9L, "已发货订单退货流程",
                "已发货订单可在派送时拒收，或签收后提交退货退款申请。", 0.91);
        List<ConversationMessage> history = List.of(new ConversationMessage(ConversationRole.ASSISTANT,
                "您的订单 20260630001 当前由顺丰配送。"));

        when(aiModelService.isModelConfigured()).thenReturn(true);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("已发货订单可以拒收，或签收后申请退货退款。");
        stubConversation(conversationMemoryService, history);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(eq("run-1"), eq("assistant_llm_chat"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "assistant_llm_chat"));
        when(ragService.retrieveForChat("run-1", message)).thenReturn(List.of(context));

        AssistantChatResponse response = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService)
                .assistantChat(new ToolChatRequest(null, message));

        ArgumentCaptor<ToolCallback[]> callbacksCaptor = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec).toolCallbacks(callbacksCaptor.capture());
        assertThat(List.of(callbacksCaptor.getValue()))
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("getCurrentTime", "calculate")
                .doesNotContain("queryOrderAPI");
        assertThat(response.answer()).isEqualTo("已发货订单可以拒收，或签收后申请退货退款。");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.retrievedContext()).containsExactly(context);
    }

    @Test
    void assistantChatUsesOrderIdFromFollowUpAfterPrompt() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        List<ConversationMessage> history = List.of(new ConversationMessage(ConversationRole.ASSISTANT,
                "请提供订单号，我拿到订单号后才能查询对应订单信息。"));

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService, history);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "tool_queryOrderAPI"),
                        new TraceStep("step-3", "run-1", "assistant_final_answer"));
        when(aiModelService.generate(anyString(), any(), anyString())).thenReturn(AiModelResult.ok("已查询到订单"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService);

        AssistantChatResponse response = service.assistantChat(new ToolChatRequest(null, "20260630001"));

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().toolName()).isEqualTo("queryOrderAPI");
        assertThat(response.answer()).isEqualTo("已查询到订单");
        assertThat(response.retrievedContext()).isEmpty();
        verify(ragService, never()).retrieveForChat(anyString(), anyString());
    }

    @Test
    void usesRuleBasedFallbackWhenModelUnavailable() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);

        when(aiModelService.isModelConfigured()).thenReturn(false);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);
        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(anyString(), anyString(), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_plan"),
                        new TraceStep("step-2", "run-1", "tool_calculate"),
                        new TraceStep("step-3", "run-1", "agent_final_answer"));
        when(aiModelService.generate(anyString(), any(), anyString())).thenReturn(AiModelResult.ok("The answer is 4"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService);

        ToolChatResponse response = service.toolChat(new ToolChatRequest(null, "Please calculate (12 + 8) / 5"));

        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().toolName()).isEqualTo("calculate");
        verify(traceService).startRun(eq(RunType.TOOL_CHAT), any());
        verify(conversationMemoryService).appendUserMessage("conv-1", "Please calculate (12 + 8) / 5");
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "The answer is 4");
    }

    @Test
    void usesLlmToolCallingWhenModelConfigured() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        AiModelService aiModelService = mock(AiModelService.class);
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        TraceService traceService = mock(TraceService.class);
        RagService ragService = mock(RagService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(aiModelService.isModelConfigured()).thenReturn(true);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("The answer is 4");
        stubConversation(conversationMemoryService);
        when(traceService.startRun(any(), any())).thenReturn(new TraceRun("run-1", Instant.now()));
        when(traceService.startTraceStep(eq("run-1"), eq("agent_llm_tool_chat"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "agent_llm_tool_chat"));

        ToolCallingAgentService service = new ToolCallingAgentService(toolGatewayService, callbackFactory,
                aiModelService, chatClientProvider, conversationMemoryService, traceService,
                TestAlibabaPolicies.legacyFallbackAllowed(), ragService);

        ToolChatResponse response = service.toolChat(new ToolChatRequest("conv-1", "Please calculate (12 + 8) / 5"));

        assertThat(response.answer()).isEqualTo("The answer is 4");
        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.toolCalls()).isEmpty();

        ArgumentCaptor<ToolCallback[]> callbacksCaptor = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(requestSpec).toolCallbacks(callbacksCaptor.capture());
        assertThat(callbacksCaptor.getValue()).isNotEmpty();
        assertThat(callbacksCaptor.getValue()[0]).isInstanceOf(TracingToolCallback.class);
        verify(traceService).completeStep(eq("step-1"), eq(Map.of("answer", "The answer is 4", "mode", "llm", "toolCallCount", 0)));
        verify(conversationMemoryService).appendAssistantMessage("conv-1", "The answer is 4");
    }

    @Test
    void llmToolCallingRecordsToolExecutionWhenCallbackRuns() {
        ToolGatewayService toolGatewayService = gatewayWithLocalTools();
        DemoToolCallbackFactory callbackFactory = new DemoToolCallbackFactory(toolGatewayService, new ObjectMapper(),
                mock(ObjectProvider.class));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_calculate"), any()))
                .thenReturn(new TraceStep("tool-step", "run-1", "tool_calculate"));
        List<ToolExecutionLog> toolCalls = new java.util.ArrayList<>();

        ToolCallback calculate = callbackFactory.tracedToolCallbacks("run-1", traceService, toolCalls).stream()
                .filter(callback -> "calculate".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        String output = calculate.call("{\"expression\":\"(12 + 8) / 5\"}");

        assertThat(output).isEqualTo("4");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().toolName()).isEqualTo("calculate");
        verify(traceService, atLeastOnce()).completeStep(eq("tool-step"), any());
    }

    private static ToolGatewayService gatewayWithLocalTools() {
        return new ToolGatewayService(List.of(new LocalToolProvider(TestToolServices.toolService())));
    }

    private static void stubConversation(ConversationMemoryService conversationMemoryService) {
        stubConversation(conversationMemoryService, List.of());
    }

    private static void stubConversation(ConversationMemoryService conversationMemoryService,
            List<ConversationMessage> history) {
        when(conversationMemoryService.resolveConversationId(any())).thenReturn("conv-1");
        when(conversationMemoryService.loadRecentMessages("conv-1")).thenReturn(history);
    }

}
