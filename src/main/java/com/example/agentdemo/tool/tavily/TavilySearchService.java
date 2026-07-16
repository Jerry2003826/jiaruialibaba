package com.example.agentdemo.tool.tavily;

import com.example.agentdemo.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TavilySearchService {

    private static final Set<String> SEARCH_DEPTHS = Set.of("basic", "advanced", "fast", "ultra-fast");
    private static final Set<String> TOPICS = Set.of("general", "news", "finance");
    private static final Set<String> TIME_RANGES = Set.of("day", "week", "month", "year", "d", "w", "m", "y");

    private final TavilyCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;
    private final TavilyHttpTransport transport;

    @Autowired
    public TavilySearchService(
            TavilyCredentialService credentialService,
            ObjectMapper objectMapper,
            @Value("${demo.tavily.endpoint:https://api.tavily.com/search}") String endpoint,
            @Value("${demo.tavily.timeout-ms:20000}") long timeoutMs) {
        this(credentialService, objectMapper, URI.create(endpoint), Duration.ofMillis(timeoutMs),
                new JavaNetTavilyHttpTransport());
    }

    TavilySearchService(TavilyCredentialService credentialService, ObjectMapper objectMapper, URI endpoint,
            Duration timeout, TavilyHttpTransport transport) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.transport = transport;
    }

    public Map<String, Object> search(Map<String, Object> rawArguments) {
        Map<String, Object> requestBody = normalizeArguments(rawArguments);
        String apiKey = credentialService.requireApiKey();
        try {
            TavilyHttpResponse response = transport.post(
                    endpoint,
                    apiKey,
                    objectMapper.writeValueAsString(requestBody),
                    timeout);
            return mapResponse(response, String.valueOf(requestBody.get("query")));
        }
        catch (BusinessException ex) {
            throw ex;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("TAVILY_SEARCH_FAILED", "Tavily search was interrupted", ex);
        }
        catch (IOException ex) {
            throw new BusinessException("TAVILY_SEARCH_FAILED", "Tavily search request failed", ex);
        }
    }

    private Map<String, Object> normalizeArguments(Map<String, Object> rawArguments) {
        Map<String, Object> source = rawArguments == null ? Map.of() : rawArguments;
        String query = requiredString(source, "query");
        String searchDepth = allowedString(source, "search_depth", "basic", SEARCH_DEPTHS);
        String topic = allowedString(source, "topic", "general", TOPICS);
        int maxResults = boundedInt(source, "max_results", 5, 1, 20);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", query);
        request.put("search_depth", searchDepth);
        request.put("topic", topic);
        request.put("max_results", maxResults);
        request.put("include_answer", booleanValue(source, "include_answer", false));
        request.put("include_raw_content", booleanValue(source, "include_raw_content", false));
        optionalAllowedString(source, "time_range", TIME_RANGES).ifPresent(value -> request.put("time_range", value));
        putStringList(request, source, "include_domains");
        putStringList(request, source, "exclude_domains");
        return request;
    }

    private Map<String, Object> mapResponse(TavilyHttpResponse response, String requestQuery) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("TAVILY_SEARCH_FAILED",
                    "Tavily search returned HTTP " + response.statusCode() + errorDetail(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root == null || !root.isObject()) {
            throw new BusinessException("TAVILY_SEARCH_FAILED", "Tavily search returned an invalid JSON response");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", textOrDefault(root.get("query"), requestQuery));
        output.put("answer", value(root.get("answer")));
        output.put("results", mapResults(root.path("results")));
        output.put("responseTime", value(root.get("response_time")));
        output.put("requestId", value(root.get("request_id")));
        if (root.has("usage")) {
            output.put("usage", value(root.get("usage")));
        }
        return output;
    }

    private List<Map<String, Object>> mapResults(JsonNode resultsNode) {
        if (!resultsNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            if (!item.isObject()) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            copy(result, item, "title", "title");
            copy(result, item, "url", "url");
            copy(result, item, "content", "content");
            copy(result, item, "score", "score");
            copy(result, item, "rawContent", "raw_content");
            copy(result, item, "publishedDate", "published_date");
            results.add(result);
        }
        return List.copyOf(results);
    }

    private void copy(Map<String, Object> target, JsonNode source, String targetName, String sourceName) {
        if (source.has(sourceName) && !source.get(sourceName).isNull()) {
            target.put(targetName, value(source.get(sourceName)));
        }
    }

    private Object value(JsonNode node) {
        return node == null || node.isNull() ? null : objectMapper.convertValue(node, Object.class);
    }

    private String textOrDefault(JsonNode node, String fallback) {
        return node != null && node.isTextual() && StringUtils.hasText(node.asText()) ? node.asText() : fallback;
    }

    private String errorDetail(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String detail = textOrDefault(root.get("detail"), textOrDefault(root.get("message"), ""));
            if (StringUtils.hasText(detail)) {
                return ": " + detail.substring(0, Math.min(detail.length(), 240));
            }
        }
        catch (JsonProcessingException ignored) {
            // The status code is sufficient when the remote body is not JSON.
        }
        return "";
    }

    private String requiredString(Map<String, Object> source, String key) {
        String value = stringValue(source.get(key));
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("TAVILY_REQUEST_INVALID", "Tavily search query is required");
        }
        return value;
    }

    private String allowedString(Map<String, Object> source, String key, String fallback, Set<String> allowed) {
        String value = stringValue(source.get(key));
        String normalized = StringUtils.hasText(value) ? value : fallback;
        if (!allowed.contains(normalized)) {
            throw new BusinessException("TAVILY_REQUEST_INVALID", "Unsupported Tavily " + key + ": " + normalized);
        }
        return normalized;
    }

    private java.util.Optional<String> optionalAllowedString(Map<String, Object> source, String key,
            Set<String> allowed) {
        String value = stringValue(source.get(key));
        if (!StringUtils.hasText(value)) {
            return java.util.Optional.empty();
        }
        if (!allowed.contains(value)) {
            throw new BusinessException("TAVILY_REQUEST_INVALID", "Unsupported Tavily " + key + ": " + value);
        }
        return java.util.Optional.of(value);
    }

    private int boundedInt(Map<String, Object> source, String key, int fallback, int min, int max) {
        Object raw = source.get(key);
        int value;
        if (raw == null) {
            value = fallback;
        }
        else if (raw instanceof Number number) {
            value = number.intValue();
        }
        else {
            try {
                value = Integer.parseInt(String.valueOf(raw));
            }
            catch (NumberFormatException ex) {
                throw new BusinessException("TAVILY_REQUEST_INVALID", "Tavily " + key + " must be an integer");
            }
        }
        if (value < min || value > max) {
            throw new BusinessException("TAVILY_REQUEST_INVALID",
                    "Tavily " + key + " must be between " + min + " and " + max);
        }
        return value;
    }

    private boolean booleanValue(Map<String, Object> source, String key, boolean fallback) {
        Object raw = source.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(raw));
    }

    private void putStringList(Map<String, Object> target, Map<String, Object> source, String key) {
        Object raw = source.get(key);
        if (!(raw instanceof Iterable<?> values)) {
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (Object value : values) {
            String text = stringValue(value);
            if (StringUtils.hasText(text)) {
                normalized.add(text);
            }
        }
        if (!normalized.isEmpty()) {
            target.put(key, List.copyOf(normalized));
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static final class JavaNetTavilyHttpTransport implements TavilyHttpTransport {

        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public TavilyHttpResponse post(URI endpoint, String apiKey, String body, Duration timeout)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new TavilyHttpResponse(response.statusCode(), response.body());
        }
    }
}

@FunctionalInterface
interface TavilyHttpTransport {
    TavilyHttpResponse post(URI endpoint, String apiKey, String body, Duration timeout)
            throws IOException, InterruptedException;
}

record TavilyHttpResponse(int statusCode, String body) {
}
