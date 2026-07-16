package com.example.agentdemo.tool.tavily;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchServiceTest {

    @Test
    void sendsBearerCredentialAndMapsTheDocumentedSearchResponse() {
        TavilyCredentialService credentials = new TavilyCredentialService("");
        credentials.configure("tvly-test-secret");
        AtomicReference<String> seenApiKey = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        TavilySearchService service = new TavilySearchService(
                credentials,
                new ObjectMapper(),
                URI.create("https://api.tavily.com/search"),
                Duration.ofSeconds(20),
                (endpoint, apiKey, body, timeout) -> {
                    seenApiKey.set(apiKey);
                    seenBody.set(body);
                    return new TavilyHttpResponse(200, """
                            {
                              "query":"latest Spring AI",
                              "answer":"Current release summary",
                              "results":[{
                                "title":"Spring AI",
                                "url":"https://spring.io/projects/spring-ai",
                                "content":"Project page",
                                "score":0.98,
                                "published_date":"2026-07-01"
                              }],
                              "response_time":0.42,
                              "request_id":"req-42"
                            }
                            """);
                });

        Map<String, Object> output = service.search(Map.of(
                "query", "latest Spring AI",
                "search_depth", "advanced",
                "topic", "news",
                "max_results", 5,
                "include_answer", true,
                "include_domains", List.of("spring.io")));

        assertThat(seenApiKey.get()).isEqualTo("tvly-test-secret");
        assertThat(seenBody.get())
                .contains("\"query\":\"latest Spring AI\"")
                .contains("\"search_depth\":\"advanced\"")
                .contains("\"include_domains\":[\"spring.io\"]")
                .doesNotContain("tvly-test-secret");
        assertThat(output)
                .containsEntry("query", "latest Spring AI")
                .containsEntry("answer", "Current release summary")
                .containsEntry("requestId", "req-42")
                .containsKey("responseTime");
        assertThat(output.get("results")).isInstanceOfSatisfying(List.class, results ->
                assertThat(results).singleElement().asString().contains("Spring AI", "spring.io", "0.98"));
        assertThat(output.toString()).doesNotContain("tvly-test-secret");
    }
}
