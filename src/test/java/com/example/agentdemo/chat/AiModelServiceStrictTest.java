package com.example.agentdemo.chat;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

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

}
