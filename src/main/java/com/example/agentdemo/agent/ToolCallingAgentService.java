package com.example.agentdemo.agent;

import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.chat.memory.ConversationRole;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolCallingAgentService {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("([0-9(][0-9+\\-*/().\\s]*[0-9)])");

    private static final String TOOL_SYSTEM_PROMPT = """
            You are a helpful agent with access to tools.
            Use tools when they help answer the user question accurately.
            Keep the final answer concise.
            """;

    private final ToolGatewayService toolGatewayService;
    private final DemoToolCallbackFactory demoToolCallbackFactory;
    private final AiModelService aiModelService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ConversationMemoryService conversationMemoryService;
    private final TraceService traceService;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;

    public ToolCallingAgentService(ToolGatewayService toolGatewayService,
            DemoToolCallbackFactory demoToolCallbackFactory, AiModelService aiModelService,
            ObjectProvider<ChatClient> chatClientProvider, ConversationMemoryService conversationMemoryService,
            TraceService traceService, AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this.toolGatewayService = toolGatewayService;
        this.demoToolCallbackFactory = demoToolCallbackFactory;
        this.aiModelService = aiModelService;
        this.chatClientProvider = chatClientProvider;
        this.conversationMemoryService = conversationMemoryService;
        this.traceService = traceService;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
    }

    public ToolChatResponse toolChat(ToolChatRequest request) {
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        ToolChatRequest traceRequest = new ToolChatRequest(conversationId, request.message());
        TraceRun run = traceService.startRun(RunType.TOOL_CHAT, traceRequest);
        try {
            ToolChatResponse response = requireLlmToolCalling()
                    ? llmToolChat(request, conversationId, history, run.runId())
                    : ruleBasedToolChat(request, conversationId, history, run.runId());
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
            List<ToolCallback> toolCallbacks = demoToolCallbackFactory.tracedToolCallbacks(runId, traceService, toolCalls);
            String answer = chatClient.prompt()
                    .system(TOOL_SYSTEM_PROMPT)
                    .messages(toSpringMessages(history, request.message()))
                    .toolCallbacks(toolCallbacks.toArray(ToolCallback[]::new))
                    .call()
                    .content();
            traceService.completeStep(step.stepId(),
                    Map.of("answer", answer, "mode", "llm", "toolCallCount", toolCalls.size()));
            return new ToolChatResponse(answer, conversationId, runId, List.copyOf(toolCalls));
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            throw ex;
        }
    }

    private ToolChatResponse ruleBasedToolChat(ToolChatRequest request, String conversationId,
            List<ConversationMessage> history, String runId) {
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        TraceStep finalStep = null;
        try {
            List<ToolPlan> plans = plan(request.message(), runId);
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
            AiModelResult modelResult = aiModelService.generate(TOOL_SYSTEM_PROMPT, history, finalPrompt);
            String answer = modelResult.fallback() && !toolCalls.isEmpty()
                    ? "Tool results: " + toolCalls.stream()
                            .map(log -> log.toolName() + "=" + log.output())
                            .toList()
                    : modelResult.answer();
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

    private List<Message> toSpringMessages(List<ConversationMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        for (ConversationMessage message : history) {
            if (message.role() == ConversationRole.USER) {
                messages.add(new UserMessage(message.content()));
            }
            else {
                messages.add(new AssistantMessage(message.content()));
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    private List<ToolPlan> plan(String message, String runId) {
        TraceStep step = traceService.startTraceStep(runId, "agent_plan", Map.of("message", message));
        String lower = message.toLowerCase(Locale.ROOT);
        List<ToolPlan> plans = new ArrayList<>();
        String expression = extractExpression(message);
        if (StringUtils.hasText(expression)) {
            plans.add(new ToolPlan("calculate", expression));
        }
        if (lower.contains("time") || lower.contains("current time") || message.contains("几点")
                || message.contains("时间") || message.contains("现在")) {
            plans.add(new ToolPlan("getCurrentTime", null));
        }
        traceService.completeStep(step.stepId(), Map.of("tools", plans.stream()
                .map(plan -> Map.of("toolName", nullable(plan.toolName()), "expression", nullable(plan.expression())))
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
        return String.valueOf(input);
    }

    private record ToolPlan(String toolName, String expression) {
    }

}
