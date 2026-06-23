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

@Configuration
public class AiConfig {

    @Bean
    @Conditional(DashScopeApiKeyPresentCondition.class)
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(Environment environment) {
        String apiKey = environment.getRequiredProperty("spring.ai.dashscope.api-key");
        String model = environment.getProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");

        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
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

    static final class DashScopeApiKeyPresentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
            return StringUtils.hasText(apiKey) && !"your-api-key".equals(apiKey);
        }

    }

}
