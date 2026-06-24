package com.example.agentdemo.agent;

import com.example.agentdemo.agent.dto.ToolChatRequest;
import com.example.agentdemo.agent.dto.ToolChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
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
            You are an agent finalizer. Answer the user using the provided tool result.
            Keep the answer short. Do not claim that a tool was used if no tool result is provided.
            """;

    private final ToolGatewayService toolGatewayService;
    private final AiModelService aiModelService;
    private final TraceService traceService;

    public ToolCallingAgentService(ToolGatewayService toolGatewayService, AiModelService aiModelService,
            TraceService traceService) {
        this.toolGatewayService = toolGatewayService;
        this.aiModelService = aiModelService;
        this.traceService = traceService;
    }

    public ToolChatResponse toolChat(ToolChatRequest request) {
        RunEntity run = traceService.createRun(RunType.TOOL_CHAT, request);
        List<ToolExecutionLog> toolCalls = new ArrayList<>();
        RunStepEntity finalStep = null;
        try {
            List<ToolPlan> plans = plan(request.message(), run.getRunId());
            String finalPrompt;
            if (plans.isEmpty()) {
                finalPrompt = request.message();
            }
            else {
                StringBuilder promptBuilder = new StringBuilder("User question: ")
                        .append(request.message());
                for (ToolPlan plan : plans) {
                    ToolExecutionLog log = executeTool(plan, run.getRunId());
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

            finalStep = traceService.startStep(run.getRunId(), "agent_final_answer",
                    Map.of("prompt", finalPrompt, "toolCallCount", toolCalls.size()));
            AiModelResult modelResult = aiModelService.generate(TOOL_SYSTEM_PROMPT, finalPrompt);
            String answer = modelResult.fallback() && !toolCalls.isEmpty()
                    ? "Tool results: " + toolCalls.stream()
                            .map(log -> log.toolName() + "=" + log.output())
                            .toList()
                    : modelResult.answer();
            ToolChatResponse response = new ToolChatResponse(answer, run.getRunId(), toolCalls);
            traceService.completeStep(finalStep.getStepId(),
                    Map.of("answer", answer, "fallback", modelResult.fallback()));
            finalStep = null;
            traceService.markRunSucceeded(run.getRunId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            if (finalStep != null) {
                traceService.failStep(finalStep.getStepId(), ex);
            }
            traceService.markRunFailed(run.getRunId(), ex);
            throw ex;
        }
    }

    private List<ToolPlan> plan(String message, String runId) {
        RunStepEntity step = traceService.startStep(runId, "agent_plan", Map.of("message", message));
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
        traceService.completeStep(step.getStepId(), Map.of("tools", plans.stream()
                .map(plan -> Map.of("toolName", nullable(plan.toolName()), "expression", nullable(plan.expression())))
                .toList()));
        return plans;
    }

    private ToolExecutionLog executeTool(ToolPlan plan, String runId) {
        Map<String, Object> arguments = toolArguments(plan);
        RunStepEntity step = traceService.startStep(runId, "tool_" + plan.toolName(),
                Map.of("toolName", plan.toolName(), "arguments", arguments));
        ToolExecutionLog log = toolGatewayService.execute(plan.toolName(), arguments);
        if (log.succeeded()) {
            traceService.completeStep(step.getStepId(), log);
        }
        else {
            traceService.failStep(step.getStepId(), new IllegalArgumentException(log.errorMessage()));
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
