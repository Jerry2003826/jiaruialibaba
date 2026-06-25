package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class DocumentIndexingService {

    private final DocumentChunkPersistenceService chunkPersistenceService;
    private final Supplier<EmbeddingModel> embeddingModelSupplier;
    private final VectorStoreGateway vectorStoreGateway;
    private final RagProperties ragProperties;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;

    @Autowired
    public DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this(chunkPersistenceService, embeddingModelProvider::getIfAvailable, vectorStoreGateway, ragProperties,
                alibabaRuntimePolicy);
    }

    DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            EmbeddingModel embeddingModel,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this(chunkPersistenceService, () -> embeddingModel, vectorStoreGateway, ragProperties, alibabaRuntimePolicy);
    }

    private DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            Supplier<EmbeddingModel> embeddingModelSupplier,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this.chunkPersistenceService = chunkPersistenceService;
        this.embeddingModelSupplier = embeddingModelSupplier;
        this.vectorStoreGateway = vectorStoreGateway;
        this.ragProperties = ragProperties;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
    }

    public List<DocumentChunkEntity> index(DocumentEntity document) {
        if (!vectorStoreGateway.isConfigured()) {
            if (alibabaRuntimePolicy.isAlibabaStackRequired()) {
                throw new BusinessException("ALIBABA_VECTOR_STORE_NOT_CONFIGURED",
                        "DashVector is required but is not configured");
            }
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

        if (alibabaRuntimePolicy.isAlibabaStackRequired()) {
            return indexWithSynchronousVectors(vectorDocuments, chunkEntities);
        }

        List<DocumentChunkEntity> savedChunks = chunkPersistenceService.saveChunks(chunkEntities);
        scheduleVectorUpsertAfterCommit(vectorDocuments);
        return savedChunks;
    }

    private List<DocumentChunkEntity> indexWithSynchronousVectors(List<VectorDocument> vectorDocuments,
            List<DocumentChunkEntity> chunkEntities) {
        List<String> vectorIds = vectorDocuments.stream().map(VectorDocument::id).toList();
        vectorStoreGateway.ensureCollection();
        try {
            vectorStoreGateway.upsert(vectorDocuments);
            return chunkPersistenceService.saveChunks(chunkEntities);
        }
        catch (RuntimeException ex) {
            rollbackVectors(vectorIds);
            throw ex;
        }
    }

    private void rollbackVectors(List<String> vectorIds) {
        if (vectorIds.isEmpty()) {
            return;
        }
        try {
            vectorStoreGateway.delete(vectorIds);
        }
        catch (RuntimeException cleanupFailure) {
            throw new BusinessException("VECTOR_INDEX_ROLLBACK_FAILED",
                    "Failed to roll back vectors after indexing failure", cleanupFailure);
        }
    }

    private void scheduleVectorUpsertAfterCommit(List<VectorDocument> vectorDocuments) {
        if (vectorDocuments.isEmpty()) {
            return;
        }
        Runnable upsert = () -> {
            vectorStoreGateway.ensureCollection();
            vectorStoreGateway.upsert(vectorDocuments);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    upsert.run();
                }
            });
        }
        else {
            upsert.run();
        }
    }

}
