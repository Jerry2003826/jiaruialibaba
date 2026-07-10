package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.common.PublicIdGenerator;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.knowledge.KnowledgeBaseEntity;
import com.example.agentdemo.knowledge.KnowledgeBasePurpose;
import com.example.agentdemo.knowledge.KnowledgeBaseRepository;
import com.example.agentdemo.knowledge.KnowledgeIngestionService;
import com.example.agentdemo.knowledge.KnowledgeSearchService;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    void retrieveLazilyCreatesManagedKbSynchronizesPacksAndCapsTopK() {
        WorkflowRulePack corePack = pack("core", "2026.07.10", "Core knowledge",
                rule("core-rule", "Registered node types only", "Use approved nodes only."));
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.empty());
        when(publicIdGenerator.next("kb")).thenReturn("kb-builder");
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBaseEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(eq("owner-a"), eq("kb-builder"), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(knowledgeSearchService.searchManaged("kb-builder", "order status", 6))
                .thenReturn(new com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse("kb-builder",
                        "order status", List.of(new Citation(11L, "core", 0, "snippet", 1.0))));

        List<Citation> citations = runAs("owner-a", () -> service.retrieve("customer-service-ecommerce",
                "order status", 10));

        assertThat(citations).extracting(Citation::documentId).containsExactly(11L);
        verify(knowledgeBaseRepository).saveAndFlush(any(KnowledgeBaseEntity.class));
        verify(knowledgeIngestionService).addManagedTextDocument(eq("kb-builder"), eq("Workflow Builder Guidance: core@2026.07.10"),
                org.mockito.ArgumentMatchers.contains("Registered node types only"));
        verify(knowledgeSearchService).searchManaged("kb-builder", "order status", 6);
    }

    @Test
    void retrieveDoesNotDuplicateOrReindexWhenPackContentHashIsUnchanged() {
        WorkflowRulePack corePack = pack("core", "2026.07.10", "Core knowledge",
                rule("core-rule", "Registered node types only", "Use approved nodes only."));
        KnowledgeBaseEntity managedKb = managedKb("kb-builder", "owner-a");
        DocumentEntity existing = managedDocument("kb-builder", "Workflow Builder Guidance: core@2026.07.10",
                service.contentHashForTest(service.guidanceContentForTest(corePack)));
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb));
        when(documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(eq("owner-a"), eq("kb-builder"), any(), any()))
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
    void retrieveReturnsEmptyListWhenSynchronizationFails(CapturedOutput output) {
        WorkflowRulePack corePack = pack("core", "2026.07.10", "Core knowledge",
                rule("core-rule", "Registered node types only", "Use approved nodes only."));
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.empty());
        when(publicIdGenerator.next("kb")).thenReturn("kb-builder");
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBaseEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(eq("owner-a"), eq("kb-builder"), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        List<Citation> citations = runAs("owner-a", () -> service.retrieve("customer-service-ecommerce",
                "refund", 3));

        assertThat(citations).isEmpty();
        assertThat(output).contains("Workflow builder knowledge retrieval failed");
    }

    @Test
    void retrieveIsOwnerScoped() {
        WorkflowRulePack corePack = pack("core", "2026.07.10", "Core knowledge",
                rule("core-rule", "Registered node types only", "Use approved nodes only."));
        when(workflowRuleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-a", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb("kb-owner-a", "owner-a")));
        when(knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                "owner-b", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb("kb-owner-b", "owner-b")));
        when(documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(eq("owner-a"), eq("kb-owner-a"), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(eq("owner-b"), eq("kb-owner-b"), any(), any()))
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

    private WorkflowRulePack pack(String id, String version, String knowledgeEntry, WorkflowGovernanceRule rule) {
        return new WorkflowRulePack(id, version, List.of(id), List.of(rule), List.of(knowledgeEntry), List.of());
    }

    private WorkflowGovernanceRule rule(String id, String description, String repairHint) {
        return new WorkflowGovernanceRule(id, "warning", id, description, repairHint, "detector");
    }

    private KnowledgeBaseEntity managedKb(String kbId, String ownerId) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(kbId, "Builder", "Hidden", null,
                KnowledgeBasePurpose.WORKFLOW_BUILDER, true);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "ownerId", ownerId);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-07-10T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-07-10T00:00:00Z"));
        return entity;
    }

    private DocumentEntity managedDocument(String kbId, String title, String hash) {
        DocumentEntity entity = new DocumentEntity(title, "content");
        entity.assignKnowledge(kbId, "TEXT", null, "text/plain", 7L, hash);
        entity.markReady();
        return entity;
    }

    private <T> T runAs(String ownerId, java.util.concurrent.Callable<T> action) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(ownerId, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return action.call();
        }
        catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
