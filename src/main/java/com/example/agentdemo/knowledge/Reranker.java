package com.example.agentdemo.knowledge;

import java.util.List;

/**
 * Reorders retrieval candidates by relevance to the query. This is the extension point for a real
 * cross-encoder / DashScope rerank model; the default {@link NoOpReranker} preserves input order.
 * Keeping the interface now lets hybrid (keyword + vector) retrieval add reranking without an API
 * change.
 */
public interface Reranker {

    /**
     * Returns the candidates reordered (and optionally trimmed) by relevance.
     *
     * @param query      the search query
     * @param candidates retrieval candidates
     * @param topK       maximum number of results to return
     * @return reranked citations, at most {@code topK}
     */
    List<Citation> rerank(String query, List<Citation> candidates, int topK);

}
