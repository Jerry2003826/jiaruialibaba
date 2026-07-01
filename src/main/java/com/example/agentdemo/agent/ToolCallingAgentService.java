package com.example.agentdemo.agent;

import com.example.agentdemo.agent.dto.AssistantChatResponse;
import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.chat.memory.ConversationRole;
import com.example.agentdemo.chat.memory.SpringMessageConverter;
import com.example.agentdemo.order.OrderIdExtractor;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.workflow.WorkflowRunRequest;
import com.example.agentdemo.workflow.WorkflowRunResponse;
import com.example.agentdemo.workflow.WorkflowService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolCallingAgentService {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("([0-9(][0-9+\\-*/().\\s]*[0-9)])");
    private static final Pattern ORDER_ID_CLAIM_PATTERN = Pattern.compile(
            "(?i)(?:订单号|order\\s*(?:id|number))\\s*(?:是|为|:|：)?\\s*([A-Za-z0-9_-]{3,})");
    // A bare "它" pronoun referring to a prior order, but not the "它" inside "其它" (= "other"),
    // so "其它退货问题" is not mistaken for a reference to a historical order.
    private static final Pattern STANDALONE_TA_PATTERN = Pattern.compile("(?<!其)它");
    private static final List<String> ORDER_INTENT_KEYWORDS = List.of(
            "订单", "退货", "退款", "物流", "快递", "运单", "发货", "包裹", "配送", "售后", "拒收",
            "order", "return", "refund", "tracking", "shipment", "delivery");
    private static final List<String> ORDER_POLICY_KEYWORDS = List.of(
            "流程", "政策", "规则", "指南", "说明", "是什么", "怎么办", "如何", "怎么",
            "process", "policy", "procedure", "guide", "how to");
    private static final List<String> SPECIFIC_ORDER_REFERENCE_KEYWORDS = List.of(
            "我", "我的", "这个", "这单", "该订单", "当前", "刚才", "刚刚", "上面", "上一",
            "订单号", "包裹", "快递员", "my", "mine", "this order", "current order", "order id", "order number");
    private static final String ORDER_CLARIFICATION_SYSTEM_PROMPT = """
            You are a customer-service assistant.
            The user is asking about an order, return, refund, logistics, tracking, shipment, or after-sales issue,
            but no explicit order id is available. Ask for the order id before any lookup.
            A valid order id must be an explicit numeric id with at least 8 digits.
            If the user supplied a value that looks like an order id but does not match that format, politely say
            that the supplied value is not a valid complete order id, then ask for the valid complete order id.
            Do not mention internal tools, RAG, databases, or implementation details.
            Keep the reply concise and natural.
            """;

    private static final String TOOL_SYSTEM_PROMPT = """
            You are a helpful agent with access to tools.
            Use tools when they help answer the user question accurately.
            For customer-service questions about a specific order, return, refund, logistics, tracking, or shipment,
            first require an explicit order id. If the user has not provided an order id, ask for it instead of
            calling tools. Once an order id is available, call queryOrderAPI and answer from that tool result.
            For general order, return, refund, or shipment policy/process questions, do not call queryOrderAPI;
            answer naturally without exposing internal tool details.
            If queryOrderAPI reports that no order was found, explain that politely in the user's language and ask
            the user to verify the order id or contact human support; do not expose internal field names or raw errors.
            Keep the final answer concise.
            """;

    private static final String ASSISTANT_SYSTEM_PROMPT = """
            You are a helpful assistant with access to both tools and retrieved knowledge base context.
            Use retrieved context when it is relevant, and use tools when they improve accuracy.
            If the context is missing or insufficient, say what is missing instead of inventing details.
            For general order, return, refund, or shipment policy/process questions, answer from retrieved knowledge
            base context and do not call queryOrderAPI.
            For customer-service questions about a specific order, return, refund, logistics, tracking, or shipment,
            first require an explicit order id. If the user has not provided an order id, ask for it and do not use
            retrieved order records. Once an order id is available, call queryOrderAPI and answer from that tool result.
            If queryOrderAPI reports that no order was found, explain that politely in the user's language and ask
            the user to verify the order id or contact human support; do not expose internal field names or raw errors.
            Keep the final answer concise.
            """;

    private final ToolGatewayService toolGatewayService;
    private final DemoToolCallbackFactory demoToolCallbackFactory;
    private final AiModelService aiModelService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ConversationMemoryService conversationMemoryService;
    private final TraceService traceService;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;
    private final RagService ragService;
    private final WorkflowService workflowService;

    @Autowired
    public ToolCallingAgentService(ToolGatewayService toolGatewayService,
            DemoToolCallbackFactory demoToolCallbackFactory, AiModelService aiModelService,
            ObjectProvider<ChatClient> chatClientProvider, ConversationMemoryService conversationMemoryService,
            TraceService traceService, AlibabaRuntimePolicy alibabaRuntimePolicy, RagService ragService,
            WorkflowService workflowService) {
        this.toolGatewayService = toolGatewayService;
        this.demoToolCallbackFactory = demoToolCallbackFactory;
        this.aiModelService = aiModelService;
        this.chatClientProvider = chatClientProvider;
        this.conversationMemoryService = conversationMemoryService;
        this.traceService = traceService;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
        this.ragService = ragService;
        this.workflowService = workflowService;
    }

    public ToolCallingAgentService(ToolGatewayService toolGatewayService,
            DemoToolCallbackFactory demoToolCallbackFactory, AiModelService aiModelService,
            ObjectProvider<ChatClient> chatClientProvider, ConversationMemoryService conversationMemoryService,
            TraceService traceService, AlibabaRuntimePolicy alibabaRuntimePolicy, RagService ragService) {
        this(toolGatewayService, demoToolCallbackFactory, aiModelService, chatClientProvider,
                conversationMemoryService, traceService, alibabaRuntimePolicy, ragService, null);
    }

    public ToolChatResponse toolChat(ToolChatRequest request) {
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        ToolChatRequest traceRequest = new ToolChatRequest(conversationId, request.message());
        TraceRun run = traceService.startRun(RunType.TOOL_CHAT, traceRequest);
        try {
            boolean needsOrderNumber = shouldAskForOrderNumber(request.message(), history);
            ToolChatResponse response;
            if (needsOrderNumber) {
                String answer = generateOrderNumberClarification(request.message(), history, run.runId());
                response = new ToolChatResponse(answer, conversationId, run.runId(), List.of());
            }
            else if (shouldRunDeterministicOrderLookup(request.message(), history)) {
                response = ruleBasedToolChat(request, conversationId, history, run.runId());
            }
            else if (requireLlmToolCalling()) {
                response = llmToolChat(request, conversationId, history, run.runId());
            }
            else {
                response = ruleBasedToolChat(request, conversationId, history, run.runId());
            }
            conversationMemoryService.appendUserMessage(conversationId, request.message());
            conversationMemoryService.appendAssistantMessage(conversationId, response.answer());
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    public AssistantChatResponse assistantChat(ToolChatRequest request) {
        if (hasAssistantWorkflowBinding(request)) {
            return workflowAssistantChat(request);
        }
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        ToolChatRequest traceRequest = new ToolChatRequest(conversationId, request.message());
        TraceRun run = traceService.startRun(RunType.ASSISTANT_CHAT, traceRequest);
        try {
            boolean needsOrderNumber = shouldAskForOrderNumber(request.message(), history);
            AssistantChatResponse response;
            if (needsOrderNumber) {
                String answer = generateOrderNumberClarification(request.message(), history, run.runId());
                response = new AssistantChatResponse(answer, conversationId, run.runId(), List.of(), List.of());
            }
            else {
                boolean runsOrderLookup = shouldRunDeterministicOrderLookup(request.message(), history);
                List<RetrievedContext> contexts = runsOrderLookup
                        ? List.of()
                        : filterRetrievedContexts(request.message(),
                                ragService.retrieveForChat(run.runId(), request.message()));
                response = runsOrderLookup
                        ? ruleBasedAssistantChat(request, conversationId, history, run.runId(), contexts)
                        : requireLlmToolCalling()
                                ? llmAssistantChat(request, conversationId, history, run.runId(), contexts)
                                : ruleBasedAssistantChat(request, conversationId, history, run.runId(), contexts);
            }
            conversationMemoryService.appendUserMessage(conversationId, request.message());
            conversationMemoryService.appendAssistantMessage(conversationId, response.answer());
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    private boolean hasAssistantWorkflowBinding(ToolChatRequest request) {
        return request.workflowDefinition() != null || StringUtils.hasText(request.workflowDefinitionId());
    }

    private AssistantChatResponse workflowAssistantChat(ToolChatRequest request) {
        if (workflowService == null) {
            throw new BusinessException("ASSISTANT_WORKFLOW_UNAVAILABLE",
                    "Workflow-backed assistant chat is not available");
        }
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        WorkflowRunResponse workflowResponse = workflowService.run(new WorkflowRunRequest(
                request.workflowDefinition(), request.workflowDefinitionId(), request.workflowDefinitionVersion(),
                workflowInput(conversationId, request.message(), history)));
        AssistantChatResponse response = new AssistantChatResponse(
                workflowAnswer(workflowResponse.output()),
                conversationId,
                workflowResponse.runId(),
                workflowToolCalls(workflowResponse.output()),
                workflowRetrievedContext(workflowResponse.output()));
        conversationMemoryService.appendUserMessage(conversationId, request.message());
        conversationMemoryService.appendAssistantMessage(conversationId, response.answer());
        return response;
    }

    private Map<String, Object> workflowInput(String conversationId, String message, List<ConversationMessage> history) {
        Map<String, Object> input = new LinkedHashMap<>();
        List<String> currentOrderIds = OrderIdExtractor.extractAll(message);
        List<String> recentOrderIds = recentValidOrderIds(history);
        List<String> referencedOrderIds = referencedOrderIds(message, currentOrderIds, recentOrderIds);
        List<Map<String, Object>> orderToolCalls = orderToolCalls(message, referencedOrderIds);
        input.put("message", message);
        input.put("conversationId", conversationId);
        input.put("history", history.stream()
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("role", item.role().name());
                    entry.put("content", item.content() == null ? "" : item.content());
                    return entry;
                })
                .toList());
        input.put("currentOrderIds", currentOrderIds);
        input.put("recentOrderIds", recentOrderIds);
        input.put("referencedOrderIds", referencedOrderIds);
        input.put("orderLookupReady", !orderToolCalls.isEmpty());
        input.put("orderLookupCount", orderToolCalls.size());
        input.put("orderToolCalls", orderToolCalls);
        return input;
    }

    private List<String> recentValidOrderIds(List<ConversationMessage> history) {
        LinkedHashSet<String> orderIds = new LinkedHashSet<>();
        Set<String> invalidOrderIds = new LinkedHashSet<>();
        for (ConversationMessage message : history) {
            if (message.role() != ConversationRole.ASSISTANT) {
                continue;
            }
            List<String> extractedOrderIds = OrderIdExtractor.extractAll(message.content());
            if (extractedOrderIds.isEmpty()) {
                continue;
            }
            if (reportsUnavailableOrder(message.content())) {
                invalidOrderIds.addAll(extractedOrderIds);
                orderIds.removeAll(extractedOrderIds);
            }
            else if (mentionsConcreteOrder(message.content())) {
                for (String orderId : extractedOrderIds) {
                    // The latest mention wins: a later concrete confirmation clears an earlier
                    // "not found" verdict for the same order id.
                    invalidOrderIds.remove(orderId);
                    orderIds.add(orderId);
                }
            }
        }
        return List.copyOf(orderIds);
    }

    private boolean reportsUnavailableOrder(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("未能查询到")
                || lower.contains("没有查询到")
                || lower.contains("未查询到")
                || lower.contains("未查到")
                || lower.contains("没查到")
                || lower.contains("未能找到")
                || lower.contains("没有找到")
                || lower.contains("未找到")
                || lower.contains("查不到")
                || lower.contains("不存在")
                || lower.contains("无效")
                || lower.contains("not found")
                || lower.contains("invalid");
    }

    private boolean mentionsConcreteOrder(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("订单")
                || lower.contains("物流")
                || lower.contains("退款")
                || lower.contains("退货")
                || lower.contains("tracking")
                || lower.contains("order");
    }

    private List<String> referencedOrderIds(String message, List<String> currentOrderIds, List<String> recentOrderIds) {
        if (!currentOrderIds.isEmpty()) {
            return currentOrderIds;
        }
        if (recentOrderIds.isEmpty() || !referencesHistoryOrder(message)) {
            return List.of();
        }
        if (referencesTwoOrders(message)) {
            return lastOrderIds(recentOrderIds, 2);
        }
        if (referencesPluralOrders(message)) {
            return recentOrderIds;
        }
        return lastOrderIds(recentOrderIds, 1);
    }

    private boolean referencesHistoryOrder(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("刚才")
                || lower.contains("刚刚")
                || lower.contains("之前")
                || lower.contains("上面")
                || lower.contains("前面")
                || lower.contains("上述")
                || lower.contains("这两个订单")
                || lower.contains("这几个订单")
                || lower.contains("这些订单")
                || lower.contains("那两个订单")
                || lower.contains("那些订单")
                || lower.contains("我的这两个订单")
                || lower.contains("这个订单")
                || lower.contains("那个订单")
                || lower.contains("这单")
                || lower.contains("那单")
                || lower.contains("它们")
                || STANDALONE_TA_PATTERN.matcher(lower).find();
    }

    private boolean referencesTwoOrders(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("两个订单")
                || lower.contains("这两个")
                || lower.contains("那两个")
                || lower.contains("two orders");
    }

    private boolean referencesPluralOrders(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("这些订单")
                || lower.contains("这几个订单")
                || lower.contains("那些订单")
                || lower.contains("它们")
                || lower.contains("these orders")
                || lower.contains("those orders")
                || lower.contains("all orders");
    }

    private List<String> lastOrderIds(List<String> orderIds, int count) {
        int fromIndex = Math.max(0, orderIds.size() - count);
        return List.copyOf(orderIds.subList(fromIndex, orderIds.size()));
    }

    private List<Map<String, Object>> orderToolCalls(String message, List<String> orderIds) {
        return orderIds.stream()
                .map(orderId -> {
                    Map<String, Object> arguments = new LinkedHashMap<>();
                    arguments.put("toolName", "queryOrderAPI");
                    arguments.put("user_query", "Order id: " + orderId + "\nUser message: " + message);
                    return arguments;
                })
                .toList();
    }

    private String workflowAnswer(Object output) {
        if (output instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        if (output instanceof Map<?, ?> map) {
            for (String key : List.of("answer", "text", "content", "result", "message", "output")) {
                Object value = map.get(key);
                if (value instanceof String text && StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        throw new BusinessException("ASSISTANT_WORKFLOW_NO_ANSWER",
                "Workflow-backed assistant chat did not produce a textual answer");
    }

    private List<ToolExecutionLog> workflowToolCalls(Object output) {
        if (!(output instanceof Map<?, ?> map) || !(map.get("toolCalls") instanceof List<?> rawToolCalls)) {
            return List.of();
        }
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        for (Object item : rawToolCalls) {
            if (item instanceof ToolExecutionLog log) {
                toolCalls.add(log);
            }
        }
        return List.copyOf(toolCalls);
    }

    private List<RetrievedContext> workflowRetrievedContext(Object output) {
        if (!(output instanceof Map<?, ?> map) || !(map.get("retrievedContext") instanceof List<?> rawContexts)) {
            return List.of();
        }
        List<RetrievedContext> contexts = new ArrayList<>();
        for (Object item : rawContexts) {
            if (item instanceof RetrievedContext context) {
                contexts.add(context);
            }
        }
        return List.copyOf(contexts);
    }

    private boolean requireLlmToolCalling() {
        if (useLlmToolCalling()) {
            return true;
        }
        if (alibabaRuntimePolicy.isStrictMode()) {
            throw new BusinessException("ALIBABA_TOOL_CHAT_UNAVAILABLE",
                    "Alibaba LLM tool calling is required in strict mode but ChatClient is unavailable");
        }
        return false;
    }

    private boolean useLlmToolCalling() {
        return aiModelService.isModelConfigured() && chatClientProvider.getIfAvailable() != null;
    }

    private ToolChatResponse llmToolChat(ToolChatRequest request, String conversationId,
            List<ConversationMessage> history, String runId) {
        TraceStep step = traceService.startTraceStep(runId, "agent_llm_tool_chat",
                Map.of("message", request.message(), "conversationId", conversationId, "historySize", history.size()));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        try {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            List<ToolCallback> toolCallbacks = toolCallbacksFor(request.message(), runId, toolCalls);
            String answer = chatClient.prompt()
                    .system(TOOL_SYSTEM_PROMPT)
                    .messages(SpringMessageConverter.toSpringMessages(
                            historyForModel(request.message(), history), request.message()))
                    .toolCallbacks(toolCallbacks.toArray(ToolCallback[]::new))
                    .call()
                    .content();
            answer = requireTextAnswer(answer, "tool agent LLM answer");
            traceService.completeStep(step.stepId(),
                    Map.of("answer", answer, "mode", "llm", "toolCallCount", toolCalls.size()));
            return new ToolChatResponse(answer, conversationId, runId, List.copyOf(toolCalls));
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            throw ex;
        }
    }

    private AssistantChatResponse llmAssistantChat(ToolChatRequest request, String conversationId,
            List<ConversationMessage> history, String runId, List<RetrievedContext> contexts) {
        TraceStep step = traceService.startTraceStep(runId, "assistant_llm_chat",
                Map.of("message", request.message(), "conversationId", conversationId, "historySize", history.size(),
                        "contextCount", contexts.size()));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        try {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            List<ToolCallback> toolCallbacks = toolCallbacksFor(request.message(), runId, toolCalls);
            String answer = chatClient.prompt()
                    .system(ASSISTANT_SYSTEM_PROMPT)
                    .messages(SpringMessageConverter.toSpringMessages(
                            historyForModel(request.message(), history),
                            assistantUserMessage(request.message(), contexts)))
                    .toolCallbacks(toolCallbacks.toArray(ToolCallback[]::new))
                    .call()
                    .content();
            answer = requireTextAnswer(answer, "assistant LLM answer");
            traceService.completeStep(step.stepId(),
                    Map.of("answer", answer, "mode", "llm", "toolCallCount", toolCalls.size(),
                            "contextCount", contexts.size()));
            return new AssistantChatResponse(answer, conversationId, runId, List.copyOf(toolCalls), contexts);
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            throw ex;
        }
    }

    private List<ToolCallback> toolCallbacksFor(String message, String runId, List<ToolExecutionLog> toolCalls) {
        List<ToolCallback> callbacks = demoToolCallbackFactory.tracedToolCallbacks(runId, traceService, toolCalls);
        if (!isGenericOrderPolicyQuestion(message)) {
            return callbacks;
        }
        return callbacks.stream()
                .filter(callback -> !"queryOrderAPI".equals(callback.getToolDefinition().name()))
                .toList();
    }

    private ToolChatResponse ruleBasedToolChat(ToolChatRequest request, String conversationId,
            List<ConversationMessage> history, String runId) {
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        TraceStep finalStep = null;
        try {
            List<ToolPlan> plans = plan(request.message(), history, runId);
            String finalPrompt;
            if (plans.isEmpty()) {
                finalPrompt = request.message();
            }
            else {
                StringBuilder promptBuilder = new StringBuilder("User question: ")
                        .append(request.message());
                for (ToolPlan plan : plans) {
                    ToolExecutionLog log = executeTool(plan, runId);
                    toolCalls.add(log);
                    if (!log.succeeded()) {
                        throw new IllegalArgumentException(log.errorMessage());
                    }
                    promptBuilder.append("\nTool name: ")
                            .append(log.toolName())
                            .append("\nTool input: ")
                            .append(formatToolInput(log.input()))
                            .append("\nTool result: ")
                            .append(log.output());
                }
                finalPrompt = promptBuilder.toString();
            }

            finalStep = traceService.startTraceStep(runId, "agent_final_answer",
                    Map.of("prompt", finalPrompt, "toolCallCount", toolCalls.size(), "mode", "rule_based"));
            AiModelResult modelResult = aiModelService.generate(TOOL_SYSTEM_PROMPT,
                    historyForModel(request.message(), history), finalPrompt);
            String answer = requireModelAnswer(modelResult, "tool agent final answer");
            ToolChatResponse response = new ToolChatResponse(answer, conversationId, runId, toolCalls);
            traceService.completeStep(finalStep.stepId(),
                    Map.of("answer", answer, "fallback", modelResult.fallback(), "mode", "rule_based"));
            finalStep = null;
            return response;
        }
        catch (RuntimeException ex) {
            if (finalStep != null) {
                traceService.failStep(finalStep.stepId(), ex);
            }
            throw ex;
        }
    }

    private AssistantChatResponse ruleBasedAssistantChat(ToolChatRequest request, String conversationId,
            List<ConversationMessage> history, String runId, List<RetrievedContext> contexts) {
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        TraceStep finalStep = null;
        try {
            List<ToolPlan> plans = plan(request.message(), history, runId);
            for (ToolPlan plan : plans) {
                ToolExecutionLog log = executeTool(plan, runId);
                toolCalls.add(log);
                if (!log.succeeded()) {
                    throw new IllegalArgumentException(log.errorMessage());
                }
            }

            String finalPrompt = assistantFinalPrompt(request.message(), contexts, toolCalls);
            finalStep = traceService.startTraceStep(runId, "assistant_final_answer",
                    Map.of("prompt", finalPrompt, "toolCallCount", toolCalls.size(), "contextCount", contexts.size(),
                            "mode", "rule_based"));
            AiModelResult modelResult = aiModelService.generate(ASSISTANT_SYSTEM_PROMPT,
                    historyForModel(request.message(), history), finalPrompt);
            String answer = requireModelAnswer(modelResult, "assistant final answer");
            AssistantChatResponse response = new AssistantChatResponse(answer, conversationId, runId, toolCalls,
                    contexts);
            traceService.completeStep(finalStep.stepId(),
                    Map.of("answer", answer, "fallback", modelResult.fallback(), "mode", "rule_based"));
            finalStep = null;
            return response;
        }
        catch (RuntimeException ex) {
            if (finalStep != null) {
                traceService.failStep(finalStep.stepId(), ex);
            }
            throw ex;
        }
    }

    private String assistantUserMessage(String message, List<RetrievedContext> contexts) {
        return assistantFinalPrompt(message, contexts, List.of());
    }

    private String assistantFinalPrompt(String message, List<RetrievedContext> contexts,
            List<ToolExecutionLog> toolCalls) {
        StringBuilder promptBuilder = new StringBuilder("User question:\n")
                .append(message)
                .append("\n\nRetrieved knowledge base context:\n")
                .append(formatContexts(contexts));
        if (!toolCalls.isEmpty()) {
            promptBuilder.append("\n\nTool results:\n");
            for (ToolExecutionLog log : toolCalls) {
                promptBuilder.append("- ")
                        .append(log.toolName())
                        .append(" input=")
                        .append(formatToolInput(log.input()))
                        .append(" result=")
                        .append(log.output())
                        .append("\n");
            }
        }
        if (isGenericOrderPolicyQuestion(message)) {
            promptBuilder.append("\n\nRouting instruction:\n")
                    .append("This is a general policy/process question. Answer generally from the retrieved ")
                    .append("knowledge base context. Do not use prior conversation order ids, order statuses, ")
                    .append("tracking numbers, or customer-specific details unless the current user message ")
                    .append("explicitly asks about that specific order.\n");
        }
        return promptBuilder.toString();
    }

    private List<ConversationMessage> historyForModel(String message, List<ConversationMessage> history) {
        if (isGenericOrderPolicyQuestion(message)) {
            return List.of();
        }
        return history;
    }

    private List<RetrievedContext> filterRetrievedContexts(String message, List<RetrievedContext> contexts) {
        if (!isGenericOrderPolicyQuestion(message)) {
            return contexts;
        }
        return contexts.stream()
                .filter(context -> !isDemoOrderContext(context))
                .toList();
    }

    private boolean isDemoOrderContext(RetrievedContext context) {
        String title = context.title() == null ? "" : context.title().toLowerCase(Locale.ROOT);
        String snippet = context.snippet() == null ? "" : context.snippet().toLowerCase(Locale.ROOT);
        return title.startsWith("demo customer order ")
                || snippet.contains("demo customer service order record");
    }

    private String formatContexts(List<RetrievedContext> contexts) {
        if (contexts.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (RetrievedContext context : contexts) {
            builder.append("- Document ")
                    .append(context.documentId())
                    .append(" (")
                    .append(context.title())
                    .append(", score=")
                    .append(context.score())
                    .append("): ")
                    .append(context.snippet())
                    .append("\n");
        }
        return builder.toString();
    }

    private boolean shouldAskForOrderNumber(String message, List<ConversationMessage> history) {
        if (isOrderLookupTurn(message, history) && orderLookupQuery(message, history).isEmpty()) {
            return true;
        }
        return false;
    }

    private String generateOrderNumberClarification(String message, List<ConversationMessage> history, String runId) {
        Optional<String> invalidOrderIdCandidate = invalidOrderIdCandidate(message);
        String prompt = """
                User message:
                %s

                Order id validation:
                - Accepted format: explicit numeric order id with at least 8 digits.
                - Valid order id detected: no.
                - Invalid-looking order id candidate supplied by user: %s

                Ask the user for the order id in the same language as the user. If an invalid-looking candidate
                is present, mention that exact value and say it is not a valid complete order id before asking.
                """.formatted(message, invalidOrderIdCandidate.orElse("(none)"));
        TraceStep step = traceService.startTraceStep(runId, "agent_order_number_clarification",
                Map.of("message", message, "historySize", history.size(), "mode", "llm"));
        try {
            AiModelResult modelResult = aiModelService.generate(ORDER_CLARIFICATION_SYSTEM_PROMPT, history, prompt);
            String answer = requireModelAnswer(modelResult, "order number clarification");
            traceService.completeStep(step.stepId(),
                    Map.of("answer", answer, "fallback", modelResult.fallback(), "mode", "llm"));
            return answer;
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            throw ex;
        }
    }

    private boolean shouldRunDeterministicOrderLookup(String message, List<ConversationMessage> history) {
        return orderLookupQuery(message, history).isPresent();
    }

    private Optional<String> orderLookupQuery(String message, List<ConversationMessage> history) {
        Optional<String> currentOrderId = OrderIdExtractor.extractFirst(message);
        if (containsOrderIntent(message)) {
            if (currentOrderId.isPresent()) {
                return Optional.of(message);
            }
            if (isGenericOrderPolicyQuestion(message)) {
                return Optional.empty();
            }
            return latestOrderId(history)
                    .map(orderId -> message + "\nOrder id: " + orderId);
        }
        if (awaitingOrderNumber(history) && currentOrderId.isPresent()) {
            return Optional.of("Order id: " + currentOrderId.get() + "\nUser message: " + message);
        }
        return Optional.empty();
    }

    private Optional<String> invalidOrderIdCandidate(String message) {
        if (!StringUtils.hasText(message) || OrderIdExtractor.extractFirst(message).isPresent()) {
            return Optional.empty();
        }
        Matcher matcher = ORDER_ID_CLAIM_PATTERN.matcher(message);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private boolean isOrderLookupTurn(String message, List<ConversationMessage> history) {
        if (isGenericOrderPolicyQuestion(message)) {
            return false;
        }
        return containsOrderIntent(message) || awaitingOrderNumber(history);
    }

    private boolean containsOrderIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return ORDER_INTENT_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private boolean isGenericOrderPolicyQuestion(String message) {
        if (!StringUtils.hasText(message) || OrderIdExtractor.extractFirst(message).isPresent()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        boolean asksPolicy = ORDER_POLICY_KEYWORDS.stream().anyMatch(lower::contains);
        boolean referencesSpecificOrder = SPECIFIC_ORDER_REFERENCE_KEYWORDS.stream().anyMatch(lower::contains);
        return asksPolicy && !referencesSpecificOrder;
    }

    private boolean awaitingOrderNumber(List<ConversationMessage> history) {
        if (history.isEmpty()) {
            return false;
        }
        ConversationMessage last = history.getLast();
        if (last.role() != ConversationRole.ASSISTANT) {
            return false;
        }
        // Fast path: the assistant explicitly mentioned the Chinese term for order number.
        if (last.content() != null && last.content().contains("订单号")) {
            return true;
        }
        // Language-agnostic path: the assistant's last turn answered the user message right before
        // it. If on that turn the user expressed an order/after-sales intent without a valid order
        // id (and it was not a generic policy question), we asked for the order id -- regardless of
        // how the LLM phrased the request (English, "请提供单号", etc.).
        return previousUserTurnRequestedOrderId(history);
    }

    private boolean previousUserTurnRequestedOrderId(List<ConversationMessage> history) {
        int userIndex = history.size() - 2;
        if (userIndex < 0) {
            return false;
        }
        ConversationMessage previousUser = history.get(userIndex);
        if (previousUser.role() != ConversationRole.USER) {
            return false;
        }
        String content = previousUser.content();
        return containsOrderIntent(content)
                && OrderIdExtractor.extractFirst(content).isEmpty()
                && !isGenericOrderPolicyQuestion(content);
    }

    private Optional<String> latestOrderId(List<ConversationMessage> history) {
        // First collect the order ids the assistant reported as unavailable, honouring "latest
        // mention wins": a later concrete confirmation clears an earlier "not found" verdict.
        Set<String> invalidOrderIds = new LinkedHashSet<>();
        for (ConversationMessage message : history) {
            if (message.role() != ConversationRole.ASSISTANT) {
                continue;
            }
            List<String> extractedOrderIds = OrderIdExtractor.extractAll(message.content());
            if (extractedOrderIds.isEmpty()) {
                continue;
            }
            if (reportsUnavailableOrder(message.content())) {
                invalidOrderIds.addAll(extractedOrderIds);
            }
            else if (mentionsConcreteOrder(message.content())) {
                invalidOrderIds.removeAll(extractedOrderIds);
            }
        }
        // Then scan every turn -- including the user's -- from newest to oldest, so an order id the
        // user supplied is still recoverable even if the assistant never repeated it, while never
        // re-binding an id that was reported unavailable.
        for (int i = history.size() - 1; i >= 0; i--) {
            List<String> extractedOrderIds = OrderIdExtractor.extractAll(history.get(i).content());
            for (int j = extractedOrderIds.size() - 1; j >= 0; j--) {
                String orderId = extractedOrderIds.get(j);
                if (!invalidOrderIds.contains(orderId)) {
                    return Optional.of(orderId);
                }
            }
        }
        return Optional.empty();
    }

    private List<ToolPlan> plan(String message, List<ConversationMessage> history, String runId) {
        TraceStep step = traceService.startTraceStep(runId, "agent_plan", Map.of("message", message));
        String lower = message.toLowerCase(Locale.ROOT);
        List<ToolPlan> plans = new ArrayList<>();
        String expression = extractExpression(message);
        if (StringUtils.hasText(expression)) {
            plans.add(new ToolPlan("calculate", expression, null));
        }
        if (lower.contains("time") || lower.contains("current time") || message.contains("几点")
                || message.contains("时间") || message.contains("现在")) {
            plans.add(new ToolPlan("getCurrentTime", null, null));
        }
        orderLookupQuery(message, history)
                .ifPresent(query -> plans.add(new ToolPlan("queryOrderAPI", null, query)));
        traceService.completeStep(step.stepId(), Map.of("tools", plans.stream()
                .map(plan -> Map.of(
                        "toolName", nullable(plan.toolName()),
                        "expression", nullable(plan.expression()),
                        "userQuery", nullable(plan.userQuery())))
                .toList()));
        return plans;
    }

    private ToolExecutionLog executeTool(ToolPlan plan, String runId) {
        Map<String, Object> arguments = toolArguments(plan);
        TraceStep step = traceService.startTraceStep(runId, "tool_" + plan.toolName(),
                Map.of("toolName", plan.toolName(), "arguments", arguments));
        ToolExecutionLog log = toolGatewayService.execute(plan.toolName(), arguments);
        if (log.succeeded()) {
            traceService.completeStep(step.stepId(), log);
        }
        else {
            traceService.failStep(step.stepId(), new IllegalArgumentException(log.errorMessage()));
        }
        return log;
    }

    private Map<String, Object> toolArguments(ToolPlan plan) {
        if ("calculate".equals(plan.toolName())) {
            return Map.of("expression", nullable(plan.expression()));
        }
        if ("queryOrderAPI".equals(plan.toolName())) {
            return Map.of("user_query", nullable(plan.userQuery()));
        }
        return Map.of();
    }

    private String extractExpression(String message) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(message);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if ((candidate.contains("+") || candidate.contains("-") || candidate.contains("*") || candidate.contains("/"))
                    && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private String formatToolInput(Object input) {
        if (input instanceof ToolExecutionLog.CalculateInput calculateInput) {
            return "expression=" + calculateInput.expression();
        }
        if (input instanceof ToolExecutionLog.EmptyInput) {
            return "{}";
        }
        if (input instanceof ToolExecutionLog.OrderQueryInput orderQueryInput) {
            return "user_query=" + orderQueryInput.userQuery();
        }
        return String.valueOf(input);
    }

    private String requireModelAnswer(AiModelResult result, String context) {
        if (result.fallback()) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM returned a fallback answer for " + context + ": " + result.errorMessage());
        }
        return requireTextAnswer(result.answer(), context);
    }

    private String requireTextAnswer(String answer, String context) {
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM returned an empty answer for " + context);
        }
        return answer;
    }

    private record ToolPlan(String toolName, String expression, String userQuery) {
    }

}
