package com.example.agentdemo.rag;

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
@Table(name = "vector_outbox_events")
public class VectorOutboxEventEntity {

    private static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VectorOutboxEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VectorOutboxEventStatus status = VectorOutboxEventStatus.PENDING;

    @Column(nullable = false)
    private Long documentId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String payloadJson;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    // Lease held by the worker currently processing this event. A PROCESSING event whose lease has
    // expired is considered abandoned (the worker crashed) and may be reclaimed by another worker.
    private Instant leaseExpiresAt;

    @Column(length = 2048)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long rowVersion;

    protected VectorOutboxEventEntity() {
    }

    private VectorOutboxEventEntity(VectorOutboxEventType type, Long documentId, String payloadJson) {
        this.type = type;
        this.documentId = documentId;
        this.payloadJson = payloadJson;
        this.nextAttemptAt = Instant.now();
    }

    public static VectorOutboxEventEntity upsert(Long documentId, String payloadJson) {
        return new VectorOutboxEventEntity(VectorOutboxEventType.UPSERT, documentId, payloadJson);
    }

    public static VectorOutboxEventEntity vectorDelete(Long documentId, String payloadJson) {
        return new VectorOutboxEventEntity(VectorOutboxEventType.VECTOR_DELETE, documentId, payloadJson);
    }

    public static VectorOutboxEventEntity documentDelete(Long documentId, String payloadJson) {
        return new VectorOutboxEventEntity(VectorOutboxEventType.DOCUMENT_DELETE, documentId, payloadJson);
    }

    /**
     * Legacy alias: old DELETE events represented full document deletion.
     */
    public static VectorOutboxEventEntity delete(Long documentId, String payloadJson) {
        return documentDelete(documentId, payloadJson);
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public VectorOutboxEventType getType() {
        return type;
    }

    public VectorOutboxEventStatus getStatus() {
        return status;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Whether this event may be claimed for processing at {@code now}: a fresh attempt whose backoff
     * has elapsed, or a PROCESSING event whose worker lease has expired (crash recovery).
     */
    public boolean isClaimable(Instant now) {
        return switch (status) {
            case PENDING, FAILED -> !nextAttemptAt.isAfter(now);
            case PROCESSING -> leaseExpiresAt != null && leaseExpiresAt.isBefore(now);
            case SUCCEEDED, DEAD_LETTER, CANCELED -> false;
        };
    }

    public void claim(Instant leaseExpiry) {
        this.status = VectorOutboxEventStatus.PROCESSING;
        this.leaseExpiresAt = leaseExpiry;
    }

    public void markSucceeded() {
        this.status = VectorOutboxEventStatus.SUCCEEDED;
        this.lastError = null;
        this.leaseExpiresAt = null;
    }

    public void markFailed(RuntimeException failure) {
        this.attempts++;
        this.status = attempts >= MAX_ATTEMPTS ? VectorOutboxEventStatus.DEAD_LETTER : VectorOutboxEventStatus.FAILED;
        this.lastError = abbreviate(failure.getMessage());
        this.nextAttemptAt = Instant.now().plusSeconds(Math.min(300, (long) Math.pow(2, attempts)));
        this.leaseExpiresAt = null;
    }

    private String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 2048 ? message : message.substring(0, 2048);
    }

}
