package com.example.agentdemo.rag;

import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Component
public class VectorOutboxWorker {

    private static final TypeReference<List<VectorDocument>> VECTOR_DOCUMENTS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> VECTOR_IDS_TYPE = new TypeReference<>() {
    };
    private static final List<VectorOutboxEventStatus> CLAIMABLE_STATUSES = List.of(
            VectorOutboxEventStatus.PENDING,
            VectorOutboxEventStatus.FAILED);

    private final VectorOutboxEventRepository outboxEventRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final VectorStoreGateway vectorStoreGateway;
    private final ObjectMapper objectMapper;

    public VectorOutboxWorker(VectorOutboxEventRepository outboxEventRepository, DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository, VectorStoreGateway vectorStoreGateway,
            ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.vectorStoreGateway = vectorStoreGateway;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${demo.rag.outbox.poll-ms:1000}")
    public void processPending() {
        List<VectorOutboxEventEntity> events = outboxEventRepository
                .findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        CLAIMABLE_STATUSES, Instant.now());
        for (VectorOutboxEventEntity event : events) {
            process(event);
        }
    }

    void process(VectorOutboxEventEntity event) {
        event.markProcessing();
        outboxEventRepository.saveAndFlush(event);
        try {
            if (event.getType() == VectorOutboxEventType.UPSERT) {
                processUpsert(event);
            }
            else if (event.getType() == VectorOutboxEventType.DELETE) {
                processDelete(event);
            }
            event.markSucceeded();
            outboxEventRepository.save(event);
        }
        catch (RuntimeException ex) {
            markDocumentFailed(event);
            event.markFailed(ex);
            outboxEventRepository.save(event);
        }
    }

    private void processUpsert(VectorOutboxEventEntity event) {
        List<VectorDocument> vectorDocuments = readPayload(event.getPayloadJson(), VECTOR_DOCUMENTS_TYPE);
        if (!vectorDocuments.isEmpty()) {
            vectorStoreGateway.ensureCollection();
            vectorStoreGateway.upsert(vectorDocuments);
        }
        documentRepository.findById(event.getDocumentId()).ifPresent(document -> {
            document.markReady();
            documentRepository.save(document);
        });
    }

    private void processDelete(VectorOutboxEventEntity event) {
        List<String> vectorIds = readPayload(event.getPayloadJson(), VECTOR_IDS_TYPE);
        deleteVectors(vectorIds);
        documentChunkRepository.deleteByDocumentId(event.getDocumentId());
        documentRepository.findById(event.getDocumentId()).ifPresent(document -> {
            document.markDeleted();
            documentRepository.delete(document);
        });
    }

    private void deleteVectors(Collection<String> vectorIds) {
        if (!vectorIds.isEmpty() && vectorStoreGateway.isConfigured()) {
            vectorStoreGateway.delete(vectorIds);
        }
    }

    private void markDocumentFailed(VectorOutboxEventEntity event) {
        if (event.getType() != VectorOutboxEventType.UPSERT) {
            return;
        }
        documentRepository.findById(event.getDocumentId()).ifPresent(document -> {
            document.markFailed();
            documentRepository.save(document);
        });
    }

    private <T> T readPayload(String payloadJson, TypeReference<T> type) {
        try {
            return objectMapper.readValue(payloadJson, type);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid vector outbox payload", ex);
        }
    }

}
