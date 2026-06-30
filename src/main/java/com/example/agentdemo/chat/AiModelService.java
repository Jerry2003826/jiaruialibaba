package com.example.agentdemo.chat;

import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.chat.memory.ConversationRole;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class AiModelService {

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
                    .messages(toSpringMessages(history, userMessage));
            String requestedModel = StringUtils.hasText(modelOverride) ? modelOverride : modelName();
            if (StringUtils.hasText(modelOverride)) {
                request = request.options(ChatOptions.builder().model(modelOverride).build());
            }
            ChatResponse response = request.call().chatResponse();
            String answer = extractAnswer(response);
            return AiModelResult.ok(StringUtils.hasText(answer) ? answer : "",
                    extractTokenUsage(response, requestedModel));
        }
        catch (Exception ex) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "DashScope chat call failed: " + ex.getMessage(), ex);
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

    public boolean stream(String systemPrompt, String userMessage, Consumer<String> onChunk) {
        return stream(systemPrompt, List.of(), userMessage, onChunk);
    }

    public boolean stream(String systemPrompt, List<ConversationMessage> history, String userMessage,
            Consumer<String> onChunk) {
        if (isModelConfigured()) {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            if (chatClient != null) {
                try {
                    AtomicInteger chunks = new AtomicInteger();
                    chatClient.prompt()
                            .system(systemPrompt)
                            .messages(toSpringMessages(history, userMessage))
                            .stream()
                            .content()
                            .doOnNext(chunk -> {
                                if (StringUtils.hasText(chunk)) {
                                    chunks.incrementAndGet();
                                    onChunk.accept(chunk);
                                }
                            })
                            .blockLast(Duration.ofSeconds(90));
                    if (chunks.get() > 0) {
                        return false;
                    }
                }
                catch (Exception ex) {
                    throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                            "DashScope streaming call failed: " + ex.getMessage(), ex);
                }
            }
            else {
                throw unavailableLlmException("ChatClient bean is not available");
            }
        }
        else {
            throw unavailableLlmException("AI_DASHSCOPE_API_KEY is not configured");
        }

        throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                "DashScope streaming returned no content");
    }

    private BusinessException unavailableLlmException(String reason) {
        return new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                "Alibaba LLM is required but unavailable: " + reason);
    }

    private List<Message> toSpringMessages(List<ConversationMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        if (history != null) {
            for (ConversationMessage message : history) {
                if (message.role() == ConversationRole.USER) {
                    messages.add(new UserMessage(message.content()));
                }
                else {
                    messages.add(new AssistantMessage(message.content()));
                }
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }

}
