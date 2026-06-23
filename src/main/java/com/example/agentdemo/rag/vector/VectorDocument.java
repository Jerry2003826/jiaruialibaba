package com.example.agentdemo.rag.vector;

import java.util.Map;

public record VectorDocument(String id, float[] vector, Map<String, Object> metadata) {
}
