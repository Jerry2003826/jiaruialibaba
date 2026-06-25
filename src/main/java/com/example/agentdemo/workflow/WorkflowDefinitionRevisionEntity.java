package com.example.agentdemo.workflow;

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
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "workflow_definition_revisions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"definitionId", "version"}))
public class WorkflowDefinitionRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String definitionId;

    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowDefinitionStatus status;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String definitionJson;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WorkflowDefinitionRevisionEntity() {
    }

    public WorkflowDefinitionRevisionEntity(String definitionId, Integer version, WorkflowDefinitionStatus status,
            String name, String description, String definitionJson) {
        this.definitionId = definitionId;
        this.version = version;
        this.status = status;
        this.name = name;
        this.description = description;
        this.definitionJson = definitionJson;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
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

    void markPublished() {
        status = WorkflowDefinitionStatus.PUBLISHED;
    }

    public Long getId() {
        return id;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public Integer getVersion() {
        return version;
    }

    public WorkflowDefinitionStatus getStatus() {
        return status;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
