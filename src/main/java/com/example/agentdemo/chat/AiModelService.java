package com.example.agentdemo.chat;

import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.chat.memory.SpringMessageConverter;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class AiModelService {

    private static final long DEFAULT_STREAM_TIMEOUT_MS = 90_000L;
    private static final int DEFAULT_MAX_TOKENS_PER_CALL = 32_000;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final Environment environment;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;

    public AiModelService(ObjectProvider<ChatClient> chatClientProvider, Environment environment,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this.chatClientProvider = chatClientProvider;
        this.environment = environment;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
    }

    public boolean isModelConfigured() {
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
        return StringUtils.hasText(apiKey) && !Objects.equals(apiKey, "your-api-key");
    }

    public boolean isChatClientAvailable() {
        return chatClientProvider.getIfAvailable() != null;
    }

    public String modelName() {
        return environment.getProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");
    }

    public AiModelResult generate(String systemPrompt, String userMessage) {
        return generate(systemPrompt, List.of(), userMessage);
    }

    public AiModelResult generateWithModel(String systemPrompt, String userMessage, String modelOverride) {
        return generate(systemPrompt, List.of(), userMessage, modelOverride);
    }

    public AiModelResult generate(String systemPrompt, List<ConversationMessage> history, String userMessage) {
        return generate(systemPrompt, history, userMessage, null);
    }

    private AiModelResult generate(String systemPrompt, List<ConversationMessage> history, String userMessage,
            String modelOverride) {
        if (!isModelConfigured()) {
            throw unavailableLlmException("AI_DASHSCOPE_API_KEY is not configured");
        }

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw unavailableLlmException("ChatClient bean is not available");
        }

        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(SpringMessageConverter.toSpringMessages(history, userMessage));
            String requestedModel = StringUtils.hasText(modelOverride) ? modelOverride : modelName();
            if (StringUtils.hasText(modelOverride)) {
                request = request.options(ChatOptions.builder().model(modelOverride).build());
            }
            ChatResponse response = request.call().chatResponse();
            String answer = extractAnswer(response);
            TokenUsage tokenUsage = extractTokenUsage(response, requestedModel);
            enforceTokenBudget(tokenUsage);
            return AiModelResult.ok(StringUtils.hasText(answer) ? answer : "", tokenUsage);
        }
        catch (BusinessException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "DashScope chat call failed", ex);
        }
    }

    private String extractAnswer(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private TokenUsage extractTokenUsage(ChatResponse response, String requestedModel) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return null;
        }
        String model = StringUtils.hasText(metadata.getModel()) ? metadata.getModel() : requestedModel;
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        Integer totalTokens = usage.getTotalTokens();
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        return new TokenUsage("dashscope", model, promptTokens, completionTokens, totalTokens,
                usage.getNativeUsage());
    }

    public void stream(String systemPrompt, String userMessage, Consumer<String> onChunk) {
        stream(systemPrompt, List.of(), userMessage, onChunk);
    }

    public void stream(String systemPrompt, List<ConversationMessage> history, String userMessage,
            Consumer<String> onChunk) {
        if (!isModelConfigured()) {
            throw unavailableLlmException("AI_DASHSCOPE_API_KEY is not configured");
        }
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw unavailableLlmException("ChatClient bean is not available");
        }
        AtomicInteger chunks = new AtomicInteger();
        try {
            chatClient.prompt()
                    .system(systemPrompt)
                    .messages(SpringMessageConverter.toSpringMessages(history, userMessage))
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        if (StringUtils.hasText(chunk)) {
                            chunks.incrementAndGet();
                            onChunk.accept(chunk);
                        }
                    })
                    .blockLast(streamTimeout());
        }
        catch (Exception ex) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "DashScope streaming call failed", ex);
        }
        if (chunks.get() == 0) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "DashScope streaming returned no content");
        }
    }

    private Duration streamTimeout() {
        Long timeoutMs = environment.getProperty("demo.ai.stream-timeout-ms", Long.class,
                DEFAULT_STREAM_TIMEOUT_MS);
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = DEFAULT_STREAM_TIMEOUT_MS;
        }
        return Duration.ofMillis(timeoutMs);
    }

    private void enforceTokenBudget(TokenUsage tokenUsage) {
        if (tokenUsage == null || tokenUsage.totalTokens() == null) {
            return;
        }
        Integer maxTokens = environment.getProperty("demo.ai.max-tokens-per-call", Integer.class,
                DEFAULT_MAX_TOKENS_PER_CALL);
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS_PER_CALL;
        }
        if (tokenUsage.totalTokens() > maxTokens) {
            throw new BusinessException("ALIBABA_LLM_TOKEN_BUDGET_EXCEEDED",
                    "DashScope chat token usage exceeded the configured per-call budget");
        }
    }

    private BusinessException unavailableLlmException(String reason) {
        return new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                "Alibaba LLM is required but unavailable: " + reason);
    }

}
