package com.example.agentdemo.knowledge;

import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class KnowledgeSearchService {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsHan}\\p{Alnum}]+");
    private static final int SCAN_PAGE_SIZE = 200;
    private static final List<DocumentIndexStatus> NON_RETRIEVABLE = List.of(
            DocumentIndexStatus.DELETING, DocumentIndexStatus.DELETED);

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final DocumentRepository documentRepository;
    private final KnowledgeProperties knowledgeProperties;
    private final RagProperties ragProperties;
    private final Reranker reranker;
    private final KnowledgeResponseMapper knowledgeResponseMapper;

    public KnowledgeSearchService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentRepository documentRepository, KnowledgeProperties knowledgeProperties,
            RagProperties ragProperties, Reranker reranker, KnowledgeResponseMapper knowledgeResponseMapper) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.documentRepository = documentRepository;
        this.knowledgeProperties = knowledgeProperties;
        this.ragProperties = ragProperties;
        this.reranker = reranker;
        this.knowledgeResponseMapper = knowledgeResponseMapper;
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse search(String kbId, String query, Integer requestedTopK) {
        KnowledgeBaseEntity kb = knowledgeBaseAccessService.findKb(kbId);
        int topK = requestedTopK != null && requestedTopK > 0 ? Math.min(requestedTopK, 50)
                : knowledgeResponseMapper.retrievalConfig(kb).topKOr(ragProperties.getRag().getTopK());
        List<Citation> candidates = keywordSearch(kbId, query, topK);
        List<Citation> ranked = reranker.rerank(query, candidates, topK);
        return new KnowledgeSearchResponse(kbId, query, ranked);
    }

    private List<Citation> keywordSearch(String kbId, String query, int topK) {
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String ownerId = SecurityIdentity.currentOwnerId();
        List<Citation> scored = new ArrayList<>();
        int pageNumber = 0;
        int scanned = 0;
        int maxScannedDocuments = Math.max(1, knowledgeProperties.getMaxScannedDocuments());
        Page<DocumentEntity> page;
        do {
            page = documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(ownerId, kbId, NON_RETRIEVABLE,
                    PageRequest.of(pageNumber++, SCAN_PAGE_SIZE, Sort.by("id").ascending()));
            for (DocumentEntity document : page.getContent()) {
                if (scanned++ >= maxScannedDocuments) {
                    break;
                }
                double score = score(document, terms);
                if (score > 0) {
                    scored.add(new Citation(document.getId(), safeTitle(document), 0,
                            snippet(document.getContent(), terms), score));
                }
            }
        }
        while (page.hasNext() && scanned < maxScannedDocuments);
        scored.sort(Comparator.comparingDouble(Citation::score).reversed().thenComparing(Citation::documentId));
        return scored.size() <= topK ? scored : scored.subList(0, topK);
    }

    private double score(DocumentEntity document, Set<String> terms) {
        String title = safeTitle(document).toLowerCase(Locale.ROOT);
        String haystack = (title + "\n" + document.getContent()).toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score += title.contains(term) ? 2.0 : 1.0;
            }
        }
        return score;
    }

    private Set<String> tokenize(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : TOKEN_SPLITTER.split(query == null ? "" : query.toLowerCase(Locale.ROOT))) {
            if (StringUtils.hasText(raw) && raw.length() >= 2) {
                terms.add(raw);
                if (terms.size() >= 16) {
                    break;
                }
            }
        }
        return terms;
    }

    private String snippet(String content, Set<String> terms) {
        String lower = content.toLowerCase(Locale.ROOT);
        int start = 0;
        for (String term : terms) {
            int index = lower.indexOf(term);
            if (index >= 0) {
                start = Math.max(0, index - 80);
                break;
            }
        }
        int end = Math.min(content.length(), start + 280);
        return content.substring(start, end);
    }

    private String safeTitle(DocumentEntity document) {
        return document.getTitle() == null ? "" : document.getTitle();
    }

}
