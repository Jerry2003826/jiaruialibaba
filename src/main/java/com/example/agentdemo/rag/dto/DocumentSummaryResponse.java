package com.example.agentdemo.rag.dto;

import com.example.agentdemo.rag.DocumentIndexStatus;

import java.time.Instant;

public record DocumentSummaryResponse(Long id, String title, int contentLength, DocumentIndexStatus indexStatus,
        Instant createdAt) {
}
