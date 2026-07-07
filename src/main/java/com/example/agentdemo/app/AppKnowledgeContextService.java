package com.example.agentdemo.app;

import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.knowledge.KnowledgeSearchService;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AppKnowledgeContextService {

    private static final Logger log = LoggerFactory.getLogger(AppKnowledgeContextService.class);
    private static final int MAX_CONTEXT_CITATIONS = 8;

    private final KnowledgeSearchService knowledgeSearchService;
    private final TraceService traceService;

    public AppKnowledgeContextService(KnowledgeSearchService knowledgeSearchService, TraceService traceService) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.traceService = traceService;
    }

    public List<Citation> retrieve(AppConfig config, String message, String runId) {
        List<String> kbIds = config == null ? List.of() : config.knowledgeBaseIdsOrEmpty();
        if (kbIds.isEmpty()) {
            return List.of();
        }
        TraceStep step = traceService.startTraceStep(runId, "app_knowledge_retrieve",
                Map.of("knowledgeBaseIds", kbIds, "query", message));
        Map<CitationKey, Citation> bestByChunk = new LinkedHashMap<>();
        for (String kbId : kbIds) {
            try {
                for (Citation citation : knowledgeSearchService.search(kbId, message, null).citations()) {
                    bestByChunk.merge(new CitationKey(citation.documentId(), citation.chunkIndex()), citation,
                            (left, right) -> left.score() >= right.score() ? left : right);
                }
            }
            catch (RuntimeException ex) {
                log.warn("App knowledge retrieval failed for kb {}", kbId, ex);
            }
        }
        List<Citation> top = bestByChunk.values().stream()
                .sorted(Comparator.comparingDouble(Citation::score).reversed()
                        .thenComparing(Citation::documentId)
                        .thenComparing(Citation::chunkIndex))
                .limit(MAX_CONTEXT_CITATIONS)
                .toList();
        traceService.completeStep(step.stepId(), Map.of("citationCount", top.size()));
        return List.copyOf(top);
    }

    public String augmentMessage(String message, List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return message;
        }
        StringBuilder context = new StringBuilder("BEGIN_UNTRUSTED_CONTEXT\n");
        for (Citation citation : citations) {
            context.append("- ").append(citation.title()).append(": ").append(citation.snippet()).append("\n");
        }
        return context.append("END_UNTRUSTED_CONTEXT\n\nQuestion:\n").append(message).toString();
    }

    private record CitationKey(Long documentId, int chunkIndex) {
    }

}
