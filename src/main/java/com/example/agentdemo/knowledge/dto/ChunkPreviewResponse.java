package com.example.agentdemo.knowledge.dto;

import java.util.List;

/**
 * Preview of how a document is split into chunks for indexing.
 */
public record ChunkPreviewResponse(Long documentId, int chunkSize, int chunkOverlap, int page, int size,
        int totalChunks, int totalPages, List<Chunk> chunks) {

    public ChunkPreviewResponse(Long documentId, int chunkSize, int chunkOverlap, List<Chunk> chunks) {
        this(documentId, chunkSize, chunkOverlap, 0, chunks.size(), chunks.size(), chunks.isEmpty() ? 0 : 1, chunks);
    }

    public record Chunk(int chunkIndex, int length, String content) {
    }

}
