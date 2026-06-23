package com.example.agentdemo.rag.dto;

import java.time.Instant;

public record DocumentResponse(Long id, String title, int contentLength, Instant createdAt) {
}
