package com.example.agentdemo.rag;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.rag.dto.DocumentDetailResponse;
import com.example.agentdemo.rag.dto.DocumentPageResponse;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.rag.dto.DocumentResponse;
import com.example.agentdemo.rag.dto.RagChatRequest;
import com.example.agentdemo.rag.dto.RagChatResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final DocumentManagementService documentManagementService;

    public RagController(RagService ragService, DocumentManagementService documentManagementService) {
        this.ragService = ragService;
        this.documentManagementService = documentManagementService;
    }

    @GetMapping("/documents")
    public ApiResponse<DocumentPageResponse> listDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentManagementService.listDocuments(page, size));
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<DocumentDetailResponse> getDocument(@PathVariable Long documentId) {
        return ApiResponse.ok(documentManagementService.getDocument(documentId));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long documentId) {
        documentManagementService.deleteDocument(documentId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/documents/{documentId}")
    public ApiResponse<DocumentResponse> updateDocument(@PathVariable Long documentId,
            @Valid @RequestBody DocumentRequest request) {
        return ApiResponse.ok(documentManagementService.updateDocument(documentId, request));
    }

    @PostMapping("/documents")
    public ApiResponse<DocumentResponse> saveDocument(@Valid @RequestBody DocumentRequest request) {
        return ApiResponse.ok(ragService.saveDocument(request));
    }

    @PostMapping("/chat")
    public ApiResponse<RagChatResponse> chat(@Valid @RequestBody RagChatRequest request) {
        return ApiResponse.ok(ragService.chat(request));
    }

}
