package com.example.agentdemo.knowledge.dto;

import java.util.List;

/**
 * Paginated knowledge-base document listing.
 */
public record KnowledgeDocumentPageResponse(List<KnowledgeDocumentResponse> content, int page, int size,
        long totalElements, int totalPages) {
}
