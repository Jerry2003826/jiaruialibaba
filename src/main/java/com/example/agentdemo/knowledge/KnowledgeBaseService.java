package com.example.agentdemo.knowledge;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.knowledge.dto.ChunkPreviewResponse;
import com.example.agentdemo.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentPageResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse;
import com.example.agentdemo.knowledge.dto.TextDocumentRequest;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.TextChunker;
import com.example.agentdemo.security.SecurityIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Knowledge Base product service: manages knowledge bases and their documents (text + file
 * ingestion via Tika), per-KB keyword search with citations, chunk preview and reindex. Documents
 * are stored in the existing {@code rag_documents} table with a {@code kb_id}, reusing the vector
 * indexing / outbox pipeline; per-KB isolation is enforced by owner + kb_id scoping.
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsHan}\\p{Alnum}]+");
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_SCANNED = 5_000;
    private static final List<DocumentIndexStatus> NON_RETRIEVABLE = List.of(
            DocumentIndexStatus.DELETING, DocumentIndexStatus.DELETED);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentIndexingService documentIndexingService;
    private final DocumentManagementService documentManagementService;
    private final DocumentTextExtractor documentTextExtractor;
    private final KnowledgeProperties knowledgeProperties;
    private final RagProperties ragProperties;
    private final Reranker reranker;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository, DocumentRepository documentRepository,
            DocumentIndexingService documentIndexingService, DocumentManagementService documentManagementService,
            DocumentTextExtractor documentTextExtractor, KnowledgeProperties knowledgeProperties,
            RagProperties ragProperties, Reranker reranker, ObjectMapper objectMapper) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentIndexingService = documentIndexingService;
        this.documentManagementService = documentManagementService;
        this.documentTextExtractor = documentTextExtractor;
        this.knowledgeProperties = knowledgeProperties;
        this.ragProperties = ragProperties;
        this.reranker = reranker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Audited(action = "kb.create", resourceType = "knowledge-base", resourceId = "#result.kbId()")
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        RetrievalConfig config = request.retrievalConfig() == null ? RetrievalConfig.defaults()
                : request.retrievalConfig();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(newKbId(), request.name().trim(),
                normalize(request.description()), toJson(config));
        KnowledgeBaseEntity saved = knowledgeBaseRepository.save(entity);
        return toKbResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases() {
        return knowledgeBaseRepository.findByOwnerIdOrderByCreatedAtDesc(SecurityIdentity.currentOwnerId())
                .stream().map(this::toKbResponse).toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getKnowledgeBase(String kbId) {
        return toKbResponse(findKb(kbId));
    }

    @Transactional
    @Audited(action = "document.create", resourceType = "document", resourceId = "#result.documentId()")
    public KnowledgeDocumentResponse addTextDocument(String kbId, TextDocumentRequest request) {
        findKb(kbId);
        if (request.content().length() > knowledgeProperties.getMaxContentChars()) {
            throw new BusinessException("DOCUMENT_CONTENT_TOO_LARGE",
                    "Document content exceeds the maximum size of " + knowledgeProperties.getMaxContentChars()
                            + " characters");
        }
        String title = StringUtils.hasText(request.title()) ? request.title().trim() : "Untitled";
        byte[] bytes = request.content().getBytes(StandardCharsets.UTF_8);
        return ingest(kbId, title, request.content(), "TEXT", null, "text/plain", (long) bytes.length);
    }

    @Transactional
    @Audited(action = "document.create", resourceType = "document", resourceId = "#result.documentId()")
    public KnowledgeDocumentResponse addFileDocument(String kbId, MultipartFile file) {
        findKb(kbId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("DOCUMENT_FILE_EMPTY", "Uploaded file is empty");
        }
        if (file.getSize() > knowledgeProperties.getMaxFileBytes()) {
            throw new BusinessException("DOCUMENT_FILE_TOO_LARGE",
                    "File exceeds the maximum size of " + knowledgeProperties.getMaxFileBytes() + " bytes");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        }
        catch (IOException ex) {
            throw new BusinessException("DOCUMENT_FILE_READ_FAILED", "Failed to read uploaded file", ex);
        }
        String fileName = documentTextExtractor.sanitizeFileName(file.getOriginalFilename());
        String mimeType = documentTextExtractor.detectMimeType(bytes, fileName);
        if (!documentTextExtractor.isAllowedMimeType(mimeType)) {
            throw new BusinessException("DOCUMENT_MIME_NOT_ALLOWED",
                    "Uploaded file type is not allowed: " + (StringUtils.hasText(mimeType) ? mimeType : "unknown"));
        }
        String title = StringUtils.hasText(fileName) ? fileName : "Uploaded file";

        // A parse failure must not lose the document record: persist it as FAILED with the reason.
        String content;
        try {
            content = documentTextExtractor.extractText(bytes, fileName);
        }
        catch (BusinessException ex) {
            DocumentEntity failed = new DocumentEntity(title, "");
            failed.assignKnowledge(kbId, "FILE", fileName, mimeType, (long) bytes.length, sha256(bytes));
            failed.markFailed(ex.getMessage());
            return toDocResponse(documentRepository.save(failed));
        }
        return ingest(kbId, title, content, "FILE", fileName, mimeType, (long) bytes.length);
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentPageResponse listDocuments(String kbId, int page, int size) {
        findKb(kbId);
        validatePage(page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentEntity> result = documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotOrderByCreatedAtDesc(
                SecurityIdentity.currentOwnerId(), kbId, DocumentIndexStatus.DELETED, pageable);
        return new KnowledgeDocumentPageResponse(result.getContent().stream().map(this::toDocResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse getDocument(String kbId, Long documentId) {
        return toDocResponse(findDocument(kbId, documentId));
    }

    @Transactional
    public void deleteDocument(String kbId, Long documentId) {
        findDocument(kbId, documentId);
        // Reuse the vector-aware delete (also audited as document.delete via its own annotation).
        documentManagementService.deleteDocument(documentId);
    }

    @Transactional
    @Audited(action = "document.reindex", resourceType = "document", resourceId = "#documentId")
    public KnowledgeDocumentResponse reindex(String kbId, Long documentId) {
        DocumentEntity document = findDocument(kbId, documentId);
        document.markPending();
        try {
            documentIndexingService.index(document);
        }
        catch (RuntimeException ex) {
            document.markFailed(ex.getMessage());
        }
        return toDocResponse(documentRepository.save(document));
    }

    @Transactional(readOnly = true)
    public ChunkPreviewResponse previewChunks(String kbId, Long documentId) {
        DocumentEntity document = findDocument(kbId, documentId);
        int chunkSize = retrievalConfig(findKb(kbId)).chunkSizeOr(ragProperties.getRag().getChunkSize());
        int chunkOverlap = retrievalConfig(findKb(kbId)).chunkOverlapOr(ragProperties.getRag().getChunkOverlap());
        List<String> chunks = new TextChunker(chunkSize, chunkOverlap).split(document.getContent());
        List<ChunkPreviewResponse.Chunk> preview = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            preview.add(new ChunkPreviewResponse.Chunk(i, chunks.get(i).length(), chunks.get(i)));
        }
        return new ChunkPreviewResponse(documentId, chunkSize, chunkOverlap, preview);
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse search(String kbId, String query, Integer requestedTopK) {
        KnowledgeBaseEntity kb = findKb(kbId);
        int topK = requestedTopK != null && requestedTopK > 0 ? Math.min(requestedTopK, 50)
                : retrievalConfig(kb).topKOr(ragProperties.getRag().getTopK());
        List<Citation> candidates = keywordSearch(kbId, query, topK);
        List<Citation> ranked = reranker.rerank(query, candidates, topK);
        return new KnowledgeSearchResponse(kbId, query, ranked);
    }

    private KnowledgeDocumentResponse ingest(String kbId, String title, String content, String sourceType,
            String fileName, String mimeType, Long sizeBytes) {
        DocumentEntity document = new DocumentEntity(title, content);
        document.assignKnowledge(kbId, sourceType, fileName, mimeType, sizeBytes,
                sha256(content.getBytes(StandardCharsets.UTF_8)));
        DocumentEntity saved = documentRepository.save(document);
        try {
            documentIndexingService.index(saved);
        }
        catch (RuntimeException ex) {
            log.warn("Indexing failed for kb {} document {}", kbId, saved.getId(), ex);
            saved.markFailed(ex.getMessage());
            saved = documentRepository.save(saved);
        }
        return toDocResponse(saved);
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
        Page<DocumentEntity> page;
        do {
            page = documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotIn(ownerId, kbId, NON_RETRIEVABLE,
                    PageRequest.of(pageNumber++, SCAN_PAGE_SIZE, Sort.by("id").ascending()));
            for (DocumentEntity document : page.getContent()) {
                if (scanned++ >= MAX_SCANNED) {
                    break;
                }
                double score = score(document, terms);
                if (score > 0) {
                    scored.add(new Citation(document.getId(), safeTitle(document), 0,
                            snippet(document.getContent(), terms), score));
                }
            }
        }
        while (page.hasNext() && scanned < MAX_SCANNED);
        scored.sort(Comparator.comparingDouble(Citation::score).reversed()
                .thenComparing(Citation::documentId));
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

    private RetrievalConfig retrievalConfig(KnowledgeBaseEntity kb) {
        if (!StringUtils.hasText(kb.getRetrievalConfigJson())) {
            return RetrievalConfig.defaults();
        }
        try {
            return objectMapper.readValue(kb.getRetrievalConfigJson(), RetrievalConfig.class);
        }
        catch (JsonProcessingException ex) {
            return RetrievalConfig.defaults();
        }
    }

    private KnowledgeBaseEntity findKb(String kbId) {
        return knowledgeBaseRepository.findByKbIdAndOwnerId(kbId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found: " + kbId));
    }

    private DocumentEntity findDocument(String kbId, Long documentId) {
        if (documentId == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found");
        }
        findKb(kbId);
        DocumentEntity document = documentRepository
                .findByIdAndKbIdAndOwnerId(documentId, kbId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId + " in kb " + kbId));
        if (document.getIndexStatus() == DocumentIndexStatus.DELETED) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + documentId);
        }
        return document;
    }

    private KnowledgeBaseResponse toKbResponse(KnowledgeBaseEntity entity) {
        long count = documentRepository.countByOwnerIdAndKbId(entity.getOwnerId(), entity.getKbId());
        return new KnowledgeBaseResponse(entity.getKbId(), entity.getName(), entity.getDescription(),
                retrievalConfig(entity), count, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private KnowledgeDocumentResponse toDocResponse(DocumentEntity document) {
        return new KnowledgeDocumentResponse(document.getId(), document.getKbId(), document.getTitle(),
                document.getSourceType(), document.getFileName(), document.getMimeType(), document.getSizeBytes(),
                document.getContent() == null ? 0 : document.getContent().length(), document.getIndexStatus(),
                document.getErrorMessage(), document.getCreatedAt());
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new BusinessException("DOCUMENT_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("DOCUMENT_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeTitle(DocumentEntity document) {
        return document.getTitle() == null ? "" : document.getTitle();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("KNOWLEDGE_CONFIG_SERIALIZATION_FAILED",
                    "Failed to serialize retrieval config", ex);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String newKbId() {
        return "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

}
