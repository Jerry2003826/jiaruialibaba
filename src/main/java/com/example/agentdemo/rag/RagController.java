package com.example.agentdemo.rag;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.rag.dto.DocumentResponse;
import com.example.agentdemo.rag.dto.RagChatRequest;
import com.example.agentdemo.rag.dto.RagChatResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
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
