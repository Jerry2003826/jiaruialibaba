package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.knowledge.dto.ChunkPreviewResponse;
import com.example.agentdemo.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentPageResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchRequest;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse;
import com.example.agentdemo.knowledge.dto.TextDocumentRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Knowledge Base API. Management requires {@code rag.write}, reads {@code rag.read}, search
 * {@code rag.query} (reusing the existing RAG scopes).
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.createKnowledgeBase(request));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> list() {
        return ApiResponse.ok(knowledgeBaseService.listKnowledgeBases());
    }

    @GetMapping("/{kbId}")
    public ApiResponse<KnowledgeBaseResponse> get(@PathVariable String kbId) {
        return ApiResponse.ok(knowledgeBaseService.getKnowledgeBase(kbId));
    }

    @PostMapping("/{kbId}/documents/text")
    public ApiResponse<KnowledgeDocumentResponse> addText(@PathVariable String kbId,
            @Valid @RequestBody TextDocumentRequest request) {
        return ApiResponse.ok(knowledgeBaseService.addTextDocument(kbId, request));
    }

    @PostMapping(value = "/{kbId}/documents/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDocumentResponse> addFile(@PathVariable String kbId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(knowledgeBaseService.addFileDocument(kbId, file));
    }

    @GetMapping("/{kbId}/documents")
    public ApiResponse<KnowledgeDocumentPageResponse> listDocuments(@PathVariable String kbId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(knowledgeBaseService.listDocuments(kbId, page, size));
    }

    @GetMapping("/{kbId}/documents/{documentId}")
    public ApiResponse<KnowledgeDocumentResponse> getDocument(@PathVariable String kbId,
            @PathVariable Long documentId) {
        return ApiResponse.ok(knowledgeBaseService.getDocument(kbId, documentId));
    }

    @GetMapping("/{kbId}/documents/{documentId}/chunks")
    public ApiResponse<ChunkPreviewResponse> previewChunks(@PathVariable String kbId,
            @PathVariable Long documentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(knowledgeBaseService.previewChunks(kbId, documentId, page, size));
    }

    @DeleteMapping("/{kbId}/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String kbId, @PathVariable Long documentId) {
        knowledgeBaseService.deleteDocument(kbId, documentId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{kbId}/documents/{documentId}/reindex")
    public ApiResponse<KnowledgeDocumentResponse> reindex(@PathVariable String kbId,
            @PathVariable Long documentId) {
        return ApiResponse.ok(knowledgeBaseService.reindex(kbId, documentId));
    }

    @PostMapping("/{kbId}/search")
    public ApiResponse<KnowledgeSearchResponse> search(@PathVariable String kbId,
            @Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(knowledgeBaseService.search(kbId, request.query(), request.topK()));
    }

}
