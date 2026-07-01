package com.example.agentdemo.rag;

public enum VectorOutboxEventType {
    UPSERT,
    VECTOR_DELETE,
    DOCUMENT_DELETE,
    /**
     * Legacy value kept so already queued DELETE rows continue to mean document deletion.
     */
    DELETE
}
