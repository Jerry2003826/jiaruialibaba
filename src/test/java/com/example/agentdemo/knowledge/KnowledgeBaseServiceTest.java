package com.example.agentdemo.knowledge;

import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.KbDocumentCountProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
    private final KnowledgeResponseMapper knowledgeResponseMapper = mock(KnowledgeResponseMapper.class);
    private final KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(knowledgeBaseRepository,
            documentRepository, knowledgeBaseAccessService, knowledgeResponseMapper, new ObjectMapper());

    @Test
    void legacyObjectMapperConstructorRemainsPublic() throws Exception {
        assertThat(Modifier.isPublic(KnowledgeBaseService.class
                .getDeclaredConstructor(KnowledgeBaseRepository.class, DocumentRepository.class,
                        KnowledgeBaseAccessService.class, KnowledgeResponseMapper.class, ObjectMapper.class)
                .getModifiers())).isTrue();
    }

    @Test
    void listKnowledgeBasesUsesGroupedDocumentCountsInsteadOfPerKbCountQueries() {
        KnowledgeBaseEntity first = knowledgeBase("kb-1", "Docs", Instant.parse("2026-07-01T10:15:30Z"));
        KnowledgeBaseEntity second = knowledgeBase("kb-2", "Policies", Instant.parse("2026-07-01T10:15:31Z"));
        when(knowledgeBaseRepository.findByOwnerIdOrderByCreatedAtDesc("workbench-dev")).thenReturn(List.of(first, second));
        when(documentRepository.countGroupedByOwnerIdAndKbIdIn(eq("workbench-dev"), eq(List.of("kb-1", "kb-2"))))
                .thenReturn(List.of(projection("kb-1", 3L), projection("kb-2", 1L)));
        when(knowledgeResponseMapper.toKnowledgeBaseResponse(first, 3L))
                .thenReturn(new KnowledgeBaseResponse("kb-1", "Docs", null, RetrievalConfig.defaults(), 3L,
                        first.getCreatedAt(), first.getUpdatedAt()));
        when(knowledgeResponseMapper.toKnowledgeBaseResponse(second, 1L))
                .thenReturn(new KnowledgeBaseResponse("kb-2", "Policies", null, RetrievalConfig.defaults(), 1L,
                        second.getCreatedAt(), second.getUpdatedAt()));

        List<KnowledgeBaseResponse> result = knowledgeBaseService.listKnowledgeBases();

        assertThat(result).extracting(KnowledgeBaseResponse::documentCount).containsExactly(3L, 1L);
        verify(documentRepository).countGroupedByOwnerIdAndKbIdIn("workbench-dev", List.of("kb-1", "kb-2"));
        verify(documentRepository, never()).countByOwnerIdAndKbId(any(), any());
    }

    private KnowledgeBaseEntity knowledgeBase(String kbId, String name, Instant createdAt) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(kbId, name, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", createdAt);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", createdAt);
        return entity;
    }

    private KbDocumentCountProjection projection(String kbId, long count) {
        return new KbDocumentCountProjection() {
            @Override
            public String getKbId() {
                return kbId;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

}
