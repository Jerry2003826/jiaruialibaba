package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.PageRequestValidator;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.knowledge.dto.ChunkPreviewResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.TextChunker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeChunkPreviewService {

    private static final int DEFAULT_CHUNK_PREVIEW_PAGE = 0;
    private static final int DEFAULT_CHUNK_PREVIEW_SIZE = 20;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final RagProperties ragProperties;
    private final KnowledgeResponseMapper knowledgeResponseMapper;
    private final PageRequestValidator pageRequestValidator;

    @Autowired
    public KnowledgeChunkPreviewService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            RagProperties ragProperties, KnowledgeResponseMapper knowledgeResponseMapper,
            PageRequestValidator pageRequestValidator) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.ragProperties = ragProperties;
        this.knowledgeResponseMapper = knowledgeResponseMapper;
        this.pageRequestValidator = pageRequestValidator;
    }

    public KnowledgeChunkPreviewService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            RagProperties ragProperties, KnowledgeResponseMapper knowledgeResponseMapper) {
        this(knowledgeBaseAccessService, ragProperties, knowledgeResponseMapper, new PageRequestValidator());
    }

    @Transactional(readOnly = true)
    public ChunkPreviewResponse previewChunks(String kbId, Long documentId) {
        return previewChunks(kbId, documentId, DEFAULT_CHUNK_PREVIEW_PAGE, DEFAULT_CHUNK_PREVIEW_SIZE);
    }

    @Transactional(readOnly = true)
    public ChunkPreviewResponse previewChunks(String kbId, Long documentId, Integer page, Integer size) {
        ChunkPreviewResponse preview = buildChunkPreview(kbId, documentId);
        int resolvedPage = page == null ? DEFAULT_CHUNK_PREVIEW_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_CHUNK_PREVIEW_SIZE : size;
        pageRequestValidator.build(resolvedPage, resolvedSize, "DOCUMENT_QUERY_INVALID", org.springframework.data.domain.Sort.unsorted());
        long rawFromIndex = (long) resolvedPage * resolvedSize;
        int fromIndex = (int) Math.min(rawFromIndex, preview.totalChunks());
        long rawToIndex = rawFromIndex + resolvedSize;
        int toIndex = (int) Math.min(rawToIndex, preview.totalChunks());
        int totalPages = preview.totalChunks() == 0 ? 0 : (preview.totalChunks() + resolvedSize - 1) / resolvedSize;
        return new ChunkPreviewResponse(preview.documentId(), preview.chunkSize(), preview.chunkOverlap(), resolvedPage,
                resolvedSize, preview.totalChunks(), totalPages, preview.chunks().subList(fromIndex, toIndex));
    }

    private ChunkPreviewResponse buildChunkPreview(String kbId, Long documentId) {
        KnowledgeBaseEntity kb = knowledgeBaseAccessService.findKb(kbId);
        DocumentEntity document = knowledgeBaseAccessService.findDocument(kbId, documentId);
        RetrievalConfig config = knowledgeResponseMapper.retrievalConfig(kb);
        int chunkSize = config.chunkSizeOr(ragProperties.getRag().getChunkSize());
        int chunkOverlap = config.chunkOverlapOr(ragProperties.getRag().getChunkOverlap());
        List<String> chunks = new TextChunker(chunkSize, chunkOverlap).split(document.getContent());
        List<ChunkPreviewResponse.Chunk> preview = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            preview.add(new ChunkPreviewResponse.Chunk(i, chunks.get(i).length(), chunks.get(i)));
        }
        int totalChunks = preview.size();
        return new ChunkPreviewResponse(documentId, chunkSize, chunkOverlap, 0, totalChunks, totalChunks,
                totalChunks == 0 ? 0 : 1, preview);
    }

}
