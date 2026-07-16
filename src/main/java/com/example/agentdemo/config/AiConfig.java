package com.example.agentdemo.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

import static com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants.MULTIMODAL_GENERATION_RESTFUL_URL;

@Configuration
public class AiConfig {

    private static final String API_V1_PREFIX = "/api/v1";
    private static final String DASHSCOPE_PROTOCOL = "dashscope";
    private static final String OPENAI_COMPATIBLE_PROTOCOL = "openai-compatible";

    @Bean
    @Conditional(DashScopeApiKeyPresentCondition.class)
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(Environment environment) {
        String model = environment.getProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");
        String baseUrl = normalizeBaseUrl(environment.getProperty("spring.ai.dashscope.base-url"));
        String completionsPath = completionsPath(environment, model, baseUrl);
        String protocol = environment.getProperty("demo.ai.chat-protocol", DASHSCOPE_PROTOCOL).trim();
        if (OPENAI_COMPATIBLE_PROTOCOL.equalsIgnoreCase(protocol)) {
            return openAiCompatibleChatClient(environment, model, baseUrl, completionsPath);
        }
        if (!DASHSCOPE_PROTOCOL.equalsIgnoreCase(protocol)) {
            throw new IllegalStateException("Unsupported demo.ai.chat-protocol: " + protocol);
        }

        String apiKey = environment.getRequiredProperty("spring.ai.dashscope.api-key");
        boolean enableThinking = environment.getProperty("demo.ai.enable-thinking", Boolean.class, false);

        DashScopeApi.Builder dashScopeApiBuilder = DashScopeApi.builder().apiKey(apiKey);
        if (StringUtils.hasText(baseUrl)) {
            dashScopeApiBuilder.baseUrl(baseUrl);
        }
        if (StringUtils.hasText(completionsPath)) {
            dashScopeApiBuilder.completionsPath(completionsPath);
        }
        DashScopeApi dashScopeApi = dashScopeApiBuilder.build();
        DashScopeChatOptions.DashScopeChatOptionsBuilder chatOptionsBuilder = DashScopeChatOptions.builder()
                .model(model)
                .incrementalOutput(true);
        if (enableThinking) {
            chatOptionsBuilder.enableThinking(true);
        }
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(chatOptionsBuilder.build())
                .build();
        return ChatClient.builder(chatModel).build();
    }

    private ChatClient openAiCompatibleChatClient(Environment environment, String model, String baseUrl,
            String completionsPath) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("spring.ai.dashscope.base-url is required for openai-compatible chat");
        }
        String apiKey = environment.getProperty("demo.ai.chat-api-key",
                environment.getProperty("spring.ai.dashscope.api-key"));
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("demo.ai.chat-api-key is required for openai-compatible chat");
        }
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey);
        if (StringUtils.hasText(completionsPath)) {
            apiBuilder.completionsPath(completionsPath);
        }
        boolean enableThinking = environment.getProperty("demo.ai.enable-thinking", Boolean.class, false);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .extraBody(Map.of("enable_thinking", enableThinking))
                        .build())
                .build();
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    @Conditional(DashScopeApiKeyPresentCondition.class)
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(Environment environment, RagProperties ragProperties) {
        String apiKey = environment.getRequiredProperty("spring.ai.dashscope.api-key");
        String baseUrl = normalizeBaseUrl(ragProperties.getAi().getEmbeddingBaseUrl());
        DashScopeApi.Builder dashScopeApiBuilder = DashScopeApi.builder().apiKey(apiKey);
        if (StringUtils.hasText(baseUrl)) {
            dashScopeApiBuilder.baseUrl(baseUrl);
        }
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .model(ragProperties.getAi().getEmbeddingModel())
                .dimensions(ragProperties.getAi().getEmbeddingDimension())
                .build();
        return DashScopeEmbeddingModel.builder()
                .dashScopeApi(dashScopeApiBuilder.build())
                .metadataMode(MetadataMode.NONE)
                .defaultOptions(options)
                .build();
    }

    private static String completionsPath(Environment environment, String model, String baseUrl) {
        String configuredPath = environment.getProperty("spring.ai.dashscope.chat.completions-path");
        if (!StringUtils.hasText(configuredPath) && isQwen37MultimodalModel(model)) {
            configuredPath = MULTIMODAL_GENERATION_RESTFUL_URL;
        }
        return normalizePath(configuredPath, baseUrl);
    }

    static boolean isQwen37MultimodalModel(String model) {
        if (!StringUtils.hasText(model)) {
            return false;
        }
        return "qwen3.7-plus".equalsIgnoreCase(model.trim());
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizePath(String path, String baseUrl) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (StringUtils.hasText(baseUrl) && baseUrl.endsWith(API_V1_PREFIX)
                && normalized.startsWith(API_V1_PREFIX + "/")) {
            return normalized.substring(API_V1_PREFIX.length());
        }
        return normalized;
    }

    static final class DashScopeApiKeyPresentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            String protocol = environment.getProperty("demo.ai.chat-protocol", DASHSCOPE_PROTOCOL);
            String apiKey = OPENAI_COMPATIBLE_PROTOCOL.equalsIgnoreCase(protocol)
                    ? environment.getProperty("demo.ai.chat-api-key")
                    : environment.getProperty("spring.ai.dashscope.api-key");
            return StringUtils.hasText(apiKey) && !"your-api-key".equals(apiKey);
        }

    }

}
