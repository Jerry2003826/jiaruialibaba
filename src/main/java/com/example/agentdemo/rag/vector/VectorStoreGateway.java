package com.example.agentdemo.rag.vector;

import java.util.List;

public interface VectorStoreGateway {

    String name();

    boolean isConfigured();

    void ensureCollection();

    void upsert(List<VectorDocument> documents);

    List<VectorSearchResult> search(float[] queryVector, int topK);
}
