package com.example.agentdemo.app;

import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.knowledge.KnowledgeSearchService;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppKnowledgeContextServiceTest {

    @Test
    void retrievesDeduplicatesAndSortsKnowledgeContext() {
        KnowledgeSearchService knowledgeSearchService = mock(KnowledgeSearchService.class);
        TraceService traceService = mock(TraceService.class);
        AppKnowledgeContextService service = new AppKnowledgeContextService(knowledgeSearchService, traceService);
        AppConfig config = new AppConfig("You help", null, true, null, List.of("kb-1", "kb-2"));
        when(traceService.startTraceStep("run-1", "app_knowledge_retrieve",
                Map.of("knowledgeBaseIds", config.knowledgeBaseIdsOrEmpty(), "query", "refund policy")))
                .thenReturn(new TraceStep("step-1", "run-1", "app_knowledge_retrieve"));
        when(knowledgeSearchService.search("kb-1", "refund policy", null)).thenReturn(
                new KnowledgeSearchResponse("kb-1", "refund policy", List.of(
                        new Citation(10L, "Low", 2, "old snippet", 0.1),
                        new Citation(11L, "Other", 0, "other snippet", 0.6))));
        when(knowledgeSearchService.search("kb-2", "refund policy", null)).thenReturn(
                new KnowledgeSearchResponse("kb-2", "refund policy", List.of(
                        new Citation(10L, "High", 2, "better snippet", 0.9),
                        new Citation(12L, "Third", 0, "third snippet", 0.4))));

        List<Citation> citations = service.retrieve(config, "refund policy", "run-1");

        assertThat(citations).extracting(Citation::documentId).containsExactly(10L, 11L, 12L);
        assertThat(citations.getFirst().title()).isEqualTo("High");
        verify(traceService).completeStep("step-1", Map.of("citationCount", 3));
    }

    @Test
    void buildsPromptContextFromCitations() {
        AppKnowledgeContextService service = new AppKnowledgeContextService(mock(KnowledgeSearchService.class),
                mock(TraceService.class));

        String augmented = service.augmentMessage("What is the policy?",
                List.of(new Citation(1L, "Returns", 0, "Refunds within 30 days", 0.8)));

        assertThat(augmented)
                .contains("Retrieved knowledge base context", "Returns", "Refunds within 30 days",
                        "Question:", "What is the policy?");
    }

}
