package com.example.agentdemo.chat;

import com.example.agentdemo.chat.dto.HealthResponse;
import com.example.agentdemo.config.AlibabaProperties;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.DocumentRetriever;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AlibabaHealthService {

    private final AiModelService aiModelService;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentRetriever documentRetriever;
    private final DocumentRepository documentRepository;
    private final AlibabaProperties alibabaProperties;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;
    private final WorkflowRuntimeProperties workflowRuntimeProperties;
    private final boolean mcpEnabled;

    public AlibabaHealthService(AiModelService aiModelService, ObjectProvider<EmbeddingModel> embeddingModelProvider,
            VectorStoreGateway vectorStoreGateway, DocumentRetriever documentRetriever,
            DocumentRepository documentRepository, AlibabaProperties alibabaProperties,
            AlibabaRuntimePolicy alibabaRuntimePolicy, WorkflowRuntimeProperties workflowRuntimeProperties,
            @Value("${demo.mcp.enabled:false}") boolean mcpEnabled) {
        this.aiModelService = aiModelService;
        this.embeddingModelProvider = embeddingModelProvider;
        this.vectorStoreGateway = vectorStoreGateway;
        this.documentRetriever = documentRetriever;
        this.documentRepository = documentRepository;
        this.alibabaProperties = alibabaProperties;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
        this.workflowRuntimeProperties = workflowRuntimeProperties;
        this.mcpEnabled = mcpEnabled;
    }

    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                Instant.now(),
                aiModelService.isModelConfigured() && aiModelService.isChatClientAvailable(),
                aiModelService.modelName(),
                embeddingModelProvider.getIfAvailable() != null,
                vectorStoreGateway.isConfigured(),
                documentRetriever.name(),
                alibabaProperties.isStrictMode(),
                alibabaRuntimePolicy.isFallbackEnabled(),
                alibabaRuntimePolicy.isKeywordFallbackAllowed(),
                documentRepository.count(),
                mcpEnabled,
                workflowRuntimeProperties.getRuntime(),
                workflowRuntimeProperties.isRequirePublishedForRun());
    }

}
