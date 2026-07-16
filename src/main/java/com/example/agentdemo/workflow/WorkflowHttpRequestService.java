package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.SecretRedactor;
import com.example.agentdemo.workflow.http.HttpCredentialService;
import com.example.agentdemo.workflow.http.HttpResolvedCredential;
import com.example.agentdemo.workflow.http.HttpTargetPolicy;
import com.example.agentdemo.workflow.http.WorkflowHttpProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WorkflowHttpRequestService {

    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> REDIRECT_STATUSES = Set.of("301", "302", "303", "307", "308");
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "proxy-authorization");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}");
    private static final Pattern CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset=([^;]+)");

    private final WorkflowVariableResolver variableResolver;
    private final HttpCredentialService credentialService;
    private final HttpTargetPolicy targetPolicy;
    private final WorkflowHttpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public WorkflowHttpRequestService(WorkflowVariableResolver variableResolver,
            HttpCredentialService credentialService, HttpTargetPolicy targetPolicy,
            WorkflowHttpProperties properties, ObjectMapper objectMapper) {
        this(variableResolver, credentialService, targetPolicy, properties, objectMapper,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
    }

    WorkflowHttpRequestService(WorkflowVariableResolver variableResolver,
            HttpCredentialService credentialService, HttpTargetPolicy targetPolicy,
            WorkflowHttpProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.variableResolver = variableResolver;
        this.credentialService = credentialService;
        this.targetPolicy = targetPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    Map<String, Object> execute(WorkflowNode node, WorkflowExecutionState state) {
        long startedAt = System.nanoTime();
        PreparedRequest prepared = prepare(node, state);
        HttpResponse<InputStream> response = sendFollowingRedirects(prepared);
        try (InputStream bodyStream = response.body()) {
            byte[] responseBytes = readLimited(bodyStream, properties.effectiveMaxResponseBytes());
            String body = new String(responseBytes, responseCharset(response.headers().firstValue("Content-Type")
                    .orElse("")));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("statusCode", response.statusCode());
            output.put("headers", sanitizedHeaders(response.headers().map()));
            output.put("body", body);
            output.put("json", parseJson(body));
            output.put("durationMs", Math.max(0, (System.nanoTime() - startedAt) / 1_000_000));
            output.put("succeeded", response.statusCode() >= 200 && response.statusCode() < 400);
            return output;
        }
        catch (IOException ex) {
            throw transportError("HTTP response could not be read", ex);
        }
    }

    private PreparedRequest prepare(WorkflowNode node, WorkflowExecutionState state) {
        String method = configString(node.config(), "method", "GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw invalid("Unsupported HTTP method: " + method);
        }
        String renderedUrl = variableResolver.renderString(configString(node.config(), "url", ""), state);
        if (!StringUtils.hasText(renderedUrl)) {
            throw invalid("HTTP request URL is required");
        }
        URI uri;
        try {
            uri = URI.create(renderedUrl.trim());
        }
        catch (IllegalArgumentException ex) {
            throw invalid("HTTP request URL is invalid");
        }
        List<NameValue> params = renderRows(node.config().get("params"), state, "params");
        rejectSensitiveQuery(uri.getRawQuery(), params);
        uri = appendQuery(uri, params);
        targetPolicy.validate(uri);

        Map<String, List<String>> headers = toHeaders(renderRows(node.config().get("headers"), state, "headers"));
        Body body = prepareBody(method, node.config().get("body"), state);
        if (StringUtils.hasText(body.contentType()) && !containsHeader(headers, "Content-Type")) {
            headers.put("Content-Type", List.of(body.contentType()));
        }
        applyCredential(node.config().get("authorization"), state, uri, headers);
        long timeoutMs = configLong(node.config(), "timeoutMs", 30_000L, 1L, 300_000L);
        return new PreparedRequest(method, uri, headers, body.bytes(), timeoutMs);
    }

    private HttpResponse<InputStream> sendFollowingRedirects(PreparedRequest initial) {
        PreparedRequest current = initial;
        int redirects = 0;
        while (true) {
            HttpResponse<InputStream> response = send(current);
            if (!REDIRECT_STATUSES.contains(String.valueOf(response.statusCode()))) {
                return response;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (!StringUtils.hasText(location)) {
                return response;
            }
            closeQuietly(response.body());
            if (redirects >= properties.effectiveMaxRedirects()) {
                throw new BusinessException("WORKFLOW_HTTP_REDIRECT_LIMIT", "HTTP redirect limit exceeded");
            }
            URI next;
            try {
                next = current.uri().resolve(location);
            }
            catch (IllegalArgumentException ex) {
                throw invalid("HTTP redirect target is invalid");
            }
            targetPolicy.validate(next);
            Map<String, List<String>> nextHeaders = copyHeaders(current.headers());
            if (!sameOrigin(current.uri(), next)) {
                removeHeader(nextHeaders, "Authorization");
                removeHeader(nextHeaders, "Proxy-Authorization");
            }
            String nextMethod = redirectedMethod(current.method(), response.statusCode());
            byte[] nextBody = nextMethod.equals(current.method()) ? current.body() : new byte[0];
            if (nextBody.length == 0) {
                removeHeader(nextHeaders, "Content-Type");
            }
            current = new PreparedRequest(nextMethod, next, nextHeaders, nextBody, current.timeoutMs());
            redirects++;
        }
    }

    private HttpResponse<InputStream> send(PreparedRequest prepared) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(prepared.uri())
                .timeout(Duration.ofMillis(prepared.timeoutMs()));
        prepared.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        HttpRequest.BodyPublisher publisher = prepared.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(prepared.body());
        builder.method(prepared.method(), publisher);
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw transportError("HTTP request was interrupted", ex);
        }
        catch (IOException | IllegalArgumentException ex) {
            throw transportError("HTTP request could not be completed", ex);
        }
    }

    private Body prepareBody(String method, Object configured, WorkflowExecutionState state) {
        Map<?, ?> bodyConfig = configured instanceof Map<?, ?> map ? map : Map.of("type", "none");
        String type = configString(bodyConfig, "type", "none").toLowerCase(Locale.ROOT);
        Object value = bodyConfig.get("value");
        if (("GET".equals(method) || "HEAD".equals(method)) && !"none".equals(type)) {
            throw invalid(method + " requests cannot contain a body");
        }
        try {
            return switch (type) {
                case "none" -> new Body(new byte[0], null);
                case "json" -> new Body(objectMapper.writeValueAsBytes(variableResolver.renderDeep(value, state)),
                        "application/json; charset=UTF-8");
                case "raw" -> new Body(variableResolver.renderString(value == null ? "" : String.valueOf(value), state)
                        .getBytes(StandardCharsets.UTF_8), configString(bodyConfig, "contentType", "text/plain; charset=UTF-8"));
                case "x-www-form-urlencoded" -> new Body(formUrlEncoded(renderRows(value, state, "body.value"))
                        .getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded; charset=UTF-8");
                case "form-data" -> multipartText(renderRows(value, state, "body.value"));
                default -> throw invalid("Unsupported HTTP body type: " + type);
            };
        }
        catch (JsonProcessingException ex) {
            throw invalid("HTTP JSON body could not be serialized");
        }
    }

    private Body multipartText(List<NameValue> fields) {
        String boundary = "workflow-" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder body = new StringBuilder();
        for (NameValue field : fields) {
            body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"")
                    .append(escapeQuoted(field.name())).append("\"\r\n\r\n")
                    .append(field.value()).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        return new Body(body.toString().getBytes(StandardCharsets.UTF_8),
                "multipart/form-data; boundary=" + boundary);
    }

    private void applyCredential(Object configured, WorkflowExecutionState state, URI uri,
            Map<String, List<String>> headers) {
        Map<?, ?> authorization = configured instanceof Map<?, ?> map ? map : Map.of("type", "none");
        String type = configString(authorization, "type", "none").toLowerCase(Locale.ROOT);
        if ("none".equals(type)) {
            return;
        }
        if (!"credential".equals(type)) {
            throw invalid("HTTP authorization must use a managed credential");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException("WORKFLOW_HTTP_HTTPS_REQUIRED",
                    "HTTP requests with credentials must use HTTPS");
        }
        String credentialId = configString(authorization, "credentialId", null);
        HttpResolvedCredential credential = credentialService.resolveForOwner(credentialId, state.ownerId());
        switch (credential.type()) {
            case "bearer" -> putCredentialHeader(headers, "Authorization",
                    "Bearer " + credential.values().get("token"));
            case "api_key_header" -> putCredentialHeader(headers, credential.values().get("headerName"),
                    credential.values().get("value"));
            case "basic" -> {
                String raw = credential.values().get("username") + ":" + credential.values().get("password");
                putCredentialHeader(headers, "Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            default -> throw invalid("Managed HTTP credential type is unsupported");
        }
    }

    private List<NameValue> renderRows(Object configured, WorkflowExecutionState state, String field) {
        if (configured == null) {
            return List.of();
        }
        if (!(configured instanceof Iterable<?> rows)) {
            throw invalid("HTTP " + field + " must be an array");
        }
        List<NameValue> rendered = new ArrayList<>();
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> row)) {
                throw invalid("HTTP " + field + " items must be objects");
            }
            if (Boolean.FALSE.equals(row.get("enabled"))) {
                continue;
            }
            String key = configString(row, "key", null);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object rawValue = row.containsKey("value") ? row.get("value") : "";
            Object value = variableResolver.renderDeep(rawValue, state);
            rendered.add(new NameValue(key.trim(), value == null ? "" : String.valueOf(value)));
        }
        return List.copyOf(rendered);
    }

    private Map<String, List<String>> toHeaders(List<NameValue> rows) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (NameValue row : rows) {
            if (!HEADER_NAME.matcher(row.name()).matches()) {
                throw invalid("HTTP header name is invalid");
            }
            String normalized = row.name().toLowerCase(Locale.ROOT);
            if (RESTRICTED_HEADERS.contains(normalized) || SecretRedactor.isSensitiveKey(row.name())) {
                throw new BusinessException("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED",
                        "Sensitive and restricted headers must use the HTTP credential center");
            }
            headers.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row.value());
        }
        return headers;
    }

    private void rejectSensitiveQuery(String rawQuery, List<NameValue> params) {
        if (StringUtils.hasText(rawQuery)) {
            for (String pair : rawQuery.split("&")) {
                String key = pair.split("=", 2)[0];
                if (SecretRedactor.isSensitiveKey(key)) {
                    throw new BusinessException("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED",
                            "Sensitive URL query values are not allowed in workflow JSON");
                }
            }
        }
        if (params.stream().anyMatch(param -> SecretRedactor.isSensitiveKey(param.name()))) {
            throw new BusinessException("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED",
                    "Sensitive query parameters are not allowed in workflow JSON");
        }
    }

    private URI appendQuery(URI uri, List<NameValue> params) {
        if (params.isEmpty()) {
            return uri;
        }
        String encoded = formUrlEncoded(params);
        String query = StringUtils.hasText(uri.getRawQuery()) ? uri.getRawQuery() + "&" + encoded : encoded;
        try {
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), query, uri.getRawFragment());
        }
        catch (Exception ex) {
            throw invalid("HTTP query parameters are invalid");
        }
    }

    private String formUrlEncoded(List<NameValue> values) {
        return values.stream()
                .map(value -> encode(value.name()) + "=" + encode(value.value()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new BusinessException("WORKFLOW_HTTP_RESPONSE_TOO_LARGE",
                        "HTTP response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Object parseJson(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return objectMapper.readValue(body, Object.class);
        }
        catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Map<String, Object> sanitizedHeaders(Map<String, List<String>> headers) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        headers.forEach((key, value) -> flattened.put(key, List.copyOf(value)));
        return SecretRedactor.redactMetadata(flattened);
    }

    private Charset responseCharset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1).replace("\"", "").trim());
            }
            catch (Exception ignored) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String redirectedMethod(String method, int status) {
        if (status == 303 || (status == 301 || status == 302) && "POST".equals(method)) {
            return "GET";
        }
        return method;
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
        return copy;
    }

    private void putCredentialHeader(Map<String, List<String>> headers, String name, String value) {
        if (!StringUtils.hasText(name) || !HEADER_NAME.matcher(name).matches() || value == null) {
            throw invalid("Managed HTTP credential is invalid");
        }
        removeHeader(headers, name);
        headers.put(name, List.of(value));
    }

    private void removeHeader(Map<String, List<String>> headers, String name) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
    }

    private boolean containsHeader(Map<String, List<String>> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        }
        catch (IOException ignored) {
        }
    }

    private String configString(Map<?, ?> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? defaultValue : String.valueOf(value);
    }

    private long configLong(Map<?, ?> config, String key, long defaultValue, long min, long max) {
        Object value = config.get(key);
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            return Math.max(min, Math.min(parsed, max));
        }
        catch (Exception ex) {
            return defaultValue;
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("WORKFLOW_HTTP_INVALID", message);
    }

    private BusinessException transportError(String message, Exception cause) {
        return new BusinessException("WORKFLOW_HTTP_TRANSPORT_ERROR", message, cause);
    }

    private record PreparedRequest(String method, URI uri, Map<String, List<String>> headers, byte[] body,
            long timeoutMs) {

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof PreparedRequest request
                    && timeoutMs == request.timeoutMs
                    && Objects.equals(method, request.method)
                    && Objects.equals(uri, request.uri)
                    && Objects.equals(headers, request.headers)
                    && Arrays.equals(body, request.body);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(method, uri, headers, timeoutMs) + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "PreparedRequest[method=%s, uri=%s, headers=%s, bodyLength=%d, timeoutMs=%d]"
                    .formatted(method, uri, headers.keySet(), body.length, timeoutMs);
        }
    }

    private record NameValue(String name, String value) {
    }

    private record Body(byte[] bytes, String contentType) {

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Body body
                    && Arrays.equals(bytes, body.bytes)
                    && Objects.equals(contentType, body.contentType);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(bytes) + Objects.hashCode(contentType);
        }

        @Override
        public String toString() {
            return "Body[bytesLength=%d, contentType=%s]".formatted(bytes.length, contentType);
        }
    }
}
