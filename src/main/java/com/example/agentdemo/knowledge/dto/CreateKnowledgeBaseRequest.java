package com.example.agentdemo.knowledge.dto;

import com.example.agentdemo.knowledge.RetrievalConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to create a knowledge base.
 *
 * @param name            display name (required)
 * @param description     optional description
 * @param retrievalConfig optional chunk/top-k configuration
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description,
        RetrievalConfig retrievalConfig) {
}
