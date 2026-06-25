package com.example.agentdemo.chat.dto;

import java.time.Instant;

public record HealthResponse(String status, Instant currentTime, boolean modelConfigured, String model,
        boolean embeddingConfigured, boolean vectorStoreConfigured, String ragRetriever, boolean strictMode,
        boolean fallbackEnabled, boolean keywordFallbackEnabled, long indexedDocumentCount, boolean mcpEnabled) {
}
