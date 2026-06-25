package com.example.agentdemo.rag.dto;

import com.example.agentdemo.rag.DocumentIndexStatus;

import java.time.Instant;

public record DocumentDetailResponse(Long id, String title, String content, DocumentIndexStatus indexStatus,
        Instant createdAt) {
}
