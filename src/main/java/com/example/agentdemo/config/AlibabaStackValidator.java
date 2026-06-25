package com.example.agentdemo.config;

import com.example.agentdemo.rag.DocumentRetriever;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates that the Alibaba stack is fully configured when strict mode or fallback-disabled policy applies.
 */
@Component
public class AlibabaStackValidator implements ApplicationRunner {

    private final AlibabaRuntimePolicy alibabaRuntimePolicy;
    private final Environment environment;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentRetriever documentRetriever;
    private final RagProperties ragProperties;

    public AlibabaStackValidator(AlibabaRuntimePolicy alibabaRuntimePolicy, Environment environment,
            ObjectProvider<ChatClient> chatClientProvider, ObjectProvider<EmbeddingModel> embeddingModelProvider,
            VectorStoreGateway vectorStoreGateway, DocumentRetriever documentRetriever,
            RagProperties ragProperties) {
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
        this.environment = environment;
        this.chatClientProvider = chatClientProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.vectorStoreGateway = vectorStoreGateway;
        this.documentRetriever = documentRetriever;
        this.ragProperties = ragProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!alibabaRuntimePolicy.isAlibabaStackRequired()) {
            return;
        }
        List<String> missing = collectMissingRequirements();
        if (!missing.isEmpty()) {
            String mode = alibabaRuntimePolicy.isStrictMode()
                    ? "Alibaba strict mode is enabled"
                    : "demo.ai.fallback-enabled=false";
            throw new IllegalStateException(mode + " but required configuration is missing: "
                    + String.join(", ", missing));
        }
    }

    List<String> collectMissingRequirements() {
        List<String> missing = new ArrayList<>();
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
        if (!StringUtils.hasText(apiKey) || Objects.equals(apiKey, "your-api-key")) {
            missing.add("AI_DASHSCOPE_API_KEY (or DASHSCOPE_API_KEY)");
        }
        if (chatClientProvider.getIfAvailable() == null) {
            missing.add("ChatClient bean (check AI_DASHSCOPE_API_KEY and spring.ai.dashscope.*)");
        }
        if (embeddingModelProvider.getIfAvailable() == null) {
            missing.add("EmbeddingModel bean (check AI_DASHSCOPE_API_KEY and demo.ai.embedding-model)");
        }
        if (!vectorStoreGateway.isConfigured()) {
            missing.add("DASHVECTOR_ENDPOINT and DASHVECTOR_API_KEY");
        }
        if (!"dashvector".equalsIgnoreCase(ragProperties.getRag().getRetriever())) {
            missing.add("DEMO_RAG_RETRIEVER=dashvector");
        }
        if (!documentRetriever.name().equals("DashVectorDocumentRetriever")) {
            missing.add("DashVectorDocumentRetriever (ensure DashVector is configured and retriever=dashvector)");
        }
        return missing;
    }

}
