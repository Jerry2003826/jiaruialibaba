package com.example.agentdemo.rag;

import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.support.TestAlibabaPolicies;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceStrictTest {

    @Test
    void doesNotUseKeywordFallbackWhenStrictModeEnabled() {
        DocumentRetriever primaryRetriever = mock(DocumentRetriever.class);
        KeywordDocumentRetriever keywordRetriever = mock(KeywordDocumentRetriever.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setKeywordFallbackEnabled(true);
        IllegalStateException primaryFailure = new IllegalStateException("vector unavailable");

        when(primaryRetriever.name()).thenReturn("DashVectorDocumentRetriever");
        when(primaryRetriever.retrieve("question", 3)).thenThrow(primaryFailure);

        RagService service = new RagService(null, primaryRetriever, keywordRetriever, null, null, null, null,
                ragProperties, TestAlibabaPolicies.strictMode());

        assertThatThrownBy(() -> service.retrieve("question", 3))
                .isSameAs(primaryFailure);
        verify(keywordRetriever, never()).retrieve(any(), anyInt());
    }

}
