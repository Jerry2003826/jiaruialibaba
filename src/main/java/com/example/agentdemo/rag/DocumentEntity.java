package com.example.agentdemo.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "rag_documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 256)
    private String title;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentIndexStatus indexStatus = DocumentIndexStatus.PENDING;

    protected DocumentEntity() {
    }

    public DocumentEntity(String title, String content) {
        this.title = title;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (indexStatus == null) {
            indexStatus = DocumentIndexStatus.PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public DocumentIndexStatus getIndexStatus() {
        return indexStatus;
    }

    public boolean isReady() {
        return indexStatus == DocumentIndexStatus.READY;
    }

    public void markPending() {
        this.indexStatus = DocumentIndexStatus.PENDING;
    }

    public void markReady() {
        this.indexStatus = DocumentIndexStatus.READY;
    }

    public void markFailed() {
        this.indexStatus = DocumentIndexStatus.FAILED;
    }

    public void markDeleting() {
        this.indexStatus = DocumentIndexStatus.DELETING;
    }

    public void markDeleted() {
        this.indexStatus = DocumentIndexStatus.DELETED;
    }

}
