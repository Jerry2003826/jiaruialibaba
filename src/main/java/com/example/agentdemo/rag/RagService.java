package com.example.agentdemo.rag;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.rag.dto.DocumentResponse;
import com.example.agentdemo.rag.dto.RagChatRequest;
import com.example.agentdemo.rag.dto.RagChatResponse;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final String RAG_SYSTEM_PROMPT = """
            You are a RAG assistant. Answer using the retrieved context when it is relevant.
            If the context is empty or not enough, say what is missing instead of inventing details.
            """;

    private final DocumentPersistenceService documentPersistenceService;
    private final DocumentRetriever documentRetriever;
    private final KeywordDocumentRetriever keywordDocumentRetriever;
    private final DocumentIndexingService documentIndexingService;
    private final AiModelService aiModelService;
    private final TraceService traceService;
    private final RagProperties ragProperties;

    public RagService(DocumentPersistenceService documentPersistenceService, DocumentRetriever documentRetriever,
            KeywordDocumentRetriever keywordDocumentRetriever, DocumentIndexingService documentIndexingService,
            AiModelService aiModelService, TraceService traceService, RagProperties ragProperties) {
        this.documentPersistenceService = documentPersistenceService;
        this.documentRetriever = documentRetriever;
        this.keywordDocumentRetriever = keywordDocumentRetriever;
        this.documentIndexingService = documentIndexingService;
        this.aiModelService = aiModelService;
        this.traceService = traceService;
        this.ragProperties = ragProperties;
    }

    public DocumentResponse saveDocument(DocumentRequest request) {
        DocumentEntity document = documentPersistenceService.save(request);
        documentIndexingService.index(document);
        return toDocumentResponse(document);
    }

    @Transactional(readOnly = true)
    public List<RetrievedContext> retrieve(String query, int topK) {
        int limit = normalizeTopK(topK);
        return documentRetriever.retrieve(query, limit);
    }

    public RagChatResponse chat(RagChatRequest request) {
        RunEntity run = traceService.createRun(RunType.RAG_CHAT, request);
        RunStepEntity activeStep = null;
        try {
            List<RetrievedContext> contexts = retrieveForChat(run.getRunId(), request.message());

            String contextText = contexts.stream()
                    .map(context -> "Document " + context.documentId() + " (" + context.title() + "):\n"
                            + context.snippet())
                    .collect(Collectors.joining("\n\n"));
            String prompt = "Question:\n" + request.message() + "\n\nRetrieved context:\n" + contextText;
            activeStep = traceService.startStep(run.getRunId(), "rag_generate_answer",
                    Map.of("prompt", prompt, "contextCount", contexts.size()));
            AiModelResult modelResult = aiModelService.generate(RAG_SYSTEM_PROMPT, prompt);
            String answer = modelResult.fallback()
                    ? fallbackAnswer(request.message(), contexts)
                    : modelResult.answer();
            RagChatResponse response = new RagChatResponse(answer, run.getRunId(), contexts);
            traceService.completeStep(activeStep.getStepId(),
                    Map.of("answer", answer, "fallback", modelResult.fallback()));
            activeStep = null;
            traceService.markRunSucceeded(run.getRunId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            if (activeStep != null) {
                traceService.failStep(activeStep.getStepId(), ex);
            }
            traceService.markRunFailed(run.getRunId(), ex);
            throw ex;
        }
    }

    private List<RetrievedContext> retrieveForChat(String runId, String message) {
        int topK = normalizeTopK(ragProperties.getRag().getTopK());
        RunStepEntity primaryStep = traceService.startStep(runId, "rag_retrieve",
                Map.of("query", message, "retriever", documentRetriever.name()));
        try {
            List<RetrievedContext> contexts = documentRetriever.retrieve(message, topK);
            traceService.completeStep(primaryStep.getStepId(), contexts);
            return contexts;
        }
        catch (RuntimeException ex) {
            traceService.failStep(primaryStep.getStepId(), ex);
            if (!ragProperties.getRag().isKeywordFallbackEnabled() || isKeywordRetrieverActive()) {
                throw ex;
            }
            return retrieveWithKeywordFallback(runId, message, topK, ex);
        }
    }

    private List<RetrievedContext> retrieveWithKeywordFallback(String runId, String message, int topK,
            RuntimeException primaryFailure) {
        RunStepEntity fallbackStep = traceService.startStep(runId, "rag_keyword_fallback_retrieve",
                Map.of("query", message, "reason", failureReason(primaryFailure), "retriever",
                        keywordDocumentRetriever.name()));
        try {
            List<RetrievedContext> contexts = keywordDocumentRetriever.retrieve(message, topK);
            traceService.completeStep(fallbackStep.getStepId(), contexts);
            return contexts;
        }
        catch (RuntimeException fallbackFailure) {
            traceService.failStep(fallbackStep.getStepId(), fallbackFailure);
            throw fallbackFailure;
        }
    }

    private boolean isKeywordRetrieverActive() {
        return documentRetriever == keywordDocumentRetriever
                || documentRetriever.name().equals(keywordDocumentRetriever.name());
    }

    private String failureReason(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private int normalizeTopK(int topK) {
        return Math.max(1, Math.min(topK, 20));
    }

    private String fallbackAnswer(String question, List<RetrievedContext> contexts) {
        if (contexts.isEmpty()) {
            return "No local document context was retrieved for: " + question;
        }
        return "Retrieved " + contexts.size() + " context item(s). Top context: " + contexts.getFirst().snippet();
    }

    private DocumentResponse toDocumentResponse(DocumentEntity document) {
        return new DocumentResponse(document.getId(), document.getTitle(), document.getContent().length(),
                document.getCreatedAt());
    }

}
