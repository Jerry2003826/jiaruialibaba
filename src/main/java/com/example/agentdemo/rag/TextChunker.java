package com.example.agentdemo.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunker(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("chunkOverlap must be zero or positive");
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String normalized = content.trim();
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }
        List<String> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + chunkSize, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
