package com.example.agentdemo.app.apikey;

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
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * A runtime API key. Only the {@code keyHash} (SHA-256 of the plaintext) is stored; the plaintext
 * is returned once at creation and never persisted.
 */
@Entity
@Table(name = "app_api_keys")
public class AppApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(name = "key_hash", nullable = false, unique = true, length = 128)
    private String keyHash;

    @Column(length = 128)
    private String name;

    @Column(nullable = false, length = 256)
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppApiKeyStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AppApiKeyEntity() {
    }

    public AppApiKeyEntity(String keyId, String appId, String keyHash, String name, String scopes) {
        this.keyId = keyId;
        this.appId = appId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.keyHash = keyHash;
        this.name = name;
        this.scopes = scopes;
        this.status = AppApiKeyStatus.ACTIVE;
    }

    @PrePersist
    void prePersist() {
        if (!StringUtils.hasText(ownerId)) {
            ownerId = SecurityIdentity.DEFAULT_OWNER_ID;
        }
        if (status == null) {
            status = AppApiKeyStatus.ACTIVE;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void revoke() {
        this.status = AppApiKeyStatus.REVOKED;
        this.revokedAt = Instant.now();
    }

    public void markUsed(Instant when) {
        this.lastUsedAt = when;
    }

    public Long getId() {
        return id;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getAppId() {
        return appId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getName() {
        return name;
    }

    public String getScopes() {
        return scopes;
    }

    public AppApiKeyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

}
