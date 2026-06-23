package com.example.agentdemo.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean fallbackEnabled;

    public AiModelService(ObjectProvider<ChatClient> chatClientProvider, Environment environment,
            @Value("${demo.ai.fallback-enabled:true}") boolean fallbackEnabled) {
        this.chatClientProvider = chatClientProvider;
        this.environment = environment;
        this.fallbackEnabled = fallbackEnabled;
    }

    public boolean isModelConfigured() {
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
        return StringUtils.hasText(apiKey) && !Objects.equals(apiKey, "your-api-key");
    }

    public String modelName() {
        return environment.getProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");
    }

    public AiModelResult generate(String systemPrompt, String userMessage) {
        if (!isModelConfigured()) {
            return fallback(userMessage, "AI_DASHSCOPE_API_KEY is not configured");
        }

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return fallback(userMessage, "ChatClient bean is not available");
        }

        try {
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();
            return AiModelResult.ok(StringUtils.hasText(answer) ? answer : "");
        }
        catch (Exception ex) {
            log.warn("DashScope chat call failed, using fallback answer", ex);
            return fallback(userMessage, ex.getMessage());
        }
    }

    public boolean stream(String systemPrompt, String userMessage, Consumer<String> onChunk) {
        if (isModelConfigured()) {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            if (chatClient != null) {
                try {
                    AtomicInteger chunks = new AtomicInteger();
                    chatClient.prompt()
                            .system(systemPrompt)
                            .user(userMessage)
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
                    log.warn("DashScope streaming call failed, using fake streaming fallback", ex);
                }
            }
        }

        AiModelResult fallback = fallback(userMessage, "Streaming model is unavailable");
        fakeStream(fallback.answer(), onChunk);
        return true;
    }

    private AiModelResult fallback(String userMessage, String reason) {
        if (!fallbackEnabled) {
            throw new IllegalStateException(reason);
        }
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

}
