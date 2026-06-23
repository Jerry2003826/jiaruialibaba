package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class DocumentIndexingService {

    private final DocumentChunkRepository chunkRepository;
    private final Supplier<EmbeddingModel> embeddingModelSupplier;
    private final VectorStoreGateway vectorStoreGateway;
    private final RagProperties ragProperties;

    public DocumentIndexingService(DocumentChunkRepository chunkRepository,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties) {
        this(chunkRepository, embeddingModelProvider::getIfAvailable, vectorStoreGateway, ragProperties);
    }

    DocumentIndexingService(DocumentChunkRepository chunkRepository,
            EmbeddingModel embeddingModel,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties) {
        this(chunkRepository, () -> embeddingModel, vectorStoreGateway, ragProperties);
    }

    private DocumentIndexingService(DocumentChunkRepository chunkRepository,
            Supplier<EmbeddingModel> embeddingModelSupplier,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties) {
        this.chunkRepository = chunkRepository;
        this.embeddingModelSupplier = embeddingModelSupplier;
        this.vectorStoreGateway = vectorStoreGateway;
        this.ragProperties = ragProperties;
    }

    @Transactional
    public List<DocumentChunkEntity> index(DocumentEntity document) {
        if (!vectorStoreGateway.isConfigured()) {
            return List.of();
        }

        EmbeddingModel embeddingModel = embeddingModelSupplier.get();
        if (embeddingModel == null) {
            throw new BusinessException("EMBEDDING_MODEL_NOT_CONFIGURED",
                    "DashScope EmbeddingModel is not configured");
        }

        TextChunker chunker = new TextChunker(ragProperties.getRag().getChunkSize(),
                ragProperties.getRag().getChunkOverlap());
        List<String> chunks = chunker.split(document.getContent());
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<float[]> embeddings;
        try {
            embeddings = embeddingModel.embed(chunks);
        }
        catch (RuntimeException ex) {
            throw new BusinessException("EMBEDDING_FAILED", "Failed to embed document chunks", ex);
        }
        if (embeddings.size() != chunks.size()) {
            throw new BusinessException("EMBEDDING_RESULT_MISMATCH",
                    "Embedding result count " + embeddings.size() + " does not match chunk count " + chunks.size());
        }

        List<DocumentChunkEntity> chunkEntities = new ArrayList<>(chunks.size());
        List<VectorDocument> vectorDocuments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String vectorId = "doc-" + document.getId() + "-chunk-" + i;
            chunkEntities.add(new DocumentChunkEntity(document.getId(), i, vectorId, chunks.get(i)));
            vectorDocuments.add(new VectorDocument(vectorId, embeddings.get(i), Map.of(
                    "documentId", document.getId(),
                    "chunkIndex", i,
                    "title", document.getTitle() == null ? "" : document.getTitle())));
        }

        List<DocumentChunkEntity> savedChunks = chunkRepository.saveAllAndFlush(chunkEntities);
        vectorStoreGateway.ensureCollection();
        vectorStoreGateway.upsert(vectorDocuments);
        return savedChunks;
    }

}
