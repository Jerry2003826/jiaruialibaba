package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.memory.ConversationMemoryService;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.example.agentdemo.usage.UsageRecordingService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class ChatAppRunner {

    static final String DEFAULT_CHAT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer clearly and concisely and do not invent external state.
            """;

    private final AiModelService aiModelService;
    private final ConversationMemoryService conversationMemoryService;
    private final TraceService traceService;
    private final UsageRecordingService usageRecordingService;
    private final AppKnowledgeContextService knowledgeContextService;

    public ChatAppRunner(AiModelService aiModelService, ConversationMemoryService conversationMemoryService,
            TraceService traceService, UsageRecordingService usageRecordingService,
            AppKnowledgeContextService knowledgeContextService) {
        this.aiModelService = aiModelService;
        this.conversationMemoryService = conversationMemoryService;
        this.traceService = traceService;
        this.usageRecordingService = usageRecordingService;
        this.knowledgeContextService = knowledgeContextService;
    }

    public AppChatResponse chat(String appId, AppSnapshot snapshot, AppChatRequest request) {
        RunContext.setAppId(appId);
        try {
            return doChat(appId, snapshot, request);
        }
        finally {
            RunContext.clear();
        }
    }

    private AppChatResponse doChat(String appId, AppSnapshot snapshot, AppChatRequest request) {
        AppConfig config = config(snapshot);
        String conversationId = conversationMemoryService.resolveConversationId(request.conversationId());
        List<ConversationMessage> history = historyFor(config, conversationId);
        TraceRun run = traceService.startRun(RunType.CHAT,
                Map.of("appId", appId, "conversationId", conversationId, "message", request.message()));
        TraceStep step = traceService.startTraceStep(run.runId(), "app_chat",
                Map.of("appId", appId, "conversationId", conversationId, "historySize", history.size()));
        try {
            List<Citation> citations = knowledgeContextService.retrieve(config, request.message(), run.runId());
            String userMessage = knowledgeContextService.augmentMessage(request.message(), citations);
            AiModelResult result = aiModelService.generate(systemPrompt(config), history, userMessage, config.model());
            String answer = requireAnswer(result);
            usageRecordingService.record(run.runId(), appId, result.tokenUsage());
            conversationMemoryService.appendUserMessage(conversationId, request.message());
            conversationMemoryService.appendAssistantMessage(conversationId, answer);
            AppChatResponse response = new AppChatResponse(answer, conversationId, run.runId(), appId, citations);
            traceService.completeStep(step.stepId(), Map.of("answer", answer, "citationCount", citations.size()));
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    AppConfig config(AppSnapshot snapshot) {
        return snapshot.config() == null ? AppConfig.empty() : snapshot.config();
    }

    List<ConversationMessage> historyFor(AppConfig config, String conversationId) {
        if (!config.memoryEnabledOrDefault()) {
            return List.of();
        }
        List<ConversationMessage> history = conversationMemoryService.loadRecentMessages(conversationId);
        Integer max = config.memoryMaxMessages();
        if (max != null && max >= 0 && history.size() > max) {
            return history.subList(history.size() - max, history.size());
        }
        return history;
    }

    String systemPrompt(AppConfig config) {
        return StringUtils.hasText(config.systemPrompt()) ? config.systemPrompt() : DEFAULT_CHAT_SYSTEM_PROMPT;
    }

    String requireAnswer(AiModelResult result) {
        if (result.fallback() || !StringUtils.hasText(result.answer())) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM is required for app chat and returned no usable answer");
        }
        return result.answer();
    }

}
