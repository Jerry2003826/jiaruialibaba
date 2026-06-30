package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.rag.dto.DocumentResponse;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentManagementServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentChunkRepository documentChunkRepository = mock(DocumentChunkRepository.class);
    private final VectorStoreGateway vectorStoreGateway = mock(VectorStoreGateway.class);
    private final DocumentIndexingService documentIndexingService = mock(DocumentIndexingService.class);
    private final DocumentManagementService service = new DocumentManagementService(documentRepository,
            documentChunkRepository, vectorStoreGateway, TestAlibabaPolicies.legacyFallbackAllowed(),
            documentIndexingService);

    @Test
    void updateDocumentReplacesContentClearsOldChunksAndReindexes() {
        DocumentEntity document = new DocumentEntity("Old", "old content");
        ReflectionTestUtils.setField(document, "id", 1L);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);

        DocumentResponse response = service.updateDocument(1L, new DocumentRequest("New", "new content"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("New");
        assertThat(response.contentLength()).isEqualTo("new content".length());
        verify(documentChunkRepository).deleteByDocumentId(1L);
        verify(documentRepository).save(document);
        verify(documentIndexingService).index(document);
    }

    @Test
    void deleteDocumentDoesNotRemoveVectorsBeforeDbWhenConfigured() {
        DocumentEntity document = new DocumentEntity("Doc", "content");
        DocumentChunkEntity chunk = new DocumentChunkEntity(1L, 0, "vec-1", "chunk");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(1L)).thenReturn(List.of(chunk));
        when(vectorStoreGateway.isConfigured()).thenReturn(true);

        service.deleteDocument(1L);

        verify(vectorStoreGateway, never()).delete(anyList());
        verify(documentChunkRepository, never()).deleteByDocumentId(1L);
        verify(documentRepository, never()).delete(document);
    }

    @Test
    void deleteDocumentSkipsVectorDeleteWhenNotConfiguredInLegacyMode() {
        DocumentEntity document = new DocumentEntity("Doc", "content");
        DocumentChunkEntity chunk = new DocumentChunkEntity(1L, 0, "vec-1", "chunk");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(1L)).thenReturn(List.of(chunk));
        when(vectorStoreGateway.isConfigured()).thenReturn(false);

        service.deleteDocument(1L);

        verify(documentChunkRepository).deleteByDocumentId(1L);
        verify(documentRepository).delete(document);
        verify(vectorStoreGateway, never()).delete(anyList());
    }

    @Test
    void deleteDocumentThrowsWhenVectorStoreRequiredButNotConfigured() {
        DocumentManagementService strictService = new DocumentManagementService(documentRepository,
                documentChunkRepository, vectorStoreGateway, TestAlibabaPolicies.strictMode());
        DocumentEntity document = new DocumentEntity("Doc", "content");
        DocumentChunkEntity chunk = new DocumentChunkEntity(1L, 0, "vec-1", "chunk");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(1L)).thenReturn(List.of(chunk));
        when(vectorStoreGateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> strictService.deleteDocument(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_VECTOR_STORE_NOT_CONFIGURED");

        verify(documentChunkRepository, never()).deleteByDocumentId(1L);
        verify(documentRepository, never()).delete(document);
    }

    @Test
    void getDocumentThrowsWhenMissing() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDocument(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("DOCUMENT_NOT_FOUND");
    }

}
