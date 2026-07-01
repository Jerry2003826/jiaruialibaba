package com.example.agentdemo.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single immutable audit record. No prompt content or secrets are stored here; only action
 * metadata (actor, action, resource, outcome).
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 32)
    private AuditActorType actorType;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(String ownerId, AuditActorType actorType, String actorId, String action,
            String resourceType, String resourceId, String ip, String userAgent, String requestId,
            boolean success, String errorCode) {
        this.ownerId = ownerId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.ip = ip;
        this.userAgent = userAgent;
        this.requestId = requestId;
        this.success = success;
        this.errorCode = errorCode;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
