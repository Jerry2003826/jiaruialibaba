package com.example.agentdemo.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentdemo.chat.memory.ConversationMessage;
import com.example.agentdemo.chat.memory.ConversationRole;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

    private static final Logger log = LoggerFactory.getLogger(AiModelService.class);

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

    public AiModelResult generate(String systemPrompt, List<ConversationMessage> history, String userMessage) {
        if (!isModelConfigured()) {
            return handleUnavailableLlm("AI_DASHSCOPE_API_KEY is not configured", userMessage);
        }

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return handleUnavailableLlm("ChatClient bean is not available", userMessage);
        }

        try {
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(toSpringMessages(history, userMessage))
                    .call()
                    .content();
            return AiModelResult.ok(StringUtils.hasText(answer) ? answer : "");
        }
        catch (Exception ex) {
            if (!alibabaRuntimePolicy.isLegacyFallbackAllowed()) {
                throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                        "DashScope chat call failed: " + ex.getMessage(), ex);
            }
            log.warn("DashScope chat call failed, using fallback answer", ex);
            return legacyFallback(userMessage, ex.getMessage());
        }
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
                    if (!alibabaRuntimePolicy.isLegacyFallbackAllowed()) {
                        throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                                "DashScope streaming call failed: " + ex.getMessage(), ex);
                    }
                    log.warn("DashScope streaming call failed, using fake streaming fallback", ex);
                }
            }
            else if (!alibabaRuntimePolicy.isLegacyFallbackAllowed()) {
                throw unavailableLlmException("ChatClient bean is not available");
            }
        }
        else if (!alibabaRuntimePolicy.isLegacyFallbackAllowed()) {
            throw unavailableLlmException("AI_DASHSCOPE_API_KEY is not configured");
        }

        if (!alibabaRuntimePolicy.isLegacyFallbackAllowed()) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "DashScope streaming returned no content");
        }
        AiModelResult fallback = legacyFallback(userMessage, "Streaming model is unavailable");
        fakeStream(fallback.answer(), onChunk);
        return true;
    }

    private AiModelResult handleUnavailableLlm(String reason, String userMessage) {
        if (!alibabaRuntimePolicy.isLegacyFallbackAllowed()) {
            throw unavailableLlmException(reason);
        }
        return legacyFallback(userMessage, reason);
    }

    private BusinessException unavailableLlmException(String reason) {
        return new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                "Alibaba LLM is required but unavailable: " + reason);
    }

    private AiModelResult legacyFallback(String userMessage, String reason) {
        String answer = "AI model fallback response. Reason: " + reason
                + ". User message: " + userMessage;
        return AiModelResult.fallback(answer, reason);
    }

    private void fakeStream(String answer, Consumer<String> onChunk) {
        for (String chunk : chunks(answer)) {
            onChunk.accept(chunk);
            try {
                Thread.sleep(35);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fake streaming interrupted", ex);
            }
        }
    }

    private List<String> chunks(String answer) {
        List<String> chunks = new ArrayList<>();
        if (answer.contains(" ")) {
            String[] words = answer.split(" ");
            for (int i = 0; i < words.length; i++) {
                chunks.add(words[i] + (i == words.length - 1 ? "" : " "));
            }
            return chunks;
        }
        int size = 12;
        for (int i = 0; i < answer.length(); i += size) {
            chunks.add(answer.substring(i, Math.min(i + size, answer.length())));
        }
        return chunks;
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
