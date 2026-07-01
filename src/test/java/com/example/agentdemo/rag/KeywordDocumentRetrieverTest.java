package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeywordDocumentRetrieverTest {

    @Test
    void retrievesDocumentsByScanningRepositoryPages() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentEntity document = document(7L, "Alpha guide", "alpha notes");
        when(documentRepository.findByOwnerIdAndIndexStatusNotIn(org.mockito.ArgumentMatchers.eq("workbench-dev"),
                        anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(document)));
        when(documentRepository.findByIndexStatusNotIn(anyCollection()))
                .thenThrow(new AssertionError("unpaged keyword retrieval must not be used"));
        KeywordDocumentRetriever retriever = new KeywordDocumentRetriever(documentRepository);

        List<RetrievedContext> results = retriever.retrieve("alpha", 1);

        assertThat(results)
                .extracting(RetrievedContext::documentId)
                .containsExactly(7L);
        verify(documentRepository, never()).findByIndexStatusNotIn(anyCollection());
    }

    private static DocumentEntity document(Long id, String title, String content) {
        DocumentEntity document = new DocumentEntity(title, content);
        document.markReady();
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }
}
