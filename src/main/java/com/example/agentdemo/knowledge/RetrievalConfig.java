package com.example.agentdemo.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-knowledge-base retrieval configuration. Null fields fall back to server defaults.
 *
 * @param chunkSize    target chunk size in characters
 * @param chunkOverlap overlap between chunks in characters
 * @param topK         default number of results returned by search
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetrievalConfig(Integer chunkSize, Integer chunkOverlap, Integer topK) {

    public static RetrievalConfig defaults() {
        return new RetrievalConfig(null, null, null);
    }

    public int chunkSizeOr(int fallback) {
        return chunkSize == null || chunkSize <= 0 ? fallback : chunkSize;
    }

    public int chunkOverlapOr(int fallback) {
        return chunkOverlap == null || chunkOverlap < 0 ? fallback : chunkOverlap;
    }

    public int topKOr(int fallback) {
        return topK == null || topK <= 0 ? fallback : topK;
    }

}
