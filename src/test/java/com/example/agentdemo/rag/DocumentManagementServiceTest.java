package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentManagementServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentChunkRepository documentChunkRepository = mock(DocumentChunkRepository.class);
    private final VectorStoreGateway vectorStoreGateway = mock(VectorStoreGateway.class);
    private final DocumentManagementService service = new DocumentManagementService(documentRepository,
            documentChunkRepository, vectorStoreGateway, TestAlibabaPolicies.legacyFallbackAllowed());

    @Test
    void deleteDocumentRemovesVectorsBeforeDbWhenConfigured() {
        DocumentEntity document = new DocumentEntity("Doc", "content");
        DocumentChunkEntity chunk = new DocumentChunkEntity(1L, 0, "vec-1", "chunk");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(1L)).thenReturn(List.of(chunk));
        when(vectorStoreGateway.isConfigured()).thenReturn(true);

        service.deleteDocument(1L);

        var order = inOrder(vectorStoreGateway, documentChunkRepository, documentRepository);
        order.verify(vectorStoreGateway).delete(List.of("vec-1"));
        order.verify(documentChunkRepository).deleteByDocumentId(1L);
        order.verify(documentRepository).delete(document);
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
