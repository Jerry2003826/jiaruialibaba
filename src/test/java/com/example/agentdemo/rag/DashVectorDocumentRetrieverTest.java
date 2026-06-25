package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashVectorDocumentRetrieverTest {

    @Mock
    private VectorStoreGateway vectorStoreGateway;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Mock
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Mock
    private EmbeddingModel embeddingModel;

    @Test
    @SuppressWarnings("unchecked")
    void retrievesContextsFromVectorMatches() {
        DashVectorDocumentRetriever retriever = retriever();
        float[] queryVector = new float[] { 1.0f, 2.0f };
        when(vectorStoreGateway.isConfigured()).thenReturn(true);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed("question")).thenReturn(queryVector);
        when(vectorStoreGateway.search(queryVector, 3)).thenReturn(List.of(
                new VectorSearchResult("vec-missing", 0.99, Map.of()),
                new VectorSearchResult("vec-low", 0.42, Map.of()),
                new VectorSearchResult("vec-high", 0.88, Map.of())));

        DocumentChunkEntity lowChunk = new DocumentChunkEntity(20L, 0, "vec-low", "low content");
        DocumentChunkEntity highChunk = new DocumentChunkEntity(10L, 1, "vec-high", "high content");
        when(chunkRepository.findByVectorIdIn(anyCollection())).thenReturn(List.of(lowChunk, highChunk));

        DocumentEntity highDocument = readyDocument(10L, "High Title", "full high");
        DocumentEntity lowDocument = readyDocument(20L, null, "full low");
        when(documentRepository.findByIdInAndIndexStatus(anyCollection(), eq(DocumentIndexStatus.READY)))
                .thenReturn(List.of(highDocument, lowDocument));

        List<RetrievedContext> contexts = retriever.retrieve("question", 3);

        assertThat(contexts)
                .extracting(RetrievedContext::documentId)
                .containsExactly(10L, 20L);
        assertThat(contexts.get(0).title()).isEqualTo("High Title");
        assertThat(contexts.get(0).snippet()).isEqualTo("high content");
        assertThat(contexts.get(0).score()).isEqualTo(0.88);
        assertThat(contexts.get(1).title()).isEmpty();
        assertThat(contexts.get(1).snippet()).isEqualTo("low content");

        ArgumentCaptor<Collection<String>> vectorIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(chunkRepository).findByVectorIdIn(vectorIdsCaptor.capture());
        assertThat(vectorIdsCaptor.getValue()).containsExactly("vec-missing", "vec-low", "vec-high");
    }

    @Test
    void skipsVectorMatchesWhoseDocumentsAreNotReady() {
        DashVectorDocumentRetriever retriever = retriever();
        float[] queryVector = new float[] { 1.0f, 2.0f };
        when(vectorStoreGateway.isConfigured()).thenReturn(true);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed("question")).thenReturn(queryVector);
        when(vectorStoreGateway.search(queryVector, 2)).thenReturn(List.of(
                new VectorSearchResult("vec-pending", 0.95, Map.of()),
                new VectorSearchResult("vec-ready", 0.70, Map.of())));

        DocumentChunkEntity pendingChunk = new DocumentChunkEntity(30L, 0, "vec-pending", "pending content");
        DocumentChunkEntity readyChunk = new DocumentChunkEntity(10L, 0, "vec-ready", "ready content");
        when(chunkRepository.findByVectorIdIn(anyCollection())).thenReturn(List.of(pendingChunk, readyChunk));

        DocumentEntity readyDocument = readyDocument(10L, "Ready Title", "full ready");
        when(documentRepository.findByIdInAndIndexStatus(anyCollection(), eq(DocumentIndexStatus.READY)))
                .thenReturn(List.of(readyDocument));

        List<RetrievedContext> contexts = retriever.retrieve("question", 2);

        assertThat(contexts)
                .extracting(RetrievedContext::documentId)
                .containsExactly(10L);
        assertThat(contexts.getFirst().title()).isEqualTo("Ready Title");
        assertThat(contexts.getFirst().snippet()).isEqualTo("ready content");
    }

    @Test
    void failsWhenVectorGatewayIsNotConfigured() {
        DashVectorDocumentRetriever retriever = retriever();
        when(vectorStoreGateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> retriever.retrieve("question", 3))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("VECTOR_STORE_NOT_CONFIGURED"))
                .hasMessage("DashVector is not configured");

        verify(embeddingModelProvider, never()).getIfAvailable();
    }

    @Test
    void failsWhenEmbeddingModelIsAbsent() {
        DashVectorDocumentRetriever retriever = retriever();
        when(vectorStoreGateway.isConfigured()).thenReturn(true);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> retriever.retrieve("question", 3))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("EMBEDDING_MODEL_NOT_CONFIGURED"))
                .hasMessage("DashScope EmbeddingModel is not configured");

        verify(vectorStoreGateway, never()).search(any(), anyInt());
    }

    @Test
    void wrapsQueryEmbeddingFailures() {
        DashVectorDocumentRetriever retriever = retriever();
        IllegalStateException cause = new IllegalStateException("embedding unavailable");
        when(vectorStoreGateway.isConfigured()).thenReturn(true);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed("question")).thenThrow(cause);

        assertThatThrownBy(() -> retriever.retrieve("question", 3))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("EMBEDDING_FAILED");
                    assertThat(ex.getCause()).isSameAs(cause);
                })
                .hasMessage("Failed to embed retrieval query");

        verify(vectorStoreGateway, never()).search(any(), anyInt());
    }

    private DashVectorDocumentRetriever retriever() {
        return new DashVectorDocumentRetriever(vectorStoreGateway, documentRepository, chunkRepository,
                embeddingModelProvider);
    }

    private static DocumentEntity document(Long id, String title, String content) {
        DocumentEntity document = new DocumentEntity(title, content);
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }

    private static DocumentEntity readyDocument(Long id, String title, String content) {
        DocumentEntity document = document(id, title, content);
        document.markReady();
        return document;
    }

}
