package com.example.agentdemo.rag.dto;

import java.time.Instant;

public record DocumentSummaryResponse(Long id, String title, int contentLength, Instant createdAt) {
}
