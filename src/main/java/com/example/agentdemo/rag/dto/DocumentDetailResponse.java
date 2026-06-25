package com.example.agentdemo.rag.dto;

import java.time.Instant;

public record DocumentDetailResponse(Long id, String title, String content, Instant createdAt) {
}
