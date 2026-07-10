package com.example.agentdemo.knowledge;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * A knowledge base groups documents and carries a retrieval configuration (chunking + top-k).
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false, unique = true, length = 64)
    private String kbId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 1024)
    private String description;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "retrieval_config_json", length = Integer.MAX_VALUE)
    private String retrievalConfigJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeBasePurpose purpose = KnowledgeBasePurpose.BUSINESS;

    @Column(name = "system_managed", nullable = false)
    private boolean systemManaged;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(String kbId, String name, String description, String retrievalConfigJson) {
        this(kbId, name, description, retrievalConfigJson, KnowledgeBasePurpose.BUSINESS, false);
    }

    public KnowledgeBaseEntity(String kbId, String name, String description, String retrievalConfigJson,
            KnowledgeBasePurpose purpose, boolean systemManaged) {
        this.kbId = kbId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.name = name;
        this.description = description;
        this.retrievalConfigJson = retrievalConfigJson;
        this.purpose = purpose;
        this.systemManaged = systemManaged;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (!StringUtils.hasText(ownerId)) {
            ownerId = SecurityIdentity.DEFAULT_OWNER_ID;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (purpose == null) {
            purpose = KnowledgeBasePurpose.BUSINESS;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getKbId() {
        return kbId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getRetrievalConfigJson() {
        return retrievalConfigJson;
    }

    public KnowledgeBasePurpose getPurpose() {
        return purpose;
    }

    public boolean isSystemManaged() {
        return systemManaged;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
