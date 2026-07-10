package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.HealthResponse;
import com.example.agentdemo.config.AlibabaProperties;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.DocumentRetriever;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlibabaHealthServiceTest {

    @Test
    void exposesAlibabaStackAndFallbackPolicyFields() {
        AiModelService aiModelService = mock(AiModelService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> embeddingModelProvider = mock(ObjectProvider.class);
        VectorStoreGateway vectorStoreGateway = mock(VectorStoreGateway.class);
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        AlibabaProperties alibabaProperties = new AlibabaProperties();
        alibabaProperties.setStrictMode(true);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setKeywordFallbackEnabled(false);
        AlibabaRuntimePolicy policy = new AlibabaRuntimePolicy(alibabaProperties, ragProperties, false);

        WorkflowRuntimeProperties workflowRuntimeProperties = new WorkflowRuntimeProperties();
        workflowRuntimeProperties.setRuntime("graph");
        workflowRuntimeProperties.setRequirePublishedForRun(true);

        when(aiModelService.isModelConfigured()).thenReturn(true);
        when(aiModelService.isChatClientAvailable()).thenReturn(true);
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        when(embeddingModelProvider.getIfAvailable()).thenReturn(mock(EmbeddingModel.class));
        when(vectorStoreGateway.isConfigured()).thenReturn(true);
        when(documentRetriever.name()).thenReturn("DashVectorDocumentRetriever");
        when(documentRepository.countPublicByOwnerIdAndIndexStatusNot("workbench-dev", DocumentIndexStatus.DELETED))
                .thenReturn(0L);

        AlibabaHealthService service = new AlibabaHealthService(aiModelService, embeddingModelProvider,
                vectorStoreGateway, documentRetriever, documentRepository, alibabaProperties, policy,
                workflowRuntimeProperties, false);

        HealthResponse health = service.health();

        assertThat(health.strictMode()).isTrue();
        assertThat(health.fallbackEnabled()).isFalse();
        assertThat(health.keywordFallbackEnabled()).isFalse();
        assertThat(health.embeddingConfigured()).isTrue();
        assertThat(health.vectorStoreConfigured()).isTrue();
        assertThat(health.ragRetriever()).isEqualTo("DashVectorDocumentRetriever");
        assertThat(health.indexedDocumentCount()).isZero();
        assertThat(health.workflowRuntime()).isEqualTo("graph");
        assertThat(health.workflowRequirePublishedForRun()).isTrue();
    }

}
