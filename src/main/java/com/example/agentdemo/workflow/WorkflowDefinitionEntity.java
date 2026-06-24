package com.example.agentdemo.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String definitionId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Lob
    @Column(nullable = false)
    private String definitionJson;

    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowDefinitionStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WorkflowDefinitionEntity() {
    }

    public WorkflowDefinitionEntity(String definitionId, String name, String description, String definitionJson) {
        this.definitionId = definitionId;
        this.name = name;
        this.description = description;
        this.definitionJson = definitionJson;
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
        this.name = name;
        this.description = description;
        this.definitionJson = definitionJson;
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

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDefinitionJson() {
        return definitionJson;
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

}
