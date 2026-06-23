package com.example.agentdemo.rag.dto;

public record RetrievedContext(Long documentId, String title, String snippet, double score) {
}
