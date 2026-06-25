package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class KeywordDocumentRetriever implements DocumentRetriever {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsHan}\\p{Alnum}]+");

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
        Set<String> terms = tokenize(query);
        return documentRepository.findByIndexStatusNotIn(NON_RETRIEVABLE_STATUSES)
                .stream()
                .map(document -> score(document, terms))
                .filter(context -> context.score() > 0)
                .sorted(Comparator.comparingDouble(RetrievedContext::score).reversed()
                        .thenComparing(RetrievedContext::documentId))
                .limit(limit)
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
        for (String raw : TOKEN_SPLITTER.split(query.toLowerCase(Locale.ROOT))) {
            if (StringUtils.hasText(raw) && raw.length() >= 2) {
                terms.add(raw);
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
