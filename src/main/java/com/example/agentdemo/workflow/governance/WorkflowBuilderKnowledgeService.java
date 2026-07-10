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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class WorkflowBuilderKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowBuilderKnowledgeService.class);
    private static final int MAX_TOP_K = 6;
    private static final int SCAN_PAGE_SIZE = 200;
    private static final List<DocumentIndexStatus> NON_RETRIEVABLE = List.of(
            DocumentIndexStatus.DELETING, DocumentIndexStatus.DELETED);
    private static final String MANAGED_KB_NAME = "Workflow Builder Guidance";
    private static final String MANAGED_KB_DESCRIPTION = "System-managed governance guidance for workflow generation.";

    private final WorkflowRuleCatalog workflowRuleCatalog;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final DocumentManagementService documentManagementService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final PublicIdGenerator publicIdGenerator;

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

    @Transactional
    public List<Citation> retrieve(String domain, String query, int topK) {
        try {
            String ownerId = SecurityIdentity.currentOwnerId();
            KnowledgeBaseEntity kb = ensureManagedKnowledgeBase(ownerId);
            synchronizeGuidance(ownerId, kb.getKbId(), workflowRuleCatalog.activePacks(domain));
            int boundedTopK = Math.max(1, Math.min(topK, MAX_TOP_K));
            return knowledgeSearchService.searchManaged(kb.getKbId(), query, boundedTopK).citations();
        }
        catch (RuntimeException exception) {
            log.warn("Workflow builder knowledge retrieval failed for owner {}", SecurityIdentity.currentOwnerId(),
                    exception);
            return List.of();
        }
    }

    String guidanceContentForTest(WorkflowRulePack pack) {
        return buildGuidanceContent(pack);
    }

    String contentHashForTest(String content) {
        return contentHash(content);
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
            return knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(ownerId,
                    KnowledgeBasePurpose.WORKFLOW_BUILDER)
                    .orElseThrow(() -> exception);
        }
    }

    private void synchronizeGuidance(String ownerId, String kbId, List<WorkflowRulePack> packs) {
        Map<String, ManagedGuidanceDocument> desiredDocuments = new LinkedHashMap<>();
        for (WorkflowRulePack pack : packs) {
            String title = "Workflow Builder Guidance: " + pack.id() + "@" + pack.version();
            String content = buildGuidanceContent(pack);
            desiredDocuments.put(title, new ManagedGuidanceDocument(title, content, contentHash(content)));
        }

        List<DocumentEntity> existingDocuments = loadExistingDocuments(ownerId, kbId);
        Map<String, List<DocumentEntity>> existingByTitle = new LinkedHashMap<>();
        for (DocumentEntity existing : existingDocuments) {
            existingByTitle.computeIfAbsent(existing.getTitle(), ignored -> new ArrayList<>()).add(existing);
        }

        for (Map.Entry<String, List<DocumentEntity>> entry : existingByTitle.entrySet()) {
            ManagedGuidanceDocument desired = desiredDocuments.get(entry.getKey());
            List<DocumentEntity> duplicates = entry.getValue();
            if (desired == null) {
                duplicates.forEach(document -> documentManagementService.deleteDocument(document.getId()));
                continue;
            }
            DocumentEntity primary = duplicates.get(0);
            for (int index = 1; index < duplicates.size(); index++) {
                documentManagementService.deleteDocument(duplicates.get(index).getId());
            }
            if (!Objects.equals(primary.getContentHash(), desired.contentHash())) {
                documentManagementService.deleteDocument(primary.getId());
                knowledgeIngestionService.addManagedTextDocument(kbId, desired.title(), desired.content());
            }
            desiredDocuments.remove(entry.getKey());
        }

        for (ManagedGuidanceDocument desired : desiredDocuments.values()) {
            knowledgeIngestionService.addManagedTextDocument(kbId, desired.title(), desired.content());
        }
    }

    private List<DocumentEntity> loadExistingDocuments(String ownerId, String kbId) {
        List<DocumentEntity> documents = new ArrayList<>();
        int pageNumber = 0;
        Page<DocumentEntity> page;
        do {
            page = documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(ownerId, kbId, NON_RETRIEVABLE,
                    PageRequest.of(pageNumber++, SCAN_PAGE_SIZE, Sort.by("id").ascending()));
            documents.addAll(page.getContent());
        }
        while (page.hasNext());
        return documents;
    }

    private String buildGuidanceContent(WorkflowRulePack pack) {
        StringBuilder builder = new StringBuilder();
        append(builder, "Pack: " + pack.id());
        append(builder, "Version: " + pack.version());
        if (!pack.domains().isEmpty()) {
            append(builder, "Domains: " + String.join(", ", pack.domains()));
        }
        append(builder, "Knowledge:");
        for (String entry : pack.knowledgeEntries()) {
            append(builder, "- " + entry);
        }
        append(builder, "Rules:");
        for (WorkflowGovernanceRule rule : pack.rules()) {
            append(builder, "* " + rule.title());
            append(builder, "  Severity: " + rule.severity());
            append(builder, "  Description: " + rule.description());
            append(builder, "  Repair hint: " + rule.repairHint());
        }
        return builder.toString().trim();
    }

    private void append(StringBuilder builder, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        builder.append(line).append('\n');
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
}
