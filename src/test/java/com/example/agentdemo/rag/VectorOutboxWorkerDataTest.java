package com.example.agentdemo.rag;

import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the recoverable state machine of {@link VectorOutboxWorker} against a real (H2) database:
 * successful upserts/deletes commit their side effects, failures retry with backoff, an abandoned
 * (lease-expired) PROCESSING event is reclaimed, and a delete whose vector store is unavailable is
 * never reported as done (which would leak vectors).
 */
@DataJpaTest
class VectorOutboxWorkerDataTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private VectorOutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final VectorStoreGateway gateway = mock(VectorStoreGateway.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private VectorOutboxWorker worker;

    @BeforeEach
    void setUp() {
        worker = new VectorOutboxWorker(outboxEventRepository, documentRepository, documentChunkRepository,
                gateway, objectMapper, transactionManager, 60_000L, 50);
    }

    @Test
    void upsertSuccessMarksDocumentReadyAndEventSucceeded() {
        long documentId = saveDocument(DocumentEntity::markPending);
        long eventId = saveUpsert(documentId);

        worker.processPending();

        assertThat(documentRepository.findById(documentId)).get()
                .extracting(DocumentEntity::getIndexStatus).isEqualTo(DocumentIndexStatus.READY);
        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.SUCCEEDED);
        verify(gateway).upsert(anyList());
    }

    @Test
    void upsertCompletingAfterDocumentDeletionEnqueuesCompensatingDelete() {
        // The document is mid-deletion while its upsert was in flight (a PROCESSING upsert that
        // cancelEventsForDocument cannot cancel). The external upsert still writes the vectors, so they
        // must be cleaned up with a compensating delete instead of leaking after the document is gone.
        long documentId = saveDocument(DocumentEntity::markDeleting);
        long eventId = saveUpsert(documentId);
        when(gateway.isConfigured()).thenReturn(true);

        worker.processPending();

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.SUCCEEDED);
        verify(gateway).upsert(anyList());

        List<VectorOutboxEventEntity> compensating = outboxEventRepository.findAll().stream()
                .filter(e -> e.getType() == VectorOutboxEventType.VECTOR_DELETE && e.getDocumentId().equals(documentId))
                .toList();
        assertThat(compensating).hasSize(1);
        assertThat(compensating.getFirst().getStatus()).isEqualTo(VectorOutboxEventStatus.PENDING);
        // The document must not be flipped back to READY by the late upsert.
        assertThat(documentRepository.findById(documentId)).get()
                .extracting(DocumentEntity::getIndexStatus).isEqualTo(DocumentIndexStatus.DELETING);
    }

    @Test
    void upsertFailureKeepsDocumentPendingUntilRetriesAreExhausted() {
        long documentId = saveDocument(DocumentEntity::markPending);
        long eventId = saveUpsert(documentId);
        doThrow(new RuntimeException("dashvector unavailable")).when(gateway).upsert(anyList());

        worker.processPending();

        assertThat(documentRepository.findById(documentId)).get()
                .extracting(DocumentEntity::getIndexStatus).isEqualTo(DocumentIndexStatus.PENDING);
        VectorOutboxEventEntity event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(VectorOutboxEventStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(event.getLeaseExpiresAt()).isNull();
    }

    @Test
    void deleteWithUnconfiguredGatewayFailsInsteadOfLeakingVectors() {
        long documentId = saveDocument(DocumentEntity::markDeleting);
        long eventId = saveDelete(documentId, List.of("doc-" + documentId + "-chunk-0"));
        when(gateway.isConfigured()).thenReturn(false);

        worker.processPending();

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.FAILED);
        assertThat(documentRepository.findById(documentId)).isPresent();
        verify(gateway, never()).delete(anyCollection());
    }

    @Test
    void deleteDeadLetterMarksDocumentFailedInsteadOfLeavingItDeleting() {
        long documentId = saveDocument(DocumentEntity::markDeleting);
        long eventId = saveDelete(documentId, List.of("doc-" + documentId + "-chunk-0"));
        when(gateway.isConfigured()).thenReturn(false);

        for (int attempt = 0; attempt < 5; attempt++) {
            makeRetryImmediatelyClaimable(eventId);
            worker.processPending();
        }

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.DEAD_LETTER);
        assertThat(documentRepository.findById(documentId)).get()
                .extracting(DocumentEntity::getIndexStatus).isEqualTo(DocumentIndexStatus.FAILED);
    }

    @Test
    void vectorDeleteSuccessRemovesOnlyOldChunksAndKeepsUpdatedDocument() {
        long documentId = saveDocument(DocumentEntity::markPending);
        documentChunkRepository.saveAndFlush(
                new DocumentChunkEntity(documentId, 0, "doc-" + documentId + "-chunk-0", "old chunk"));
        long eventId = saveVectorDelete(documentId, List.of("doc-" + documentId + "-chunk-0"));
        when(gateway.isConfigured()).thenReturn(true);

        worker.processPending();

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.SUCCEEDED);
        assertThat(documentRepository.findById(documentId)).isPresent();
        assertThat(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId)).isEmpty();
        verify(gateway).delete(anyCollection());
    }

    @Test
    void deleteSuccessRemovesChunksAndDocument() {
        long documentId = saveDocument(DocumentEntity::markDeleting);
        documentChunkRepository.saveAndFlush(
                new DocumentChunkEntity(documentId, 0, "doc-" + documentId + "-chunk-0", "chunk body"));
        long eventId = saveDelete(documentId, List.of("doc-" + documentId + "-chunk-0"));
        when(gateway.isConfigured()).thenReturn(true);

        worker.processPending();

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.SUCCEEDED);
        assertThat(documentRepository.findById(documentId)).isEmpty();
        assertThat(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId)).isEmpty();
        verify(gateway).delete(anyCollection());
    }

    @Test
    void expiredProcessingLeaseIsReclaimedAndCompleted() {
        long documentId = saveDocument(DocumentEntity::markPending);
        VectorOutboxEventEntity abandoned = VectorOutboxEventEntity.upsert(documentId, upsertPayload(documentId));
        abandoned.claim(Instant.now().minus(5, ChronoUnit.MINUTES));
        long eventId = outboxEventRepository.saveAndFlush(abandoned).getId();

        worker.processPending();

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.SUCCEEDED);
        assertThat(documentRepository.findById(documentId)).get()
                .extracting(DocumentEntity::getIndexStatus).isEqualTo(DocumentIndexStatus.READY);
    }

    @Test
    void cancelEventsForDocumentCancelsQueuedUpsertsButNotInFlight() {
        long documentId = saveDocument(DocumentEntity::markPending);
        long pendingId = saveUpsert(documentId);
        VectorOutboxEventEntity failed = VectorOutboxEventEntity.upsert(documentId, upsertPayload(documentId));
        failed.markFailed(new RuntimeException("transient"));
        long failedId = outboxEventRepository.saveAndFlush(failed).getId();
        VectorOutboxEventEntity inFlight = VectorOutboxEventEntity.upsert(documentId, upsertPayload(documentId));
        inFlight.claim(Instant.now().plusSeconds(60));
        long inFlightId = outboxEventRepository.saveAndFlush(inFlight).getId();

        int canceled = outboxEventRepository.cancelEventsForDocument(documentId, VectorOutboxEventType.UPSERT,
                List.of(VectorOutboxEventStatus.PENDING, VectorOutboxEventStatus.FAILED),
                VectorOutboxEventStatus.CANCELED, Instant.now());

        assertThat(canceled).isEqualTo(2);
        assertThat(outboxEventRepository.findById(pendingId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.CANCELED);
        assertThat(outboxEventRepository.findById(failedId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.CANCELED);
        assertThat(outboxEventRepository.findById(inFlightId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.PROCESSING);
    }

    private long saveDocument(Consumer<DocumentEntity> status) {
        DocumentEntity document = new DocumentEntity("Doc", "content for indexing");
        status.accept(document);
        return documentRepository.saveAndFlush(document).getId();
    }

    private long saveUpsert(long documentId) {
        return outboxEventRepository.saveAndFlush(VectorOutboxEventEntity.upsert(documentId, upsertPayload(documentId)))
                .getId();
    }

    private long saveDelete(long documentId, List<String> vectorIds) {
        return outboxEventRepository.saveAndFlush(VectorOutboxEventEntity.documentDelete(documentId, writeJson(vectorIds)))
                .getId();
    }

    private long saveVectorDelete(long documentId, List<String> vectorIds) {
        return outboxEventRepository.saveAndFlush(VectorOutboxEventEntity.vectorDelete(documentId, writeJson(vectorIds)))
                .getId();
    }

    private void makeRetryImmediatelyClaimable(long eventId) {
        VectorOutboxEventEntity event = outboxEventRepository.findById(eventId).orElseThrow();
        if (event.getStatus() == VectorOutboxEventStatus.FAILED) {
            ReflectionTestUtils.setField(event, "nextAttemptAt", Instant.now().minusSeconds(1));
            outboxEventRepository.saveAndFlush(event);
        }
    }

    private String upsertPayload(long documentId) {
        return writeJson(List.of(new VectorDocument("doc-" + documentId + "-chunk-0", new float[] {0.1f, 0.2f},
                Map.of("documentId", documentId))));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
