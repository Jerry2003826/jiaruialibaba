package com.example.agentdemo.rag;

import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceTest {

    @Test
    void retrieveFallsBackToKeywordRetrieverWhenPrimaryFails() {
        DocumentRetriever primaryRetriever = mock(DocumentRetriever.class);
        KeywordDocumentRetriever keywordRetriever = mock(KeywordDocumentRetriever.class);
        RagProperties ragProperties = new RagProperties();
        IllegalStateException primaryFailure = new IllegalStateException("vector unavailable");
        RetrievedContext fallbackContext = new RetrievedContext(1L, "Doc", "keyword match", 1.0);

        when(primaryRetriever.name()).thenReturn("DashVectorDocumentRetriever");
        when(primaryRetriever.retrieve("question", 3)).thenThrow(primaryFailure);
        when(keywordRetriever.name()).thenReturn("KeywordDocumentRetriever");
        when(keywordRetriever.retrieve("question", 3)).thenReturn(List.of(fallbackContext));

        RagService service = new RagService(null, primaryRetriever, keywordRetriever, null, null, null,
                ragProperties);

        List<RetrievedContext> contexts = service.retrieve("question", 3);

        assertThat(contexts).containsExactly(fallbackContext);
    }

    @Test
    void retrieveRecordsPrimaryFailureAndFallbackStepWhenRunIdIsProvided() {
        DocumentRetriever primaryRetriever = mock(DocumentRetriever.class);
        KeywordDocumentRetriever keywordRetriever = mock(KeywordDocumentRetriever.class);
        TraceService traceService = mock(TraceService.class);
        RagProperties ragProperties = new RagProperties();
        IllegalStateException primaryFailure = new IllegalStateException("vector unavailable");
        RetrievedContext fallbackContext = new RetrievedContext(1L, "Doc", "keyword match", 1.0);
        TraceStep primaryStep = step("primary-step", "rag_retrieve");
        TraceStep fallbackStep = step("fallback-step", "rag_keyword_fallback_retrieve");

        when(primaryRetriever.name()).thenReturn("DashVectorDocumentRetriever");
        when(primaryRetriever.retrieve("question", 3)).thenThrow(primaryFailure);
        when(keywordRetriever.name()).thenReturn("KeywordDocumentRetriever");
        when(keywordRetriever.retrieve("question", 3)).thenReturn(List.of(fallbackContext));
        when(traceService.startTraceStep(eq("run-1"), eq("rag_retrieve"), any())).thenReturn(primaryStep);
        when(traceService.startTraceStep(eq("run-1"), eq("rag_keyword_fallback_retrieve"), any()))
                .thenReturn(fallbackStep);

        RagService service = new RagService(null, primaryRetriever, keywordRetriever, null, null, traceService,
                ragProperties);

        List<RetrievedContext> contexts = service.retrieve("question", 3, "run-1");

        assertThat(contexts).containsExactly(fallbackContext);
        verify(traceService).failStep("primary-step", primaryFailure);
        verify(traceService).completeStep("fallback-step", List.of(fallbackContext));
    }

    @Test
    void retrievePropagatesPrimaryFailureWhenFallbackIsDisabled() {
        DocumentRetriever primaryRetriever = mock(DocumentRetriever.class);
        KeywordDocumentRetriever keywordRetriever = mock(KeywordDocumentRetriever.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setKeywordFallbackEnabled(false);
        IllegalStateException primaryFailure = new IllegalStateException("vector unavailable");

        when(primaryRetriever.name()).thenReturn("DashVectorDocumentRetriever");
        when(primaryRetriever.retrieve("question", 3)).thenThrow(primaryFailure);

        RagService service = new RagService(null, primaryRetriever, keywordRetriever, null, null, null,
                ragProperties);

        assertThatThrownBy(() -> service.retrieve("question", 3))
                .isSameAs(primaryFailure);
        verify(keywordRetriever, never()).retrieve(any(), anyInt());
    }

    private static TraceStep step(String stepId, String nodeName) {
        return new TraceStep(stepId, "run-1", nodeName);
    }

}
