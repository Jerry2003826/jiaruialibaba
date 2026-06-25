package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentIndexingServiceStrictTest {

    @Test
    void throwsWhenStrictModeEnabledAndVectorStoreIsNotConfigured() {
        RagProperties ragProperties = new RagProperties();
        DocumentIndexingService service = new DocumentIndexingService(
                new DocumentChunkPersistenceService(mock(DocumentChunkRepository.class)),
                (org.springframework.ai.embedding.EmbeddingModel) null,
                new FakeVectorStoreGateway(false),
                ragProperties,
                TestAlibabaPolicies.strictMode());

        DocumentEntity document = new DocumentEntity("Doc", "content");
        ReflectionTestUtils.setField(document, "id", 1L);

        assertThatThrownBy(() -> service.index(document))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_VECTOR_STORE_NOT_CONFIGURED");
    }

    @Test
    void rollsBackVectorsWhenChunkPersistenceFailsInStrictMode() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        TrackingVectorStoreGateway vectorStoreGateway = new TrackingVectorStoreGateway(true);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setChunkSize(10);
        ragProperties.getRag().setChunkOverlap(0);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        when(chunkRepository.saveAllAndFlush(anyList())).thenThrow(new IllegalStateException("database unavailable"));
        DocumentIndexingService service = new DocumentIndexingService(
                new DocumentChunkPersistenceService(chunkRepository),
                embeddingModel,
                vectorStoreGateway,
                ragProperties,
                TestAlibabaPolicies.strictMode());

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        assertThatThrownBy(() -> service.index(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        assertThat(vectorStoreGateway.upsertedDocuments).hasSize(2);
        assertThat(vectorStoreGateway.deletedVectorIds).containsExactly("doc-7-chunk-0", "doc-7-chunk-1");
    }

    private static final class FakeEmbeddingModel implements org.springframework.ai.embedding.EmbeddingModel {

        @Override
        public org.springframework.ai.embedding.EmbeddingResponse call(
                org.springframework.ai.embedding.EmbeddingRequest request) {
            java.util.List<org.springframework.ai.embedding.Embedding> embeddings = new java.util.ArrayList<>();
            java.util.List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new org.springframework.ai.embedding.Embedding(new float[] {1.0f, i}, i));
            }
            return new org.springframework.ai.embedding.EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return new float[] {1.0f, 1.0f};
        }

        @Override
        public java.util.List<float[]> embed(java.util.List<String> texts) {
            return texts.stream().map(text -> new float[] {1.0f, text.length()}).toList();
        }

    }

    private static final class TrackingVectorStoreGateway implements VectorStoreGateway {

        private final boolean configured;
        private final java.util.List<com.example.agentdemo.rag.vector.VectorDocument> upsertedDocuments =
                new java.util.ArrayList<>();
        private final java.util.List<String> deletedVectorIds = new java.util.ArrayList<>();

        private TrackingVectorStoreGateway(boolean configured) {
            this.configured = configured;
        }

        @Override
        public String name() {
            return "TrackingVectorStore";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void ensureCollection() {
        }

        @Override
        public void upsert(java.util.List<com.example.agentdemo.rag.vector.VectorDocument> documents) {
            upsertedDocuments.addAll(documents);
        }

        @Override
        public void delete(java.util.Collection<String> vectorIds) {
            deletedVectorIds.addAll(vectorIds);
        }

        @Override
        public java.util.List<com.example.agentdemo.rag.vector.VectorSearchResult> search(float[] queryVector,
                int topK) {
            return java.util.List.of();
        }

    }

    private static final class FakeVectorStoreGateway implements VectorStoreGateway {

        private final boolean configured;

        private FakeVectorStoreGateway(boolean configured) {
            this.configured = configured;
        }

        @Override
        public String name() {
            return "FakeVectorStore";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void ensureCollection() {
        }

        @Override
        public void upsert(java.util.List<com.example.agentdemo.rag.vector.VectorDocument> documents) {
        }

        @Override
        public void delete(java.util.Collection<String> vectorIds) {
        }

        @Override
        public java.util.List<com.example.agentdemo.rag.vector.VectorSearchResult> search(float[] queryVector,
                int topK) {
            return java.util.List.of();
        }

    }

}
