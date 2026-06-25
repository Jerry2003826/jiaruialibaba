package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIndexingServiceTest {

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Test
    @SuppressWarnings("unchecked")
    void indexesDocumentChunksIntoVectorOutbox() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        VectorOutboxEventRepository outboxEventRepository = mock(VectorOutboxEventRepository.class);
        RagProperties ragProperties = ragProperties(10, 0);
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), () -> embeddingModel,
                vectorStoreGateway, ragProperties, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                outboxEventRepository, new ObjectMapper());

        when(chunkRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        List<DocumentChunkEntity> savedChunks = service.index(document);

        assertThat(vectorStoreGateway.ensureCollectionCalls).isZero();
        assertThat(embeddingModel.requestedTexts).containsExactly("abcdefghij", "klmnop");
        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
        assertThat(savedChunks).hasSize(2);

        ArgumentCaptor<List<DocumentChunkEntity>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAllAndFlush(chunksCaptor.capture());
        assertThat(chunksCaptor.getValue())
                .isNotEmpty()
                .extracting(DocumentChunkEntity::getVectorId)
                .containsExactly("doc-7-chunk-0", "doc-7-chunk-1");
        ArgumentCaptor<VectorOutboxEventEntity> eventCaptor = ArgumentCaptor.forClass(VectorOutboxEventEntity.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo(VectorOutboxEventType.UPSERT);
        assertThat(eventCaptor.getValue().getDocumentId()).isEqualTo(7L);
        assertThat(eventCaptor.getValue().getPayloadJson()).contains("doc-7-chunk-0", "doc-7-chunk-1");
    }

    @Test
    void skipsIndexingWhenVectorGatewayIsNotConfigured() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(false);
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), embeddingModel,
                vectorStoreGateway, ragProperties(10, 0), com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed());

        DocumentEntity document = new DocumentEntity(null, "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        List<DocumentChunkEntity> savedChunks = service.index(document);

        assertThat(savedChunks).isEmpty();
        assertThat(embeddingModel.requestedTexts).isEmpty();
        assertThat(vectorStoreGateway.ensureCollectionCalls).isZero();
        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void failsWhenEmbeddingModelIsAbsent() {
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), (EmbeddingModel) null,
                vectorStoreGateway, ragProperties(10, 0), com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed());

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        assertThatThrownBy(() -> service.index(document))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("EMBEDDING_MODEL_NOT_CONFIGURED"))
                .hasMessage("DashScope EmbeddingModel is not configured");

        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void wrapsEmbeddingFailures() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        embeddingModel.failOnEmbed = true;
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), embeddingModel,
                vectorStoreGateway, ragProperties(10, 0), com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed());

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        assertThatThrownBy(() -> service.index(document))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("EMBEDDING_FAILED");
                    assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                            .hasMessage("embedding unavailable");
                })
                .hasMessage("Failed to embed document chunks");

        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void failsWhenEmbeddingCountDoesNotMatchChunks() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        embeddingModel.dropLastEmbedding = true;
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), embeddingModel,
                vectorStoreGateway, ragProperties(10, 0), com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed());

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        assertThatThrownBy(() -> service.index(document))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("EMBEDDING_RESULT_MISMATCH"))
                .hasMessage("Embedding result count 1 does not match chunk count 2");

        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void doesNotUpsertVectorsWhenChunkPersistenceFails() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), embeddingModel,
                vectorStoreGateway, ragProperties(10, 0), com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed());

        when(chunkRepository.saveAllAndFlush(anyList())).thenThrow(new IllegalStateException("database unavailable"));

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        assertThatThrownBy(() -> service.index(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        assertThat(embeddingModel.requestedTexts).containsExactly("abcdefghij", "klmnop");
        assertThat(vectorStoreGateway.ensureCollectionCalls).isZero();
        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
    }

    @Test
    void doesNotPropagateVectorUpsertFailureFromRequestThread() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        vectorStoreGateway.failOnUpsert = true;
        DocumentIndexingService service = new DocumentIndexingService(chunkPersistenceService(), embeddingModel,
                vectorStoreGateway, ragProperties(10, 0), com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed());

        when(chunkRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        assertThatCode(() -> service.index(document))
                .doesNotThrowAnyException();

        verify(chunkRepository).saveAllAndFlush(anyList());
        assertThat(vectorStoreGateway.ensureCollectionCalls).isZero();
        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
    }

    private static RagProperties ragProperties(int chunkSize, int chunkOverlap) {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setChunkSize(chunkSize);
        ragProperties.getRag().setChunkOverlap(chunkOverlap);
        return ragProperties;
    }

    private DocumentChunkPersistenceService chunkPersistenceService() {
        return new DocumentChunkPersistenceService(chunkRepository);
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {

        private final List<String> requestedTexts = new ArrayList<>();
        private boolean failOnEmbed;
        private boolean dropLastEmbedding;

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
            requestedTexts.addAll(texts);
            if (failOnEmbed) {
                throw new IllegalStateException("embedding unavailable");
            }
            List<float[]> embeddings = texts.stream()
                    .map(FakeEmbeddingModel::vectorFor)
                    .toList();
            if (dropLastEmbedding) {
                return embeddings.subList(0, embeddings.size() - 1);
            }
            return embeddings;
        }

        private static float[] vectorFor(String text) {
            return new float[] { text.length(), text.charAt(0) };
        }

    }

    private static final class FakeVectorStoreGateway implements VectorStoreGateway {

        private final boolean configured;
        private final List<VectorDocument> upsertedDocuments = new ArrayList<>();
        private int ensureCollectionCalls;
        private boolean failOnUpsert;

        private FakeVectorStoreGateway(boolean configured) {
            this.configured = configured;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void ensureCollection() {
            ensureCollectionCalls++;
        }

        @Override
        public void upsert(List<VectorDocument> documents) {
            if (failOnUpsert) {
                throw new IllegalStateException("vector upsert unavailable");
            }
            upsertedDocuments.addAll(documents);
        }

        @Override
        public void delete(java.util.Collection<String> vectorIds) {
        }

        @Override
        public List<VectorSearchResult> search(float[] queryVector, int topK) {
            return List.of();
        }

    }

}
