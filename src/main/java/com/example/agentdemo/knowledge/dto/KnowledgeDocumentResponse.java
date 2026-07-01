package com.example.agentdemo.knowledge.dto;

import com.example.agentdemo.rag.DocumentIndexStatus;

import java.time.Instant;

/**
 * API view of a knowledge-base document (metadata + index status).
 */
public record KnowledgeDocumentResponse(Long documentId, String kbId, String title, String sourceType,
        String fileName, String mimeType, Long sizeBytes, int contentLength, DocumentIndexStatus indexStatus,
        String errorMessage, Instant createdAt) {
}
