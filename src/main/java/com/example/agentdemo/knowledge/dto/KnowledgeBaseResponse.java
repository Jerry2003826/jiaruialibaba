package com.example.agentdemo.knowledge.dto;

import com.example.agentdemo.knowledge.RetrievalConfig;

import java.time.Instant;

/**
 * API view of a knowledge base.
 */
public record KnowledgeBaseResponse(String kbId, String name, String description, RetrievalConfig retrievalConfig,
        long documentCount, Instant createdAt, Instant updatedAt) {
}
