package com.example.agentdemo.rag;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface VectorOutboxEventRepository extends JpaRepository<VectorOutboxEventEntity, Long> {

    /**
     * Ids of events that are eligible to be claimed at {@code now}: a fresh attempt whose backoff has
     * elapsed, or a PROCESSING event whose worker lease has expired (crash recovery). Only ids are
     * selected so each candidate can be re-loaded and atomically claimed under optimistic locking.
     */
    @Query("""
            select e.id from VectorOutboxEventEntity e
            where (e.status in :readyStatuses and e.nextAttemptAt <= :now)
               or (e.status = :processingStatus and e.leaseExpiresAt < :now)
            order by e.createdAt asc, e.id asc
            """)
    List<Long> findClaimableEventIds(
            @Param("readyStatuses") Collection<VectorOutboxEventStatus> readyStatuses,
            @Param("processingStatus") VectorOutboxEventStatus processingStatus,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Cancels still-queued events of a given type for a document. Used when a document is deleted so
     * that an obsolete UPSERT cannot resurrect vectors after the delete. PROCESSING events are left
     * untouched on purpose: an in-flight worker owns them and will finalize under its lease.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VectorOutboxEventEntity e
               set e.status = :canceledStatus,
                   e.updatedAt = :now
             where e.documentId = :documentId
               and e.type = :type
               and e.status in :activeStatuses
            """)
    int cancelEventsForDocument(
            @Param("documentId") Long documentId,
            @Param("type") VectorOutboxEventType type,
            @Param("activeStatuses") Collection<VectorOutboxEventStatus> activeStatuses,
            @Param("canceledStatus") VectorOutboxEventStatus canceledStatus,
            @Param("now") Instant now);

}
