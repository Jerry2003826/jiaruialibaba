package com.example.agentdemo.app;

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
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * An application: the product-level wrapper over chat, workflow or agent capabilities. Versioning
 * mirrors the workflow definition model — each edit bumps {@code version} and returns to DRAFT; a
 * separate {@code publishedVersion} pointer records which immutable revision the runtime serves.
 */
@Entity
@Table(name = "apps")
public class AppEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false, unique = true, length = 64)
    private String appId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AppType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AppStatus status;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "config_json", length = Integer.MAX_VALUE)
    private String configJson;

    @Column(name = "workflow_definition_id", length = 64)
    private String workflowDefinitionId;

    @Column(name = "workflow_definition_version")
    private Integer workflowDefinitionVersion;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "published_version")
    private Integer publishedVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected AppEntity() {
    }

    public AppEntity(String appId, String name, String description, AppType type, String configJson,
            String workflowDefinitionId, Integer workflowDefinitionVersion) {
        this.appId = appId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.name = name;
        this.description = description;
        this.type = type;
        this.configJson = configJson;
        this.workflowDefinitionId = workflowDefinitionId;
        this.workflowDefinitionVersion = workflowDefinitionVersion;
        this.version = 1;
        this.status = AppStatus.DRAFT;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (version == null) {
            version = 1;
        }
        if (status == null) {
            status = AppStatus.DRAFT;
        }
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

    public void updateDraft(String name, String description, String configJson, String workflowDefinitionId,
            Integer workflowDefinitionVersion) {
        this.name = name;
        this.description = description;
        this.configJson = configJson;
        this.workflowDefinitionId = workflowDefinitionId;
        this.workflowDefinitionVersion = workflowDefinitionVersion;
        this.version = currentVersion() + 1;
        this.status = AppStatus.DRAFT;
    }

    public void publish(Integer pinnedWorkflowVersion) {
        this.status = AppStatus.PUBLISHED;
        this.publishedVersion = this.version;
        if (pinnedWorkflowVersion != null) {
            this.workflowDefinitionVersion = pinnedWorkflowVersion;
        }
    }

    public void archive() {
        this.status = AppStatus.ARCHIVED;
    }

    private int currentVersion() {
        return version == null ? 0 : version;
    }

    public Long getId() {
        return id;
    }

    public String getAppId() {
        return appId;
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

    public AppType getType() {
        return type;
    }

    public AppStatus getStatus() {
        return status;
    }

    public String getConfigJson() {
        return configJson;
    }

    public String getWorkflowDefinitionId() {
        return workflowDefinitionId;
    }

    public Integer getWorkflowDefinitionVersion() {
        return workflowDefinitionVersion;
    }

    public Integer getVersion() {
        return version;
    }

    public Integer getPublishedVersion() {
        return publishedVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }

}
