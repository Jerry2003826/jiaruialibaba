package com.example.agentdemo.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import static com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants.MULTIMODAL_GENERATION_RESTFUL_URL;

@Configuration
public class AiConfig {

    private static final String API_V1_PREFIX = "/api/v1";
    private static final String QWEN_3_7_PLUS = "qwen3.7-plus";

    @Bean
    @Conditional(DashScopeApiKeyPresentCondition.class)
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(Environment environment) {
        String apiKey = environment.getRequiredProperty("spring.ai.dashscope.api-key");
        String model = environment.getProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");
        String baseUrl = normalizeBaseUrl(environment.getProperty("spring.ai.dashscope.base-url"));
        String completionsPath = completionsPath(environment, model, baseUrl);

        DashScopeApi.Builder dashScopeApiBuilder = DashScopeApi.builder().apiKey(apiKey);
        if (StringUtils.hasText(baseUrl)) {
            dashScopeApiBuilder.baseUrl(baseUrl);
        }
        if (StringUtils.hasText(completionsPath)) {
            dashScopeApiBuilder.completionsPath(completionsPath);
        }
        DashScopeApi dashScopeApi = dashScopeApiBuilder.build();
        DashScopeChatOptions chatOptions = DashScopeChatOptions.builder()
                .model(model)
                .incrementalOutput(true)
                .build();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(chatOptions)
                .build();
        return ChatClient.builder(chatModel).build();
    }

    private static String completionsPath(Environment environment, String model, String baseUrl) {
        String configuredPath = environment.getProperty("spring.ai.dashscope.chat.completions-path");
        if (!StringUtils.hasText(configuredPath) && QWEN_3_7_PLUS.equalsIgnoreCase(model)) {
            configuredPath = MULTIMODAL_GENERATION_RESTFUL_URL;
        }
        return normalizePath(configuredPath, baseUrl);
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
            String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
            return StringUtils.hasText(apiKey) && !"your-api-key".equals(apiKey);
        }

    }

}
