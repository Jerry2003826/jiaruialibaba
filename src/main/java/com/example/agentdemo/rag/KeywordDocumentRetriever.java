package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class KeywordDocumentRetriever implements DocumentRetriever {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsHan}\\p{Alnum}]+");
    private static final int DOCUMENT_SCAN_PAGE_SIZE = 200;
    private static final int MAX_QUERY_CHARS = 512;
    private static final int MAX_TERMS = 16;
    private static final int MAX_SCANNED_DOCUMENTS = 5_000;
    private static final Comparator<RetrievedContext> BEST_CONTEXT_FIRST =
            Comparator.comparingDouble(RetrievedContext::score).reversed()
                    .thenComparing(RetrievedContext::documentId);
    private static final Comparator<RetrievedContext> WORST_CONTEXT_FIRST =
            Comparator.comparingDouble(RetrievedContext::score)
                    .thenComparing(RetrievedContext::documentId, Comparator.reverseOrder());

    private final DocumentRepository documentRepository;

    public KeywordDocumentRetriever(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public String name() {
        return "KeywordDocumentRetriever";
    }

    // Keyword search works directly on persisted document content, so it must include every
    // document that still has content available — PENDING (vector indexing not finished or no
    // vector store at all), READY and FAILED — and only exclude documents being removed. This is
    // what makes keyword retrieval usable as a local-only retriever and as a vector fallback.
    private static final List<DocumentIndexStatus> NON_RETRIEVABLE_STATUSES = List.of(
            DocumentIndexStatus.DELETING, DocumentIndexStatus.DELETED);

    @Override
    public List<RetrievedContext> retrieve(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        PriorityQueue<RetrievedContext> bestContexts = new PriorityQueue<>(WORST_CONTEXT_FIRST);
        int pageNumber = 0;
        int scannedDocuments = 0;
        String ownerId = SecurityIdentity.currentOwnerId();
        Page<DocumentEntity> page;
        do {
            page = documentRepository.findPublicByOwnerIdAndIndexStatusNotIn(ownerId, NON_RETRIEVABLE_STATUSES,
                    PageRequest.of(pageNumber++, DOCUMENT_SCAN_PAGE_SIZE, Sort.by("id").ascending()));
            for (DocumentEntity document : page.getContent()) {
                if (scannedDocuments++ >= MAX_SCANNED_DOCUMENTS) {
                    return bestContexts.stream()
                            .sorted(BEST_CONTEXT_FIRST)
                            .toList();
                }
                RetrievedContext context = score(document, terms);
                if (context.score() <= 0) {
                    continue;
                }
                bestContexts.offer(context);
                if (bestContexts.size() > limit) {
                    bestContexts.poll();
                }
            }
        }
        while (page.hasNext());
        return bestContexts.stream()
                .sorted(BEST_CONTEXT_FIRST)
                .toList();
    }

    private RetrievedContext score(DocumentEntity document, Set<String> terms) {
        String title = document.getTitle() == null ? "" : document.getTitle();
        String haystack = (title + "\n" + document.getContent()).toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score += title.toLowerCase(Locale.ROOT).contains(term) ? 2.0 : 1.0;
            }
        }
        return new RetrievedContext(document.getId(), title, snippet(document.getContent(), terms), score);
    }

    private Set<String> tokenize(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String boundedQuery = query == null ? "" : query;
        if (boundedQuery.length() > MAX_QUERY_CHARS) {
            boundedQuery = boundedQuery.substring(0, MAX_QUERY_CHARS);
        }
        for (String raw : TOKEN_SPLITTER.split(boundedQuery.toLowerCase(Locale.ROOT))) {
            if (StringUtils.hasText(raw) && raw.length() >= 2) {
                terms.add(raw);
                if (terms.size() >= MAX_TERMS) {
                    break;
                }
            }
        }
        return terms;
    }

    private String snippet(String content, Set<String> terms) {
        int start = 0;
        String lower = content.toLowerCase(Locale.ROOT);
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

}
