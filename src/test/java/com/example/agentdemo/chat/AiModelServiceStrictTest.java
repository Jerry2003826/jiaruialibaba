package com.example.agentdemo.chat;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelServiceStrictTest {

    @Test
    void throwsWhenStrictModeEnabledAndApiKeyMissing() {
        MockEnvironment environment = new MockEnvironment();
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        AiModelService service = new AiModelService(chatClientProvider, environment, TestAlibabaPolicies.strictMode());

        assertThatThrownBy(() -> service.generate("system", "hello"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_NOT_CONFIGURED");
    }

    @Test
    void throwsWhenLegacyFallbackPolicyAllowsButApiKeyMissing() {
        MockEnvironment environment = new MockEnvironment();
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        AiModelService service = new AiModelService(chatClientProvider, environment,
                TestAlibabaPolicies.legacyFallbackAllowed());

        assertThatThrownBy(() -> service.generate("system", "hello"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_NOT_CONFIGURED");
    }

    @Test
    void throwsWhenStrictModeEnabledAndStreamingUnavailable() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        AiModelService service = new AiModelService(chatClientProvider, environment, TestAlibabaPolicies.strictMode());

        assertThatThrownBy(() -> service.stream("system", "hello", chunk -> {
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_NOT_CONFIGURED");
    }

    @Test
    void throwsWhenStrictModeEnabledAndStreamingReturnsNoContent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(org.mockito.ArgumentMatchers.anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(reactor.core.publisher.Flux.empty());

        AiModelService service = new AiModelService(chatClientProvider, environment, TestAlibabaPolicies.strictMode());

        assertThatThrownBy(() -> service.stream("system", "hello", chunk -> {
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void timesOutStreamingWithConfiguredTimeout() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        environment.setProperty("demo.ai.stream-timeout-ms", "10");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(org.mockito.ArgumentMatchers.anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(reactor.core.publisher.Flux.never());

        AiModelService service = new AiModelService(chatClientProvider, environment, TestAlibabaPolicies.strictMode());

        assertThatThrownBy(() -> service.stream("system", "hello", chunk -> {
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void timesOutWhenStreamStopsEmittingAfterReturningContent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        environment.setProperty("demo.ai.stream-timeout-ms", "300");
        environment.setProperty("demo.ai.stream-idle-timeout-ms", "10");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(org.mockito.ArgumentMatchers.anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just("{\"complete\":true}"),
                reactor.core.publisher.Flux.never()));
        AiModelService service = new AiModelService(chatClientProvider, environment, TestAlibabaPolicies.strictMode());
        long startedNanos = System.nanoTime();

        assertThatThrownBy(() -> service.stream("system", "hello", chunk -> {
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedNanos))
                .isLessThan(java.time.Duration.ofMillis(200));
    }

    @Test
    void stopsStreamingWhenCompletionPredicateMatchesWithoutWaitingForProviderCompletion() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        environment.setProperty("demo.ai.stream-timeout-ms", "300");
        environment.setProperty("demo.ai.stream-idle-timeout-ms", "250");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(org.mockito.ArgumentMatchers.anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just("{\"complete\":true}"),
                reactor.core.publisher.Flux.never()));
        AiModelService service = new AiModelService(chatClientProvider, environment, TestAlibabaPolicies.strictMode());
        StringBuilder received = new StringBuilder();
        long startedNanos = System.nanoTime();

        service.streamUntilComplete(
                "system",
                "hello",
                received::append,
                () -> received.toString().equals("{\"complete\":true}"));

        assertThat(received).hasToString("{\"complete\":true}");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedNanos))
                .isLessThan(java.time.Duration.ofMillis(200));
    }

    @Test
    void throwsWhenFallbackDisabledAndRuntimeCallFails() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new IllegalStateException("upstream unavailable"));

        AiModelService service = new AiModelService(chatClientProvider, environment,
                TestAlibabaPolicies.fallbackDisabled());

        assertThatThrownBy(() -> service.generate("system", "hello"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void throwsWhenLegacyFallbackPolicyAllowsButRuntimeCallFails() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new IllegalStateException("upstream unavailable"));

        AiModelService service = new AiModelService(chatClientProvider, environment,
                TestAlibabaPolicies.legacyFallbackAllowed());

        assertThatThrownBy(() -> service.generate("system", "hello"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void returnsTokenUsageFromChatResponseMetadata() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        environment.setProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("tracked answer"))),
                ChatResponseMetadata.builder()
                        .model("qwen-plus")
                        .usage(new DefaultUsage(11, 7, 18, Map.of("input_tokens", 11, "output_tokens", 7)))
                        .build());
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(response);

        AiModelService service = new AiModelService(chatClientProvider, environment,
                TestAlibabaPolicies.fallbackDisabled());

        AiModelResult result = service.generate("system", "hello");

        assertThat(result.answer()).isEqualTo("tracked answer");
        assertThat(result.tokenUsage()).isNotNull();
        assertThat(result.tokenUsage().provider()).isEqualTo("dashscope");
        assertThat(result.tokenUsage().model()).isEqualTo("qwen-plus");
        assertThat(result.tokenUsage().promptTokens()).isEqualTo(11);
        assertThat(result.tokenUsage().completionTokens()).isEqualTo(7);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(18);
        assertThat(result.tokenUsage().nativeUsage()).isEqualTo(Map.of("input_tokens", 11, "output_tokens", 7));
    }

}
