package com.example.agentdemo.knowledge;

import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {

    @Test
    void managedBuilderGuidanceRemainsKeywordOnlyAndNeverEntersTheSharedVectorCollection() {
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIndexingService indexingService = mock(DocumentIndexingService.class);
        KnowledgeBaseEntity managedKb = new KnowledgeBaseEntity("kb-builder", "Builder", "Hidden", null,
                KnowledgeBasePurpose.WORKFLOW_BUILDER, true);
        when(accessService.findManagedKb("kb-builder", KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenReturn(managedKb);
        when(documentRepository.saveAndFlush(any(DocumentEntity.class))).thenAnswer(invocation -> {
            DocumentEntity document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", 7L);
            return document;
        });
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                accessService,
                documentRepository,
                indexingService,
                mock(DocumentTextExtractor.class),
                new KnowledgeProperties(),
                new KnowledgeResponseMapper(new ObjectMapper()));

        var response = service.addManagedTextDocument("kb-builder", "core/rule", "internal guidance");

        assertThat(response.sourceType()).isEqualTo(DocumentEntity.WORKFLOW_BUILDER_SOURCE_TYPE);
        assertThat(response.indexStatus()).isEqualTo(DocumentIndexStatus.READY);
        verify(indexingService, never()).index(any());
    }
}
