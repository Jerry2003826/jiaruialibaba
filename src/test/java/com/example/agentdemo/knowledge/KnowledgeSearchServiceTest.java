package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final KnowledgeBaseAccessService knowledgeBaseAccessService = new KnowledgeBaseAccessService(
            knowledgeBaseRepository, documentRepository);
    private final KnowledgeResponseMapper knowledgeResponseMapper = new KnowledgeResponseMapper(new ObjectMapper());
    private final KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
    private final RagProperties ragProperties = new RagProperties();
    private final Reranker reranker = mock(Reranker.class);
    private final KnowledgeSearchService knowledgeSearchService = new KnowledgeSearchService(
            knowledgeBaseAccessService, documentRepository, knowledgeProperties, ragProperties, reranker,
            knowledgeResponseMapper);
    private final KnowledgeDocumentService knowledgeDocumentService = new KnowledgeDocumentService(
            knowledgeBaseAccessService, documentRepository, mock(DocumentIndexingService.class),
            mock(DocumentManagementService.class), knowledgeResponseMapper);
    private final KnowledgeChunkPreviewService knowledgeChunkPreviewService = new KnowledgeChunkPreviewService(
            knowledgeBaseAccessService, ragProperties, knowledgeResponseMapper);

    @Test
    void publicSearchRejectsSystemManagedKnowledgeBaseAsNotFound() {
        when(knowledgeBaseRepository.findByKbIdAndOwnerIdAndSystemManagedFalse("kb-builder", "workbench-dev"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeSearchService.search("kb-builder", "refund", 3))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("KNOWLEDGE_BASE_NOT_FOUND"));
        verify(documentRepository, never()).findByOwnerIdAndKbIdAndIndexStatusNotIn(any(), any(), any(), any());
    }

    @Test
    void managedSearchUsesManagedLookup() {
        KnowledgeBaseEntity managedKb = managedKnowledgeBase("kb-builder");
        DocumentEntity document = document("Builder hint", "Ask for the order number before claiming shipping status.");
        when(knowledgeBaseRepository.findByKbIdAndOwnerIdAndPurposeAndSystemManagedTrue(
                "kb-builder", "workbench-dev", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(Optional.of(managedKb));
        when(documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(eq("workbench-dev"), eq("kb-builder"),
                any(), any()))
                .thenReturn(new PageImpl<>(List.of(document)));
        when(reranker.rerank(eq("order status"), any(), eq(4)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        KnowledgeSearchResponse response = knowledgeSearchService.searchManaged("kb-builder", "order status", 4);

        assertThat(response.kbId()).isEqualTo("kb-builder");
        assertThat(response.citations()).extracting(Citation::title).containsExactly("Builder hint");
    }

    @Test
    void documentAndChunkOperationsRejectSystemManagedKnowledgeBasesAsNotFound() {
        when(knowledgeBaseRepository.findByKbIdAndOwnerIdAndSystemManagedFalse("kb-builder", "workbench-dev"))
                .thenReturn(Optional.empty());

        assertManagedNotFound(() -> knowledgeDocumentService.listDocuments("kb-builder", 0, 20));
        assertManagedNotFound(() -> knowledgeDocumentService.getDocument("kb-builder", 7L));
        assertManagedNotFound(() -> knowledgeDocumentService.deleteDocument("kb-builder", 7L));
        assertManagedNotFound(() -> knowledgeDocumentService.reindex("kb-builder", 7L));
        assertManagedNotFound(() -> knowledgeChunkPreviewService.previewChunks("kb-builder", 7L, 0, 20));

        verify(documentRepository, never()).findByIdAndKbIdAndOwnerId(any(), any(), any());
    }

    private void assertManagedNotFound(ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("KNOWLEDGE_BASE_NOT_FOUND"));
    }

    private KnowledgeBaseEntity managedKnowledgeBase(String kbId) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(kbId, "Builder", "Hidden", null,
                KnowledgeBasePurpose.WORKFLOW_BUILDER, true);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-07-10T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-07-10T00:00:00Z"));
        return entity;
    }

    private DocumentEntity document(String title, String content) {
        DocumentEntity entity = new DocumentEntity(title, content);
        entity.assignKnowledge("kb-builder", "TEXT", null, "text/plain", (long) content.length(), "hash");
        entity.markReady();
        return entity;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
