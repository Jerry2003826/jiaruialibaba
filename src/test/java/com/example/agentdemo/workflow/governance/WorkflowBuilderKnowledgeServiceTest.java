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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class WorkflowBuilderKnowledgeServiceTest {

    private final WorkflowRuleCatalog workflowRuleCatalog = mock(WorkflowRuleCatalog.class);
    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final KnowledgeIngestionService knowledgeIngestionService = mock(KnowledgeIngestionService.class);
    private final DocumentManagementService documentManagementService = mock(DocumentManagementService.class);
    private final KnowledgeSearchService knowledgeSearchService = mock(KnowledgeSearchService.class);
    private final PublicIdGenerator publicIdGenerator = mock(PublicIdGenerator.class);
    private final WorkflowBuilderKnowledgeService service = new WorkflowBuilderKnowledgeService(
            workflowRuleCatalog, knowledgeBaseRepository, documentRepository, knowledgeIngestionService,
            documentManagementService, knowledgeSearchService, publicIdGenerator);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void retrieveCreatesOneStableManagedDocumentPerRuleWithCompleteContentAndCapsTopK() {
        WorkflowGovernanceRule firstRule = rule("core-registered-node-types",
                "Generated or repaired graphs must stay within the existing workflow node catalog.",
                "Invent a graph node type that does not exist.",
                "Use the existing condition node for branching instead.",
                "Replace unsupported nodes with approved platform nodes only.");
        WorkflowGovernanceRule secondRule = rule("core-registered-tools",
                "Tool nodes must reference approved platform tools.",
                "Call a business tool that is not registered.",
                "Reuse queryOrderAPI when the platform exposes it.",
                "Swap placeholder tool names for approved tools.");
        WorkflowRulePack corePack = pack("core", "2026.07.10",
                List.of("The core pack always applies.", "Detectors remain data-only labels."),
                firstRule, secondRule);

        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.empty());
        when(publicIdGenerator.next("kb")).thenReturn("kb-builder");
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBaseEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-a"), eq("kb-builder"), eq("BUILDER"), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(knowledgeSearchService.searchManaged("kb-builder", "order status", 6))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-builder",
                        "order status", List.of(new Citation(11L, "core", 0, "snippet", 1.0))));

        List<Citation> citations = runAs("owner-a", () -> service.retrieve("customer-service-ecommerce",
                "order status", 10));

        assertThat(citations).extracting(Citation::documentId).containsExactly(11L);
        verify(knowledgeBaseRepository).saveAndFlush(any(KnowledgeBaseEntity.class));

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeIngestionService, times(2)).addManagedTextDocument(eq("kb-builder"), titleCaptor.capture(),
                contentCaptor.capture());
        assertThat(titleCaptor.getAllValues())
                .containsExactlyInAnyOrder(
                        "Workflow Builder Guidance: core/core-registered-node-types",
                        "Workflow Builder Guidance: core/core-registered-tools");
        assertThat(contentCaptor.getAllValues()).allSatisfy(content -> {
            assertThat(content).contains("Rationale:");
            assertThat(content).contains("Anti-patterns:");
            assertThat(content).contains("Examples:");
            assertThat(content).contains("Repair hint:");
            assertThat(content).contains("Severity:");
            assertThat(content).contains("Detector:");
            assertThat(content).contains("Pack guidance:");
            assertThat(content).contains("The core pack always applies.");
        });
        assertThat(contentCaptor.getAllValues().get(0))
                .contains("Generated or repaired graphs must stay within the existing workflow node catalog.")
                .contains("Invent a graph node type that does not exist.")
                .contains("Use the existing condition node for branching instead.");
        verify(knowledgeSearchService).searchManaged("kb-builder", "order status", 6);
    }

    @Test
    void retrieveDoesNotIngestOrReindexWhenRuleContentHashIsUnchanged() {
        WorkflowGovernanceRule rule = rule("core-registered-node-types",
                "Generated or repaired graphs must stay within the existing workflow node catalog.",
                "Invent a graph node type that does not exist.",
                "Use the existing condition node for branching instead.",
                "Replace unsupported nodes with approved platform nodes only.");
        WorkflowRulePack corePack = pack("core", "2026.07.10",
                List.of("The core pack always applies."), rule);
        KnowledgeBaseEntity managedKb = managedKb("kb-builder", "owner-a");
        DocumentEntity existing = managedDocument("kb-builder",
                "Workflow Builder Guidance: core/core-registered-node-types",
                "BUILDER",
                service.contentHashForTest(service.guidanceContentForTest(corePack, rule)));
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-a"), eq("kb-builder"), eq("BUILDER"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(existing)));
        when(knowledgeSearchService.searchManaged("kb-builder", "refund", 3))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-builder",
                        "refund", List.of()));

        runAs("owner-a", () -> service.retrieve("customer-service-ecommerce", "refund", 3));
        runAs("owner-a", () -> service.retrieve("customer-service-ecommerce", "refund", 3));

        verify(knowledgeIngestionService, never()).addManagedTextDocument(any(), any(), any());
        verify(documentManagementService, never()).deleteDocument(any());
        verify(knowledgeSearchService, times(2)).searchManaged("kb-builder", "refund", 3);
    }

    @Test
    void retrieveDoesNotReindexWhenOnlyPackVersionChanges() {
        WorkflowGovernanceRule rule = rule("core-registered-node-types",
                "Generated or repaired graphs must stay within the existing workflow node catalog.",
                "Invent a graph node type that does not exist.",
                "Use the existing condition node for branching instead.",
                "Replace unsupported nodes with approved platform nodes only.");
        WorkflowRulePack previousPack = pack("core", "2026.07.10",
                List.of("The core pack always applies."), rule);
        WorkflowRulePack upgradedPack = pack("core", "2026.07.11",
                List.of("The core pack always applies."), rule);
        KnowledgeBaseEntity managedKb = managedKb("kb-builder", "owner-a");
        DocumentEntity existing = managedDocument("kb-builder",
                "Workflow Builder Guidance: core/core-registered-node-types",
                "BUILDER",
                service.contentHashForTest(service.guidanceContentForTest(previousPack, rule)));
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(upgradedPack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-a"), eq("kb-builder"), eq("BUILDER"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(existing)));
        when(knowledgeSearchService.searchManaged("kb-builder", "refund", 3))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-builder",
                        "refund", List.of()));

        runAs("owner-a", () -> service.retrieve("customer-service-ecommerce", "refund", 3));

        verify(knowledgeIngestionService, never()).addManagedTextDocument(any(), any(), any());
        verify(documentManagementService, never()).deleteDocument(any());
    }

    @Test
    void concurrentRetrievalLeavesExactlyOneActiveDocumentPerRule() throws Exception {
        WorkflowGovernanceRule firstRule = rule("core-registered-node-types",
                "Generated or repaired graphs must stay within the existing workflow node catalog.",
                "Invent a graph node type that does not exist.",
                "Use the existing condition node for branching instead.",
                "Replace unsupported nodes with approved platform nodes only.");
        WorkflowGovernanceRule secondRule = rule("core-registered-tools",
                "Tool nodes must reference approved platform tools.",
                "Call a business tool that is not registered.",
                "Reuse queryOrderAPI when the platform exposes it.",
                "Swap placeholder tool names for approved tools.");
        WorkflowRulePack corePack = pack("core", "2026.07.10",
                List.of("The core pack always applies."), firstRule, secondRule);
        KnowledgeBaseEntity managedKb = managedKb("kb-builder", "owner-a");
        CopyOnWriteArrayList<DocumentEntity> documents = new CopyOnWriteArrayList<>();
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-a"), eq("kb-builder"), eq("BUILDER"), any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(new ArrayList<>(documents)));
        when(knowledgeIngestionService.addManagedTextDocument(eq("kb-builder"), any(), any()))
                .thenAnswer(invocation -> {
                    String title = invocation.getArgument(1, String.class);
                    String content = invocation.getArgument(2, String.class);
                    DocumentEntity created = managedDocument("kb-builder", title, "BUILDER",
                            service.contentHashForTest(content));
                    documents.add(created);
                    return null;
                });
        when(knowledgeSearchService.searchManaged("kb-builder", "refund", 2))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-builder",
                        "refund", List.of()));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<Citation>> first = executor.submit(callableRetrieve(start));
            Future<List<Citation>> second = executor.submit(callableRetrieve(start));
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(DocumentEntity::getTitle)
                .containsExactlyInAnyOrder(
                        "Workflow Builder Guidance: core/core-registered-node-types",
                        "Workflow Builder Guidance: core/core-registered-tools");
    }

    @Test
    void retrieveReturnsEmptyListWhenSynchronizationFails(CapturedOutput output) {
        WorkflowGovernanceRule rule = rule("core-registered-node-types",
                "Generated or repaired graphs must stay within the existing workflow node catalog.",
                "Invent a graph node type that does not exist.",
                "Use the existing condition node for branching instead.",
                "Replace unsupported nodes with approved platform nodes only.");
        WorkflowRulePack corePack = pack("core", "2026.07.10",
                List.of("The core pack always applies."), rule);
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.empty());
        when(publicIdGenerator.next("kb")).thenReturn("kb-builder");
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBaseEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-a"), eq("kb-builder"), eq("BUILDER"), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        List<Citation> citations = runAs("owner-a", () -> service.retrieve("customer-service-ecommerce",
                "refund", 3));

        assertThat(citations).isEmpty();
        assertThat(output).contains("Workflow builder knowledge retrieval failed");
    }

    @Test
    void retrieveIsOwnerScoped() {
        WorkflowGovernanceRule rule = rule("core-registered-node-types",
                "Generated or repaired graphs must stay within the existing workflow node catalog.",
                "Invent a graph node type that does not exist.",
                "Use the existing condition node for branching instead.",
                "Replace unsupported nodes with approved platform nodes only.");
        WorkflowRulePack corePack = pack("core", "2026.07.10",
                List.of("The core pack always applies."), rule);
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb("kb-owner-a", "owner-a")));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-b", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb("kb-owner-b", "owner-b")));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-a"), eq("kb-owner-a"), eq("BUILDER"), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(documentRepository.findByOwnerIdAndKbIdAndSourceTypeAndIndexStatusNotIn(
                eq("owner-b"), eq("kb-owner-b"), eq("BUILDER"), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(knowledgeSearchService.searchManaged("kb-owner-a", "refund", 2))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-owner-a",
                        "refund", List.of()));
        when(knowledgeSearchService.searchManaged("kb-owner-b", "refund", 2))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-owner-b",
                        "refund", List.of()));

        runAs("owner-a", () -> service.retrieve("customer-service-ecommerce", "refund", 2));
        runAs("owner-b", () -> service.retrieve("customer-service-ecommerce", "refund", 2));

        verify(knowledgeSearchService).searchManaged("kb-owner-a", "refund", 2);
        verify(knowledgeSearchService).searchManaged("kb-owner-b", "refund", 2);
    }

    private Callable<List<Citation>> callableRetrieve(CountDownLatch start) {
        return () -> {
            start.await();
            return runAs("owner-a", () -> service.retrieve("customer-service-ecommerce", "refund", 2));
        };
    }

    private WorkflowRulePack pack(String id, String version, List<String> knowledgeEntries, WorkflowGovernanceRule... rules) {
        return new WorkflowRulePack(id, version, List.of(id), List.of(rules), knowledgeEntries, List.of());
    }

    private WorkflowGovernanceRule rule(String id, String description, String antiPattern, String example,
            String repairHint) {
        return new WorkflowGovernanceRule(
                id,
                "warning",
                id,
                description,
                List.of(antiPattern),
                List.of(example),
                repairHint,
                "detector-" + id);
    }

    private KnowledgeBaseEntity managedKb(String kbId, String ownerId) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(kbId, "Builder", "Hidden", null,
                KnowledgeBasePurpose.WORKFLOW_BUILDER, true);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "ownerId", ownerId);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-07-10T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-07-10T00:00:00Z"));
        return entity;
    }

    private DocumentEntity managedDocument(String kbId, String title, String sourceType, String hash) {
        DocumentEntity entity = new DocumentEntity(title, "content");
        entity.assignKnowledge(kbId, sourceType, null, "text/plain", 7L, hash);
        entity.markReady();
        return entity;
    }

    private <T> T runAs(String ownerId, Callable<T> action) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(ownerId, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return action.call();
        }
        catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }
}
