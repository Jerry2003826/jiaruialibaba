package com.example.agentdemo.rag;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.rag.dto.DocumentResponse;
import com.example.agentdemo.rag.dto.RagChatRequest;
import com.example.agentdemo.rag.dto.RagChatResponse;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
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
        return retrieve(query, topK, null);
    }

    public List<RetrievedContext> retrieve(String query, int topK, String runId) {
        int limit = normalizeTopK(topK);
        return retrieveWithOptionalTrace(runId, query, limit);
    }

    public RagChatResponse chat(RagChatRequest request) {
        TraceRun run = traceService.startRun(RunType.RAG_CHAT, request);
        TraceStep activeStep = null;
        try {
            List<RetrievedContext> contexts = retrieveForChat(run.runId(), request.message());

            String contextText = contexts.stream()
                    .map(context -> "Document " + context.documentId() + " (" + context.title() + "):\n"
                            + context.snippet())
                    .collect(Collectors.joining("\n\n"));
            String prompt = "Question:\n" + request.message() + "\n\nRetrieved context:\n" + contextText;
            activeStep = traceService.startTraceStep(run.runId(), "rag_generate_answer",
                    Map.of("prompt", prompt, "contextCount", contexts.size()));
            AiModelResult modelResult = aiModelService.generate(RAG_SYSTEM_PROMPT, prompt);
            String answer = modelResult.fallback()
                    ? fallbackAnswer(request.message(), contexts)
                    : modelResult.answer();
            RagChatResponse response = new RagChatResponse(answer, run.runId(), contexts);
            traceService.completeStep(activeStep.stepId(),
                    Map.of("answer", answer, "fallback", modelResult.fallback()));
            activeStep = null;
            traceService.markRunSucceeded(run.runId(), response);
            return response;
        }
        catch (RuntimeException ex) {
            if (activeStep != null) {
                traceService.failStep(activeStep.stepId(), ex);
            }
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    private List<RetrievedContext> retrieveForChat(String runId, String message) {
        int topK = normalizeTopK(ragProperties.getRag().getTopK());
        return retrieveWithOptionalTrace(runId, message, topK);
    }

    private List<RetrievedContext> retrieveWithOptionalTrace(String runId, String message, int topK) {
        TraceStep primaryStep = startRetrieveStep(runId, "rag_retrieve",
                Map.of("query", message, "retriever", documentRetriever.name()));
        try {
            List<RetrievedContext> contexts = documentRetriever.retrieve(message, topK);
            completeStep(primaryStep, contexts);
            return contexts;
        }
        catch (RuntimeException ex) {
            failStep(primaryStep, ex);
            if (!ragProperties.getRag().isKeywordFallbackEnabled() || isKeywordRetrieverActive()) {
                throw ex;
            }
            return retrieveWithKeywordFallback(runId, message, topK, ex);
        }
    }

    private List<RetrievedContext> retrieveWithKeywordFallback(String runId, String message, int topK,
            RuntimeException primaryFailure) {
        TraceStep fallbackStep = startRetrieveStep(runId, "rag_keyword_fallback_retrieve",
                Map.of("query", message, "reason", failureReason(primaryFailure), "retriever",
                        keywordDocumentRetriever.name()));
        try {
            List<RetrievedContext> contexts = keywordDocumentRetriever.retrieve(message, topK);
            completeStep(fallbackStep, contexts);
            return contexts;
        }
        catch (RuntimeException fallbackFailure) {
            failStep(fallbackStep, fallbackFailure);
            throw fallbackFailure;
        }
    }

    private TraceStep startRetrieveStep(String runId, String nodeName, Object input) {
        if (runId == null) {
            return null;
        }
        return traceService.startTraceStep(runId, nodeName, input);
    }

    private void completeStep(TraceStep step, Object output) {
        if (step != null) {
            traceService.completeStep(step.stepId(), output);
        }
    }

    private void failStep(TraceStep step, RuntimeException ex) {
        if (step != null) {
            traceService.failStep(step.stepId(), ex);
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
