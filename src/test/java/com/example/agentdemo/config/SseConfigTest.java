package com.example.agentdemo.config;

import com.example.agentdemo.security.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SseConfigTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void sseExecutorPropagatesSecurityContext() throws Exception {
        ThreadPoolTaskExecutor executor = new SseConfig().sseExecutor();
        try {
            setOwner("owner-a");

            CompletableFuture<String> owner = new CompletableFuture<>();
            executor.execute(() -> owner.complete(SecurityIdentity.currentOwnerId()));

            assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("owner-a");
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void sseExecutorRestoresSecurityContextBetweenTasks() throws Exception {
        ThreadPoolTaskExecutor executor = singleThreadExecutor();
        try {
            setOwner("owner-a");
            CompletableFuture<String> firstOwner = new CompletableFuture<>();
            executor.execute(() -> firstOwner.complete(SecurityIdentity.currentOwnerId()));
            assertThat(firstOwner.get(5, TimeUnit.SECONDS)).isEqualTo("owner-a");

            setOwner("owner-b");
            CompletableFuture<String> secondOwner = new CompletableFuture<>();
            executor.execute(() -> secondOwner.complete(SecurityIdentity.currentOwnerId()));

            assertThat(secondOwner.get(5, TimeUnit.SECONDS)).isEqualTo("owner-b");
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void sseExecutorPropagatesAndRestoresMdc() throws Exception {
        ThreadPoolTaskExecutor executor = singleThreadExecutor();
        try {
            MDC.put("requestId", "req-123");
            CompletableFuture<String> firstRequestId = new CompletableFuture<>();
            executor.execute(() -> firstRequestId.complete(MDC.get("requestId")));
            assertThat(firstRequestId.get(5, TimeUnit.SECONDS)).isEqualTo("req-123");

            MDC.clear();
            CompletableFuture<String> secondRequestId = new CompletableFuture<>();
            executor.execute(() -> secondRequestId.complete(MDC.get("requestId")));

            assertThat(secondRequestId.get(5, TimeUnit.SECONDS)).isNull();
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void ssePropertiesDefaultWorkflowGenerationTimeoutExceedsEvaluationBudget() {
        SseConfig.SseProperties properties = new SseConfig.SseProperties(120_000L, 0L);

        assertThat(properties.timeoutMs()).isEqualTo(120_000L);
        assertThat(properties.workflowGenerationTimeoutMs()).isEqualTo(960_000L);
        assertThat(properties.workflowGenerationTimeoutMs()).isGreaterThan(900_000L);
    }

    private ThreadPoolTaskExecutor singleThreadExecutor() {
        ThreadPoolTaskExecutor executor = new SseConfig().sseExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        return executor;
    }

    private void setOwner(String ownerId) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(ownerId, "n/a", List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

}
