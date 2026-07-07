package com.example.agentdemo.rag.vector;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface VectorStoreGateway {

    String name();

    boolean isConfigured();

    void ensureCollection();

    void upsert(List<VectorDocument> documents);

    void delete(Collection<String> vectorIds);

    List<VectorSearchResult> search(float[] queryVector, int topK, Map<String, Object> metadataFilter);
}
