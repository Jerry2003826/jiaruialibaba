package com.example.agentdemo.rag;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        AgentBackendDemoApplication.class,
        DocumentIndexingTransactionIntegrationTest.TestConfig.class
}, properties = {
        "spring.profiles.group.dev=dev",
        "demo.seed.enabled=false",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.rag.retriever=keyword",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:document_indexing_tx_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=test-endpoint",
        "demo.dashvector.api-key=test-key"
})
class DocumentIndexingTransactionIntegrationTest {

    @Autowired
    private RagService ragService;

    @Autowired
    private DocumentManagementService documentManagementService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private RecordingEmbeddingModel embeddingModel;

    @BeforeEach
    void resetEmbeddingRecorder() {
        embeddingModel.transactionStates.clear();
    }

    @Test
    void saveDocumentEmbedsAfterDocumentTransactionCommits() {
        ragService.saveDocument(new DocumentRequest("Save", "save content"));

        assertThat(embeddingModel.transactionStates).containsExactly(false);
    }

    @Test
    void updateDocumentEmbedsAfterDocumentTransactionCommits() {
        DocumentEntity document = documentRepository.saveAndFlush(new DocumentEntity("Old", "old content"));

        documentManagementService.updateDocument(document.getId(), new DocumentRequest("New", "new content"));

        assertThat(embeddingModel.transactionStates).containsExactly(false);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        RecordingEmbeddingModel recordingEmbeddingModel() {
            return new RecordingEmbeddingModel();
        }

        @Bean
        @Primary
        VectorStoreGateway fakeVectorStoreGateway() {
            return new NoopConfiguredVectorStoreGateway();
        }
    }

    static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<Boolean> transactionStates = new CopyOnWriteArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(vectorFor(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorFor(document.getText());
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            return texts.stream()
                    .map(RecordingEmbeddingModel::vectorFor)
                    .toList();
        }

        private static float[] vectorFor(String text) {
            return new float[] { text.length(), text.isEmpty() ? 0 : text.charAt(0) };
        }
    }

    private static final class NoopConfiguredVectorStoreGateway implements VectorStoreGateway {

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void ensureCollection() {
        }

        @Override
        public void upsert(List<VectorDocument> documents) {
        }

        @Override
        public void delete(Collection<String> vectorIds) {
        }

        @Override
        public List<VectorSearchResult> search(float[] queryVector, int topK) {
            return List.of();
        }
    }
}
