package com.example.agentdemo.workflow.http;

import com.example.agentdemo.security.SecurityIdentity;
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
import org.springframework.util.StringUtils;

import java.time.Instant;

@Entity
@Table(name = "workflow_http_credentials")
public class HttpCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credential_id", nullable = false, unique = true, length = 64)
    private String credentialId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String type;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "encrypted_payload", nullable = false, length = Integer.MAX_VALUE)
    private String encryptedPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HttpCredentialEntity() {
    }

    HttpCredentialEntity(String credentialId, String name, String type, String encryptedPayload) {
        this.credentialId = credentialId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.name = name;
        this.type = type;
        this.encryptedPayload = encryptedPayload;
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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    void update(String name, String type, String encryptedPayload) {
        this.name = name;
        this.type = type;
        this.encryptedPayload = encryptedPayload;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    String getEncryptedPayload() {
        return encryptedPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
