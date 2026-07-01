package com.example.agentdemo.usage;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * A single model-call token usage record, associated with a run and optionally an app.
 */
@Entity
@Table(name = "usage_records")
public class UsageRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "app_id", length = 64)
    private String appId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(length = 64)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsageRecordEntity() {
    }

    public UsageRecordEntity(String runId, String appId, String provider, String model, Integer promptTokens,
            Integer completionTokens, Integer totalTokens) {
        this.runId = runId;
        this.appId = appId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    @PrePersist
    void prePersist() {
        if (!StringUtils.hasText(ownerId)) {
            ownerId = SecurityIdentity.DEFAULT_OWNER_ID;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getAppId() {
        return appId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
