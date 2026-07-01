package com.example.agentdemo.knowledge;

/**
 * A retrieval citation returned by knowledge-base search and RAG answers.
 *
 * @param documentId source document id
 * @param title      document title
 * @param chunkIndex chunk index (0 for whole-document keyword matches)
 * @param snippet    matched snippet
 * @param score      relevance score
 */
public record Citation(Long documentId, String title, int chunkIndex, String snippet, double score) {
}
