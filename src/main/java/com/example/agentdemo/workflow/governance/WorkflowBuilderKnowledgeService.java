package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.common.PublicIdGenerator;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.knowledge.KnowledgeBaseEntity;
import com.example.agentdemo.knowledge.KnowledgeBasePurpose;
import com.example.agentdemo.knowledge.KnowledgeBaseRepository;
import com.example.agentdemo.knowledge.KnowledgeIngestionService;
import com.example.agentdemo.knowledge.KnowledgeSearchService;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.security.SecurityIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class WorkflowBuilderKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowBuilderKnowledgeService.class);
    private static final String DOCUMENT_TITLE_PREFIX = "Workflow Builder Guidance: ";
    private static final String MANAGED_KB_NAME = "Workflow Builder Guidance";
    private static final String MANAGED_KB_DESCRIPTION =
            "System-managed governance guidance for workflow generation.";
    private static final int MAX_TOP_K = 6;
    private static final int SCAN_PAGE_SIZE = 200;
    private static final List<DocumentIndexStatus> NON_RETRIEVABLE = List.of(
            DocumentIndexStatus.DELETING, DocumentIndexStatus.DELETED);

    private final WorkflowRuleCatalog workflowRuleCatalog;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final DocumentManagementService documentManagementService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final PublicIdGenerator publicIdGenerator;
    private final ConcurrentMap<String, OwnerSynchronizationLock> ownerSynchronizationLocks = new ConcurrentHashMap<>();

    public WorkflowBuilderKnowledgeService(WorkflowRuleCatalog workflowRuleCatalog,
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentRepository documentRepository,
            KnowledgeIngestionService knowledgeIngestionService,
            DocumentManagementService documentManagementService,
            KnowledgeSearchService knowledgeSearchService,
            PublicIdGenerator publicIdGenerator) {
        this.workflowRuleCatalog = workflowRuleCatalog;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.documentManagementService = documentManagementService;
        this.knowledgeSearchService = knowledgeSearchService;
        this.publicIdGenerator = publicIdGenerator;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Citation> retrieve(String domain, String query, int topK) {
        String ownerId = SecurityIdentity.currentOwnerId();
        OwnerSynchronizationLock ownerLock = null;
        try {
            KnowledgeBaseEntity knowledgeBase;
            List<WorkflowRulePack> activePacks = workflowRuleCatalog.activePacks(domain);
            ownerLock = acquireOwnerLock(ownerId);
            ownerLock.lock.lock();
            try {
                knowledgeBase = ensureManagedKnowledgeBase(ownerId);
                synchronizeGuidance(ownerId, knowledgeBase.getKbId(), workflowRuleCatalog.allPacks());
            }
            finally {
                ownerLock.lock.unlock();
                releaseOwnerLock(ownerId, ownerLock);
                ownerLock = null;
            }
            int boundedTopK = Math.max(1, Math.min(topK, MAX_TOP_K));
            return knowledgeSearchService.searchManaged(
                    knowledgeBase.getKbId(), query, boundedTopK, documentTitles(activePacks)).citations();
        }
        catch (RuntimeException exception) {
            if (ownerLock != null) {
                releaseOwnerLock(ownerId, ownerLock);
            }
            log.warn("Workflow builder knowledge retrieval failed for owner {}", ownerId, exception);
            return List.of();
        }
    }

    private Set<String> documentTitles(List<WorkflowRulePack> packs) {
        return packs.stream()
                .flatMap(pack -> pack.rules().stream()
                        .map(rule -> DOCUMENT_TITLE_PREFIX + pack.id() + "/" + rule.id()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private OwnerSynchronizationLock acquireOwnerLock(String ownerId) {
        return ownerSynchronizationLocks.compute(ownerId, (ignored, current) -> {
            OwnerSynchronizationLock resolved = current == null ? new OwnerSynchronizationLock() : current;
            resolved.references++;
            return resolved;
        });
    }

    private void releaseOwnerLock(String ownerId, OwnerSynchronizationLock released) {
        ownerSynchronizationLocks.computeIfPresent(ownerId, (ignored, current) -> {
            if (current != released) {
                return current;
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    String guidanceContentForTest(WorkflowRulePack pack, WorkflowGovernanceRule rule) {
        return buildGuidanceContent(pack, rule);
    }

    String contentHashForTest(String content) {
        return contentHash(content);
    }

    int ownerLockCountForTest() {
        return ownerSynchronizationLocks.size();
    }

    private KnowledgeBaseEntity ensureManagedKnowledgeBase(String ownerId) {
        return knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(ownerId,
                KnowledgeBasePurpose.WORKFLOW_BUILDER)
                .orElseGet(() -> createManagedKnowledgeBase(ownerId));
    }

    private KnowledgeBaseEntity createManagedKnowledgeBase(String ownerId) {
        try {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity(publicIdGenerator.next("kb"), MANAGED_KB_NAME,
                    MANAGED_KB_DESCRIPTION, null, KnowledgeBasePurpose.WORKFLOW_BUILDER, true);
            return knowledgeBaseRepository.saveAndFlush(entity);
        }
        catch (DataIntegrityViolationException exception) {
            // saveAndFlush owns its transaction. Once it rolls back, a concurrent creator can be
            // read safely without leaving this service in a rollback-only outer transaction.
            return knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(ownerId,
                    KnowledgeBasePurpose.WORKFLOW_BUILDER)
                    .orElseThrow(() -> exception);
        }
    }

    private void synchronizeGuidance(String ownerId, String kbId, List<WorkflowRulePack> packs) {
        Map<String, ManagedGuidanceDocument> desiredDocuments = desiredDocuments(packs);
        Map<String, List<DocumentEntity>> existingByTitle = new LinkedHashMap<>();
        for (DocumentEntity existing : loadExistingDocuments(ownerId, kbId)) {
            existingByTitle.computeIfAbsent(existing.getTitle(), ignored -> new ArrayList<>()).add(existing);
        }

        for (Map.Entry<String, List<DocumentEntity>> entry : existingByTitle.entrySet()) {
            ManagedGuidanceDocument desired = desiredDocuments.remove(entry.getKey());
            List<DocumentEntity> duplicates = entry.getValue();
            if (desired == null) {
                duplicates.forEach(this::deleteDocument);
                continue;
            }

            DocumentEntity primary = duplicates.getFirst();
            duplicates.stream().skip(1).forEach(this::deleteDocument);
            if (Objects.equals(primary.getContentHash(), desired.contentHash())) {
                continue;
            }

            updateManagedDocument(primary, kbId, desired);
        }

        for (ManagedGuidanceDocument desired : desiredDocuments.values()) {
            ingestManagedDocument(ownerId, kbId, desired);
        }
    }

    private Map<String, ManagedGuidanceDocument> desiredDocuments(List<WorkflowRulePack> packs) {
        Map<String, ManagedGuidanceDocument> documents = new LinkedHashMap<>();
        for (WorkflowRulePack pack : packs) {
            for (WorkflowGovernanceRule rule : pack.rules()) {
                String title = DOCUMENT_TITLE_PREFIX + pack.id() + "/" + rule.id();
                String content = buildGuidanceContent(pack, rule);
                documents.put(title, new ManagedGuidanceDocument(title, content, contentHash(content)));
            }
        }
        return documents;
    }

    private void ingestManagedDocument(String ownerId, String kbId, ManagedGuidanceDocument desired) {
        try {
            knowledgeIngestionService.addManagedTextDocument(kbId, desired.title(), desired.content());
        }
        catch (DataIntegrityViolationException exception) {
            boolean concurrentWinnerMatches = loadExistingDocuments(ownerId, kbId).stream()
                    .anyMatch(document -> Objects.equals(document.getTitle(), desired.title())
                            && Objects.equals(document.getContentHash(), desired.contentHash()));
            if (!concurrentWinnerMatches) {
                throw exception;
            }
        }
    }

    private void updateManagedDocument(DocumentEntity document, String kbId, ManagedGuidanceDocument desired) {
        byte[] contentBytes = desired.content().getBytes(StandardCharsets.UTF_8);
        document.update(desired.title(), desired.content());
        document.assignKnowledge(
                kbId,
                DocumentEntity.WORKFLOW_BUILDER_SOURCE_TYPE,
                null,
                "text/plain",
                (long) contentBytes.length,
                desired.contentHash());
        document.markReady();
        documentRepository.saveAndFlush(document);
    }

    private void deleteDocument(DocumentEntity document) {
        if (document.getId() != null) {
            documentManagementService.deleteManagedDocument(document.getId());
        }
    }

    private List<DocumentEntity> loadExistingDocuments(String ownerId, String kbId) {
        List<DocumentEntity> documents = new ArrayList<>();
        int pageNumber = 0;
        Page<DocumentEntity> page;
        do {
            page = documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                    ownerId, kbId, DocumentEntity.WORKFLOW_BUILDER_SOURCE_TYPE, NON_RETRIEVABLE,
                    PageRequest.of(pageNumber++, SCAN_PAGE_SIZE, Sort.by("id").ascending()));
            documents.addAll(page.getContent());
        }
        while (page.hasNext());
        return documents;
    }

    private String buildGuidanceContent(WorkflowRulePack pack, WorkflowGovernanceRule rule) {
        StringBuilder builder = new StringBuilder();
        append(builder, "Pack: " + pack.id());
        append(builder, "Rule: " + rule.id());
        append(builder, "Title: " + rule.title());
        append(builder, "Retrieval mode: hidden keyword-only");
        append(builder, "Severity: " + rule.severity());
        append(builder, "Detector: " + rule.detector());
        append(builder, "Rationale:");
        append(builder, rule.description());
        appendList(builder, "Anti-patterns:", rule.antiPatterns());
        appendList(builder, "Examples:", rule.examples());
        append(builder, "Repair hint:");
        append(builder, rule.repairHint());
        appendList(builder, "Domains:", pack.domains());
        appendList(builder, "Pack guidance:", pack.knowledgeEntries());
        return builder.toString().trim();
    }

    private void appendList(StringBuilder builder, String heading, List<String> values) {
        append(builder, heading);
        values.forEach(value -> append(builder, "- " + value));
    }

    private void append(StringBuilder builder, String line) {
        if (line != null && !line.isBlank()) {
            builder.append(line).append('\n');
        }
    }

    private String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ManagedGuidanceDocument(String title, String content, String contentHash) {
    }

    private static final class OwnerSynchronizationLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
