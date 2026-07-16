package com.example.agentdemo.workflow;

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

import java.time.Instant;

@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String definitionId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String definitionJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "layout_json", length = Integer.MAX_VALUE)
    private String layoutJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "variables_json", length = Integer.MAX_VALUE)
    private String variablesJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "locked_spec_json", length = Integer.MAX_VALUE)
    private String lockedSpecJson;

    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowDefinitionStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long rowVersion;

    protected WorkflowDefinitionEntity() {
    }

    public WorkflowDefinitionEntity(String definitionId, String name, String description, String definitionJson) {
        this(definitionId, name, description, definitionJson, null);
    }

    public WorkflowDefinitionEntity(String definitionId, String name, String description, String definitionJson,
            String lockedSpecJson) {
        this.definitionId = definitionId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.name = name;
        this.description = description;
        this.definitionJson = definitionJson;
        this.lockedSpecJson = lockedSpecJson;
        this.version = 1;
        this.status = WorkflowDefinitionStatus.DRAFT;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (version == null) {
            version = 1;
        }
        if (status == null) {
            status = WorkflowDefinitionStatus.DRAFT;
        }
        if (!org.springframework.util.StringUtils.hasText(ownerId)) {
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

    void updateDraft(String name, String description, String definitionJson) {
        updateDraft(name, description, definitionJson, lockedSpecJson);
    }

    void updateDraft(String name, String description, String definitionJson, String lockedSpecJson) {
        this.name = name;
        this.description = description;
        this.definitionJson = definitionJson;
        this.lockedSpecJson = lockedSpecJson;
        this.version = currentVersion() + 1;
        this.status = WorkflowDefinitionStatus.DRAFT;
    }

    void publish() {
        this.status = WorkflowDefinitionStatus.PUBLISHED;
    }

    private int currentVersion() {
        return version == null ? 0 : version;
    }

    public Long getId() {
        return id;
    }

    public String getDefinitionId() {
        return definitionId;
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

    public String getDefinitionJson() {
        return definitionJson;
    }

    public String getLayoutJson() {
        return layoutJson;
    }

    public void setLayoutJson(String layoutJson) {
        this.layoutJson = layoutJson;
    }

    public String getVariablesJson() {
        return variablesJson;
    }

    public void setVariablesJson(String variablesJson) {
        this.variablesJson = variablesJson;
    }

    public String getLockedSpecJson() {
        return lockedSpecJson;
    }

    public void setLockedSpecJson(String lockedSpecJson) {
        this.lockedSpecJson = lockedSpecJson;
    }

    public Integer getVersion() {
        return version;
    }

    public WorkflowDefinitionStatus getStatus() {
        return status;
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
