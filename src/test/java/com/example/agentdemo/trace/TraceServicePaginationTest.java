package com.example.agentdemo.trace;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.dto.RunPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceServicePaginationTest {

    @Test
    void listRunsReturnsPagedResponseWithFilters() {
        RunRepository runRepository = mock(RunRepository.class);
        RunStepRepository runStepRepository = mock(RunStepRepository.class);
        TraceService traceService = new TraceService(runRepository, runStepRepository, new com.fasterxml.jackson.databind.ObjectMapper());
        RunEntity entity = new RunEntity("run-1", RunType.CHAT, RunStatus.SUCCEEDED, "{}", Instant.now());
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startedAt"));
        when(runRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        RunPageResponse page = traceService.listRuns(RunType.CHAT, RunStatus.SUCCEEDED, 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().runId()).isEqualTo("run-1");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void listRunsRejectsInvalidPageSize() {
        RunRepository runRepository = mock(RunRepository.class);
        TraceService traceService = new TraceService(runRepository, mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> traceService.listRuns(null, null, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("RUN_QUERY_INVALID");
    }

    @Test
    void toJsonRedactsSensitiveKeys() throws Exception {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        String json = traceService.toJson(Map.of("apiKey", "secret-value", "message", "hello"));

        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("apiKey").asText()).isEqualTo("[REDACTED]");
        assertThat(node.path("message").asText()).isEqualTo("hello");
    }

    @Test
    void toJsonRedactsSensitiveKeysInsideEmbeddedJsonString() throws Exception {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        // Mirrors TracingToolCallback: a tool's raw JSON arguments arrive as a *string* under a
        // non-sensitive "arguments" key, so naive key-based redaction would miss the embedded secret.
        String toolArguments = "{\"api_key\":\"sk-leak-123\",\"city\":\"Beijing\"}";
        String json = traceService.toJson(Map.of("toolName", "weather", "arguments", toolArguments));

        assertThat(json).doesNotContain("sk-leak-123");
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        com.fasterxml.jackson.databind.JsonNode embedded =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(node.path("arguments").asText());
        assertThat(embedded.path("api_key").asText()).isEqualTo("[REDACTED]");
        assertThat(embedded.path("city").asText()).isEqualTo("Beijing");
    }

    @Test
    void toJsonRedactsSensitiveKeysWhenWholeValueIsAJsonString() throws Exception {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        String json = traceService.toJson("{\"password\":\"hunter2\",\"keep\":\"ok\"}");

        assertThat(json).doesNotContain("hunter2");
        // The whole value was a JSON string, so it round-trips as a (sanitized) string literal.
        String inner = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).asText();
        com.fasterxml.jackson.databind.JsonNode embedded =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(inner);
        assertThat(embedded.path("password").asText()).isEqualTo("[REDACTED]");
        assertThat(embedded.path("keep").asText()).isEqualTo("ok");
    }

    @Test
    void toJsonRedactsSensitiveKeysInsideEmbeddedJsonArrayString() throws Exception {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        String json = traceService.toJson(Map.of("messages", "[{\"token\":\"sk-array-leak\",\"role\":\"user\"}]"));

        assertThat(json).doesNotContain("sk-array-leak");
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        com.fasterxml.jackson.databind.JsonNode embedded =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(node.path("messages").asText());
        assertThat(embedded.get(0).path("token").asText()).isEqualTo("[REDACTED]");
        assertThat(embedded.get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void toJsonKeepsMalformedJsonLikeTextUnchanged() throws Exception {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        // Looks like a container (starts with '{', ends with '}') but is not valid JSON, so it must
        // be preserved verbatim rather than dropped or misinterpreted.
        String json = traceService.toJson(Map.of("note", "{oops not json}"));

        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("note").asText()).isEqualTo("{oops not json}");
    }

    @Test
    void toJsonSummarizesOversizedPayload() throws Exception {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < 80; i++) {
            payload.put("field" + i, "x".repeat(1000));
        }
        String json = traceService.toJson(payload);

        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("payloadStored").asBoolean()).isFalse();
        assertThat(node.path("sha256").asText()).hasSize(64);
    }

    @Test
    void toJsonDegradesPathologicallyNestedInputInsteadOfThrowing() {
        TraceService traceService = new TraceService(mock(RunRepository.class), mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        // Deeply nested input would otherwise either trip Jackson's nesting limit (an
        // IllegalArgumentException from valueToTree) or overflow the sanitizer's recursion. Tracing
        // must never break the operation it traces, so toJson degrades to a safe value instead.
        Map<String, Object> root = new java.util.HashMap<>();
        Map<String, Object> current = root;
        for (int i = 0; i < 3000; i++) {
            Map<String, Object> child = new java.util.HashMap<>();
            current.put("a", child);
            current = child;
        }

        String json = traceService.toJson(root);

        assertThat(json).isNotBlank();
    }

}
