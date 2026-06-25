package com.example.agentdemo.rag;

public enum VectorOutboxEventStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    DEAD_LETTER
}
