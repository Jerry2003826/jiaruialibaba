package com.example.agentdemo.config;

import com.example.agentdemo.rag.DocumentRetriever;
import com.example.agentdemo.rag.KeywordDocumentRetriever;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlibabaStackValidatorTest {

    @Test
    void failsFastWhenStrictModeEnabledAndStackIsIncomplete() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "");
        AlibabaStackValidator validator = validator(environment, TestAlibabaPolicies.strictMode(), false, false);

        assertThatThrownBy(() -> validator.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Alibaba strict mode is enabled")
                .hasMessageContaining("AI_DASHSCOPE_API_KEY")
                .hasMessageContaining("DASHVECTOR_ENDPOINT");
    }

    @Test
    void failsFastWhenFallbackDisabledAndStackIsIncomplete() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "");
        AlibabaStackValidator validator = validator(environment, TestAlibabaPolicies.fallbackDisabled(), false, false);

        assertThatThrownBy(() -> validator.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.ai.fallback-enabled=false")
                .hasMessageContaining("AI_DASHSCOPE_API_KEY");
    }

    @Test
    void passesWhenStrictModeEnabledAndStackIsComplete() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", "sk-test");
        AlibabaStackValidator validator = validator(environment, TestAlibabaPolicies.strictMode(), true, true);

        assertThatCode(() -> validator.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }

    @Test
    void skipsValidationWhenLegacyFallbackAllowed() {
        MockEnvironment environment = new MockEnvironment();
        AlibabaStackValidator validator = validator(environment, TestAlibabaPolicies.legacyFallbackAllowed(), false,
                false);

        assertThatCode(() -> validator.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }

    private static AlibabaStackValidator validator(MockEnvironment environment, AlibabaRuntimePolicy policy,
            boolean chatClientAvailable, boolean embeddingAvailable) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> embeddingModelProvider = mock(ObjectProvider.class);
        VectorStoreGateway vectorStoreGateway = mock(VectorStoreGateway.class);
        DocumentRetriever documentRetriever = mock(KeywordDocumentRetriever.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setRetriever("dashvector");

        when(chatClientProvider.getIfAvailable()).thenReturn(chatClientAvailable ? mock(ChatClient.class) : null);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingAvailable ? mock(EmbeddingModel.class) : null);
        when(vectorStoreGateway.isConfigured()).thenReturn(chatClientAvailable && embeddingAvailable);
        when(documentRetriever.name()).thenReturn(chatClientAvailable && embeddingAvailable
                ? "DashVectorDocumentRetriever"
                : "KeywordDocumentRetriever");

        return new AlibabaStackValidator(policy, environment, chatClientProvider, embeddingModelProvider,
                vectorStoreGateway, documentRetriever, ragProperties);
    }

    private static final class TestAlibabaPolicies {

        private TestAlibabaPolicies() {
        }

        static AlibabaRuntimePolicy strictMode() {
            return com.example.agentdemo.support.TestAlibabaPolicies.strictMode();
        }

        static AlibabaRuntimePolicy fallbackDisabled() {
            return com.example.agentdemo.support.TestAlibabaPolicies.fallbackDisabled();
        }

        static AlibabaRuntimePolicy legacyFallbackAllowed() {
            return com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed();
        }

    }

}
