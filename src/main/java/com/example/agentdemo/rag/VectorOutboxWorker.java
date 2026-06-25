package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drains the transactional vector outbox into the configured {@link VectorStoreGateway}.
 *
 * <p>Each event is processed as a recoverable state machine: it is first <em>claimed</em> in a short
 * transaction (status {@code PROCESSING} + a time-bounded lease, guarded by optimistic locking so two
 * workers cannot own the same event), then the external vector-store call runs <em>outside</em> any
 * database transaction, and finally the result is committed in a second short transaction. If a worker
 * dies mid-flight the lease expires and another worker reclaims the event, so no event is lost or
 * silently dropped.
 */
@Component
public class VectorOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(VectorOutboxWorker.class);

    private static final TypeReference<List<VectorDocument>> VECTOR_DOCUMENTS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> VECTOR_IDS_TYPE = new TypeReference<>() {
    };
    private static final List<VectorOutboxEventStatus> READY_STATUSES = List.of(
            VectorOutboxEventStatus.PENDING,
            VectorOutboxEventStatus.FAILED);

    private final VectorOutboxEventRepository outboxEventRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final VectorStoreGateway vectorStoreGateway;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final long leaseMs;
    private final int batchSize;

    public VectorOutboxWorker(VectorOutboxEventRepository outboxEventRepository, DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository, VectorStoreGateway vectorStoreGateway,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            @Value("${demo.rag.outbox.lease-ms:60000}") long leaseMs,
            @Value("${demo.rag.outbox.batch-size:50}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.vectorStoreGateway = vectorStoreGateway;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.leaseMs = leaseMs;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${demo.rag.outbox.poll-ms:1000}")
    public void processPending() {
        Instant now = Instant.now();
        List<Long> candidateIds = outboxEventRepository.findClaimableEventIds(
                READY_STATUSES, VectorOutboxEventStatus.PROCESSING, now, PageRequest.of(0, batchSize));
        for (Long id : candidateIds) {
            try {
                processCandidate(id);
            }
            catch (RuntimeException ex) {
                log.warn("Vector outbox event {} processing aborted unexpectedly", id, ex);
            }
        }
    }

    void processCandidate(Long id) {
        VectorOutboxEventEntity claimed = claim(id, Instant.now());
        if (claimed == null) {
            return;
        }
        try {
            performExternalEffect(claimed);
            finalizeSuccess(id);
        }
        catch (RuntimeException ex) {
            log.warn("Vector outbox event {} ({}) failed; scheduling retry", id, claimed.getType(), ex);
            finalizeFailure(id, ex);
        }
    }

    private VectorOutboxEventEntity claim(Long id, Instant now) {
        try {
            return transactionTemplate.execute(status -> {
                VectorOutboxEventEntity event = outboxEventRepository.findById(id).orElse(null);
                if (event == null || !event.isClaimable(now)) {
                    return null;
                }
                event.claim(now.plusMillis(leaseMs));
                return outboxEventRepository.saveAndFlush(event);
            });
        }
        catch (OptimisticLockingFailureException | CannotAcquireLockException ex) {
            // Another worker claimed this event between the candidate scan and our optimistic update.
            return null;
        }
    }

    private void performExternalEffect(VectorOutboxEventEntity event) {
        if (event.getType() == VectorOutboxEventType.UPSERT) {
            List<VectorDocument> vectorDocuments = readPayload(event.getPayloadJson(), VECTOR_DOCUMENTS_TYPE);
            if (!vectorDocuments.isEmpty()) {
                vectorStoreGateway.ensureCollection();
                vectorStoreGateway.upsert(vectorDocuments);
            }
            return;
        }
        List<String> vectorIds = readPayload(event.getPayloadJson(), VECTOR_IDS_TYPE);
        if (vectorIds.isEmpty()) {
            return;
        }
        if (!vectorStoreGateway.isConfigured()) {
            // Never mark a delete succeeded while its vectors are still live in the store: that would
            // leak them permanently. Fail instead so the event is retried (and eventually dead-lettered).
            throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED",
                    "Cannot delete vectors because the vector store is not configured");
        }
        vectorStoreGateway.delete(vectorIds);
    }

    private void finalizeSuccess(Long id) {
        runFinalize(id, event -> {
            if (event.getType() == VectorOutboxEventType.UPSERT) {
                finalizeUpsertSuccess(event);
            }
            else {
                documentChunkRepository.deleteByDocumentId(event.getDocumentId());
                documentRepository.findById(event.getDocumentId()).ifPresent(documentRepository::delete);
            }
            event.markSucceeded();
        });
    }

    private void finalizeUpsertSuccess(VectorOutboxEventEntity event) {
        DocumentEntity document = documentRepository.findById(event.getDocumentId()).orElse(null);
        if (document != null && document.getIndexStatus() != DocumentIndexStatus.DELETING
                && document.getIndexStatus() != DocumentIndexStatus.DELETED) {
            document.markReady();
            documentRepository.save(document);
            return;
        }
        // The document was deleted (or is being deleted) while this upsert was in flight. Under multiple
        // workers a concurrent DELETE event can run its external delete before this upsert's external
        // write lands, which would leave the freshly-written vectors orphaned in the store. A queued
        // UPSERT is canceled on delete, but a PROCESSING one cannot be (its worker owns it) — this is
        // exactly that case — so enqueue a compensating delete to remove the orphaned vectors.
        enqueueCompensatingDelete(event);
    }

    private void enqueueCompensatingDelete(VectorOutboxEventEntity upsertEvent) {
        List<String> vectorIds = readPayload(upsertEvent.getPayloadJson(), VECTOR_DOCUMENTS_TYPE).stream()
                .map(VectorDocument::id)
                .toList();
        if (vectorIds.isEmpty()) {
            return;
        }
        outboxEventRepository.save(VectorOutboxEventEntity.delete(upsertEvent.getDocumentId(), writePayload(vectorIds)));
        log.warn("Vector outbox upsert for document {} completed after the document was deleted; enqueued a "
                + "compensating delete for {} orphaned vector(s)", upsertEvent.getDocumentId(), vectorIds.size());
    }

    private void finalizeFailure(Long id, RuntimeException failure) {
        runFinalize(id, event -> {
            if (event.getType() == VectorOutboxEventType.UPSERT) {
                documentRepository.findById(event.getDocumentId()).ifPresent(document -> {
                    document.markFailed();
                    documentRepository.save(document);
                });
            }
            event.markFailed(failure);
        });
    }

    private void runFinalize(Long id, Consumer<VectorOutboxEventEntity> mutation) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                VectorOutboxEventEntity event = outboxEventRepository.findById(id).orElse(null);
                if (event == null || event.getStatus() != VectorOutboxEventStatus.PROCESSING) {
                    // Already finalized, or the lease expired and another worker reclaimed it.
                    return;
                }
                mutation.accept(event);
                outboxEventRepository.saveAndFlush(event);
            });
        }
        catch (OptimisticLockingFailureException ex) {
            log.debug("Vector outbox event {} was reclaimed before finalize; leaving it to the new owner", id);
        }
    }

    private <T> T readPayload(String payloadJson, TypeReference<T> type) {
        try {
            return objectMapper.readValue(payloadJson, type);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid vector outbox payload", ex);
        }
    }

    private String writePayload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize compensating vector delete payload", ex);
        }
    }

}
