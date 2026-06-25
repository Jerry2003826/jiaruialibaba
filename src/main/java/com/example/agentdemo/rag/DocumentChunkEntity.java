package com.example.agentdemo.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "rag_document_chunks")
public class DocumentChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, length = 128, unique = true)
    private String vectorId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DocumentChunkEntity() {
    }

    public DocumentChunkEntity(Long documentId, int chunkIndex, String vectorId, String content) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.vectorId = vectorId;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getVectorId() { return vectorId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
