package com.example.agentdemo.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to search a knowledge base.
 *
 * @param query the search query (required)
 * @param topK  optional result count (defaults to the KB's retrieval config)
 */
public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 2000) String query,
        Integer topK) {
}
