package com.example.agentdemo.rag;

import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIndexingServiceTest {

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Test
    @SuppressWarnings("unchecked")
    void indexesDocumentChunksIntoVectorStore() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(true);
        RagProperties ragProperties = ragProperties(10, 0);
        DocumentIndexingService service = new DocumentIndexingService(chunkRepository, embeddingModel,
                vectorStoreGateway, ragProperties);

        when(chunkRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentEntity document = new DocumentEntity("Letters", "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        List<DocumentChunkEntity> savedChunks = service.index(document);

        assertThat(vectorStoreGateway.ensureCollectionCalls).isEqualTo(1);
        assertThat(embeddingModel.requestedTexts).containsExactly("abcdefghij", "klmnop");
        assertThat(vectorStoreGateway.upsertedDocuments).hasSize(2);
        VectorDocument firstVector = vectorStoreGateway.upsertedDocuments.getFirst();
        assertThat(firstVector.id()).isEqualTo("doc-7-chunk-0");
        assertThat(firstVector.metadata())
                .containsEntry("documentId", 7L)
                .containsEntry("chunkIndex", 0)
                .containsEntry("title", "Letters");
        assertThat(savedChunks).hasSize(2);

        ArgumentCaptor<List<DocumentChunkEntity>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(chunksCaptor.capture());
        assertThat(chunksCaptor.getValue())
                .isNotEmpty()
                .extracting(DocumentChunkEntity::getVectorId)
                .containsExactly("doc-7-chunk-0", "doc-7-chunk-1");
    }

    @Test
    void skipsIndexingWhenVectorGatewayIsNotConfigured() {
        FakeEmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeVectorStoreGateway vectorStoreGateway = new FakeVectorStoreGateway(false);
        DocumentIndexingService service = new DocumentIndexingService(chunkRepository, embeddingModel,
                vectorStoreGateway, ragProperties(10, 0));

        DocumentEntity document = new DocumentEntity(null, "abcdefghijklmnop");
        ReflectionTestUtils.setField(document, "id", 7L);

        List<DocumentChunkEntity> savedChunks = service.index(document);

        assertThat(savedChunks).isEmpty();
        assertThat(embeddingModel.requestedTexts).isEmpty();
        assertThat(vectorStoreGateway.ensureCollectionCalls).isZero();
        assertThat(vectorStoreGateway.upsertedDocuments).isEmpty();
        verify(chunkRepository, never()).saveAll(anyList());
    }

    private static RagProperties ragProperties(int chunkSize, int chunkOverlap) {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setChunkSize(chunkSize);
        ragProperties.getRag().setChunkOverlap(chunkOverlap);
        return ragProperties;
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {

        private final List<String> requestedTexts = new ArrayList<>();

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
            return texts.stream()
                    .map(FakeEmbeddingModel::vectorFor)
                    .toList();
        }

        private static float[] vectorFor(String text) {
            return new float[] { text.length(), text.charAt(0) };
        }

    }

    private static final class FakeVectorStoreGateway implements VectorStoreGateway {

        private final boolean configured;
        private final List<VectorDocument> upsertedDocuments = new ArrayList<>();
        private int ensureCollectionCalls;

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
            upsertedDocuments.addAll(documents);
        }

        @Override
        public List<VectorSearchResult> search(float[] queryVector, int topK) {
            return List.of();
        }

    }

}
