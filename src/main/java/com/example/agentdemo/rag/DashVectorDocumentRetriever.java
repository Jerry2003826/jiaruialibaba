package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class DashVectorDocumentRetriever implements DocumentRetriever {

    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public DashVectorDocumentRetriever(VectorStoreGateway vectorStoreGateway, DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository, ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.vectorStoreGateway = vectorStoreGateway;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public String name() {
        return "DashVectorDocumentRetriever";
    }

    @Override
    public List<RetrievedContext> retrieve(String query, int limit) {
        if (!vectorStoreGateway.isConfigured()) {
            throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED", "DashVector is not configured");
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new BusinessException("EMBEDDING_MODEL_NOT_CONFIGURED",
                    "DashScope EmbeddingModel is not configured");
        }

        List<VectorSearchResult> vectorResults = vectorStoreGateway.search(embeddingModel.embed(query), limit);
        if (vectorResults.isEmpty()) {
            return List.of();
        }

        Map<String, VectorSearchResult> resultsByVectorId = vectorResults.stream()
                .collect(Collectors.toMap(VectorSearchResult::id, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        Set<String> vectorIds = new LinkedHashSet<>(resultsByVectorId.keySet());
        Map<String, DocumentChunkEntity> chunksByVectorId = chunkRepository.findByVectorIdIn(vectorIds)
                .stream()
                .collect(Collectors.toMap(DocumentChunkEntity::getVectorId, Function.identity()));
        if (chunksByVectorId.isEmpty()) {
            return List.of();
        }

        Set<Long> documentIds = chunksByVectorId.values()
                .stream()
                .map(DocumentChunkEntity::getDocumentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, DocumentEntity> documentsById = StreamSupport
                .stream(documentRepository.findAllById(documentIds).spliterator(), false)
                .collect(Collectors.toMap(DocumentEntity::getId, Function.identity()));

        return resultsByVectorId.values()
                .stream()
                .map(result -> toRetrievedContext(result, chunksByVectorId, documentsById))
                .filter(context -> context != null)
                .sorted(Comparator.comparingDouble(RetrievedContext::score).reversed())
                .toList();
    }

    private RetrievedContext toRetrievedContext(VectorSearchResult result,
            Map<String, DocumentChunkEntity> chunksByVectorId,
            Map<Long, DocumentEntity> documentsById) {
        DocumentChunkEntity chunk = chunksByVectorId.get(result.id());
        if (chunk == null) {
            return null;
        }
        DocumentEntity document = documentsById.get(chunk.getDocumentId());
        String title = document == null || document.getTitle() == null ? "" : document.getTitle();
        return new RetrievedContext(chunk.getDocumentId(), title, chunk.getContent(), result.score());
    }

}
