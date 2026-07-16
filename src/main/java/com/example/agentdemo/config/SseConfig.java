package com.example.agentdemo.config;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(SseConfig.SseProperties.class)
public class SseConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sse-chat-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setTaskDecorator(task -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            SecurityContext contextToPropagate = SecurityContextHolder.createEmptyContext();
            contextToPropagate.setAuthentication(authentication);
            Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
            return () -> {
                SecurityContext previousContext = SecurityContextHolder.getContext();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    SecurityContextHolder.setContext(contextToPropagate);
                    if (capturedMdc != null) {
                        MDC.setContextMap(capturedMdc);
                    }
                    else {
                        MDC.clear();
                    }
                    task.run();
                }
                finally {
                    SecurityContextHolder.setContext(previousContext);
                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    }
                    else {
                        MDC.clear();
                    }
                }
            };
        });
        executor.initialize();
        return executor;
    }

    @ConfigurationProperties(prefix = "demo.sse")
    public record SseProperties(long timeoutMs, long workflowGenerationTimeoutMs) {
        private static final long DEFAULT_TIMEOUT_MS = 120_000L;
        private static final long DEFAULT_WORKFLOW_GENERATION_TIMEOUT_MS = 960_000L;

        public SseProperties(long timeoutMs) {
            this(timeoutMs, DEFAULT_WORKFLOW_GENERATION_TIMEOUT_MS);
        }

        @ConstructorBinding
        public SseProperties {
            if (timeoutMs <= 0) {
                timeoutMs = DEFAULT_TIMEOUT_MS;
            }
            if (workflowGenerationTimeoutMs <= 0) {
                workflowGenerationTimeoutMs = DEFAULT_WORKFLOW_GENERATION_TIMEOUT_MS;
            }
        }
    }

}
