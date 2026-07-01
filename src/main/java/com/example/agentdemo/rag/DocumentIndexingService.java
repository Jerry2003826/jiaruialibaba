package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final VectorOutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate nonTransactionalTemplate;

    @Autowired
    public DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy,
            VectorOutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(chunkPersistenceService, embeddingModelProvider::getIfAvailable, vectorStoreGateway, ragProperties,
                alibabaRuntimePolicy, outboxEventRepository, objectMapper, nonTransactionalTemplate(transactionManager));
    }

    DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            EmbeddingModel embeddingModel,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this(chunkPersistenceService, () -> embeddingModel, vectorStoreGateway, ragProperties, alibabaRuntimePolicy,
                null, new ObjectMapper(), null);
    }

    DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            Supplier<EmbeddingModel> embeddingModelSupplier,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy,
            VectorOutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper) {
        this(chunkPersistenceService, embeddingModelSupplier, vectorStoreGateway, ragProperties, alibabaRuntimePolicy,
                outboxEventRepository, objectMapper, null);
    }

    private DocumentIndexingService(DocumentChunkPersistenceService chunkPersistenceService,
            Supplier<EmbeddingModel> embeddingModelSupplier,
            VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy,
            VectorOutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            TransactionTemplate nonTransactionalTemplate) {
        this.chunkPersistenceService = chunkPersistenceService;
        this.embeddingModelSupplier = embeddingModelSupplier;
        this.vectorStoreGateway = vectorStoreGateway;
        this.ragProperties = ragProperties;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.nonTransactionalTemplate = nonTransactionalTemplate;
    }

    public List<DocumentChunkEntity> index(DocumentEntity document) {
        if (!vectorStoreGateway.isConfigured()) {
            if (alibabaRuntimePolicy.isAlibabaStackRequired()) {
                throw new BusinessException("ALIBABA_VECTOR_STORE_NOT_CONFIGURED",
                        "DashVector is required but is not configured");
            }
            // No vector store: this is a keyword-only deployment. The document content is already
            // persisted, so it is immediately retrievable by KeywordDocumentRetriever. Mark it READY
            // instead of leaving it stuck in PENDING (which no worker would ever advance here).
            document.markReady();
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
            embeddings = embedOutsideCurrentTransaction(embeddingModel, chunks);
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

        List<DocumentChunkEntity> savedChunks = chunkPersistenceService.saveChunks(chunkEntities);
        document.markPending();
        enqueueVectorUpsert(document.getId(), vectorDocuments);
        return savedChunks;
    }

    private void enqueueVectorUpsert(Long documentId, List<VectorDocument> vectorDocuments) {
        if (outboxEventRepository == null || vectorDocuments.isEmpty()) {
            return;
        }
        try {
            outboxEventRepository.save(VectorOutboxEventEntity.upsert(documentId,
                    objectMapper.writeValueAsString(vectorDocuments)));
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("VECTOR_OUTBOX_SERIALIZATION_FAILED",
                    "Failed to serialize vector outbox payload", ex);
        }
    }

    private List<float[]> embedOutsideCurrentTransaction(EmbeddingModel embeddingModel, List<String> chunks) {
        if (nonTransactionalTemplate == null) {
            return embeddingModel.embed(chunks);
        }
        return nonTransactionalTemplate.execute(status -> embeddingModel.embed(chunks));
    }

    private static TransactionTemplate nonTransactionalTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return template;
    }

}
