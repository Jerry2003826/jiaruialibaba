package com.example.agentdemo.knowledge.dto;

import java.util.List;

/**
 * Preview of how a document is split into chunks for indexing.
 */
public record ChunkPreviewResponse(Long documentId, int chunkSize, int chunkOverlap, List<Chunk> chunks) {

    public record Chunk(int chunkIndex, int length, String content) {
    }

}
