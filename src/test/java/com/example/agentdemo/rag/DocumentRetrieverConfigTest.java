package com.example.agentdemo.rag;

import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.knowledge.KnowledgeBaseRepository;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentRetrieverConfigTest {

    @Test
    void failsFastWhenDashVectorRequiredButNotConfigured() {
        DocumentRetrieverConfig config = new DocumentRetrieverConfig();
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setRetriever("dashvector");
        VectorStoreGateway vectorStoreGateway = mock(VectorStoreGateway.class);
        when(vectorStoreGateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> config.documentRetriever(ragProperties, vectorStoreGateway,
                mock(DocumentRepository.class), mock(DocumentChunkRepository.class),
                mock(ObjectProvider.class), mock(KnowledgeBaseRepository.class), mock(KeywordDocumentRetriever.class),
                TestAlibabaPolicies.strictMode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHVECTOR_ENDPOINT");
    }

}
