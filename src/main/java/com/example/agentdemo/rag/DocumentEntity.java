package com.example.agentdemo.rag;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "rag_documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(length = 256)
    private String title;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentIndexStatus indexStatus = DocumentIndexStatus.PENDING;

    protected DocumentEntity() {
    }

    public DocumentEntity(String title, String content) {
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.title = title;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        if (!org.springframework.util.StringUtils.hasText(ownerId)) {
            ownerId = SecurityIdentity.DEFAULT_OWNER_ID;
        }
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

    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public void update(String title, String content) {
        this.title = title;
        this.content = content;
        this.indexStatus = DocumentIndexStatus.PENDING;
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
