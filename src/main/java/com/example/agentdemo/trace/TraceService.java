package com.example.agentdemo.trace;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.SecretRedactor;
import com.example.agentdemo.security.SecurityIdentity;
import com.example.agentdemo.trace.dto.RunPageResponse;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TraceService {

    private static final int MAX_TRACE_JSON_CHARS = 32768;
    private static final int MAX_TRACE_TEXT_CHARS = 2048;
    private static final int MAX_TRACE_ARRAY_ITEMS = 50;
    private static final int MAX_SANITIZE_DEPTH = 200;

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final ObjectMapper objectMapper;

    public TraceService(RunRepository runRepository, RunStepRepository runStepRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TraceRun startRun(RunType type, Object input) {
        RunEntity run = createRun(type, input);
        return toTraceRun(run);
    }

    private RunEntity createRun(RunType type, Object input) {
        RunEntity run = new RunEntity(newId(), type, RunStatus.RUNNING, toJson(input), Instant.now());
        return runRepository.save(run);
    }

    @Transactional
    public TraceStep startTraceStep(String runId, String nodeName, Object input) {
        RunStepEntity step = startStep(runId, nodeName, input);
        return toTraceStep(step);
    }

    private RunStepEntity startStep(String runId, String nodeName, Object input) {
        ensureRunExists(runId);
        RunStepEntity step = new RunStepEntity(newId(), runId, nodeName, toJson(input), StepStatus.RUNNING,
                Instant.now());
        return runStepRepository.save(step);
    }

    @Transactional
    public void completeStep(String stepId, Object output) {
        RunStepEntity step = findStep(stepId);
        step.setOutputJson(toJson(output));
        step.setStatus(StepStatus.SUCCEEDED);
        step.setEndedAt(Instant.now());
        runStepRepository.save(step);
    }

    @Transactional
    public void failStep(String stepId, Throwable error) {
        failStep(stepId, error, new ErrorPayload(error.getClass().getSimpleName(), error.getMessage()));
    }

    @Transactional
    public void failStep(String stepId, Throwable error, Object output) {
        RunStepEntity step = findStep(stepId);
        step.setErrorMessage(error.getMessage());
        step.setOutputJson(toJson(output));
        step.setStatus(StepStatus.FAILED);
        step.setEndedAt(Instant.now());
        runStepRepository.save(step);
    }

    @Transactional
    public void markRunSucceeded(String runId, Object output) {
        RunEntity run = findRunForMutation(runId);
        run.setOutput(toJson(output));
        run.setStatus(RunStatus.SUCCEEDED);
        run.setEndedAt(Instant.now());
        runRepository.save(run);
    }

    @Transactional
    public void markRunFailed(String runId, Throwable error) {
        RunEntity run = findRunForMutation(runId);
        run.setErrorMessage(error.getMessage());
        run.setOutput(toJson(new ErrorPayload(error.getClass().getSimpleName(), error.getMessage())));
        run.setStatus(RunStatus.FAILED);
        run.setEndedAt(Instant.now());
        runRepository.save(run);
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRuns() {
        return listRuns(null, null, 0, 20).content();
    }

    @Transactional(readOnly = true)
    public RunPageResponse listRuns(RunType type, RunStatus status, int page, int size) {
        validateRunQuery(page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<RunEntity> runPage = runRepository.findAll(
                RunSpecifications.filter(SecurityIdentity.currentOwnerId(), type, status), pageable);
        List<RunResponse> content = runPage.getContent().stream().map(this::toRunResponse).toList();
        return new RunPageResponse(content, runPage.getNumber(), runPage.getSize(), runPage.getTotalElements(),
                runPage.getTotalPages());
    }

    private void validateRunQuery(int page, int size) {
        if (page < 0) {
            throw new BusinessException("RUN_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("RUN_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(String runId) {
        return toRunResponse(findRun(runId));
    }

    @Transactional(readOnly = true)
    public Map<String, RunResponse> findRunsById(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return runRepository.findAllByOwnerIdAndRunIdIn(SecurityIdentity.currentOwnerId(), runIds)
                .stream()
                .map(this::toRunResponse)
                .collect(Collectors.toMap(RunResponse::runId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public List<RunStepResponse> listSteps(String runId) {
        ensureRunExists(runId);
        return runStepRepository.findByOwnerIdAndRunIdOrderByStartedAtAsc(SecurityIdentity.currentOwnerId(), runId)
                .stream()
                .map(this::toRunStepResponse)
                .toList();
    }

    public String toJson(Object value) {
        try {
            JsonNode sanitized = sanitize(objectMapper.valueToTree(value), 0);
            String json = objectMapper.writeValueAsString(sanitized);
            if (json.length() <= MAX_TRACE_JSON_CHARS) {
                return json;
            }
            return objectMapper.writeValueAsString(Map.of(
                    "payloadStored", false,
                    "truncated", true,
                    "inputBytes", json.getBytes(StandardCharsets.UTF_8).length,
                    "sha256", sha256(json)));
        }
        catch (JsonProcessingException ex) {
            return escapedSerializationError(ex.getOriginalMessage());
        }
        catch (RuntimeException | StackOverflowError ex) {
            // Tracing must never break the operation being traced. Degrade payloads that Jackson
            // rejects (e.g. an IllegalArgumentException from valueToTree) or that are nested deeply
            // enough to exhaust the stack while serializing (StackOverflowError, which unwinds cleanly
            // unlike OutOfMemoryError) to a safe marker instead of propagating.
            return escapedSerializationError(ex.getMessage());
        }
    }

    private JsonNode sanitize(JsonNode node, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return objectMapper.nullNode();
        }
        if (depth >= MAX_SANITIZE_DEPTH) {
            // Defence in depth against pathologically nested payloads (including JSON nested inside
            // JSON strings): stop recursing rather than risk a StackOverflowError.
            return objectMapper.getNodeFactory().textNode("[TRUNCATED_DEPTH]");
        }
        if (node.isObject()) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (SecretRedactor.isSensitiveKey(entry.getKey())) {
                    sanitized.put(entry.getKey(), SecretRedactor.REDACTED);
                }
                else {
                    sanitized.set(entry.getKey(), sanitize(entry.getValue(), depth + 1));
                }
            });
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = objectMapper.createArrayNode();
            int count = 0;
            for (JsonNode item : node) {
                if (count >= MAX_TRACE_ARRAY_ITEMS) {
                    sanitized.add(objectMapper.createObjectNode()
                            .put("truncatedItems", node.size() - MAX_TRACE_ARRAY_ITEMS));
                    break;
                }
                sanitized.add(sanitize(item, depth + 1));
                count++;
            }
            return sanitized;
        }
        if (node.isTextual()) {
            return sanitizeTextual(node.asText(), depth);
        }
        return node;
    }

    /**
     * Sanitizes a string value. A value that is itself serialized JSON (a JSON object or array
     * embedded in a string) would otherwise bypass key-based redaction, because the sanitizer sees a
     * single opaque text node and only checks its length. Such values are parsed, recursively
     * sanitized and re-embedded as a string so secrets inside the embedded JSON are redacted too;
     * truncation is applied only after redaction so a preview can never leak a secret. Non-JSON text
     * (or malformed JSON) is left as-is, subject to length truncation.
     */
    private JsonNode sanitizeTextual(String text, int depth) {
        JsonNode embedded = parseEmbeddedJsonContainer(text);
        if (embedded != null) {
            try {
                return truncateTextual(objectMapper.writeValueAsString(sanitize(embedded, depth + 1)));
            }
            catch (JsonProcessingException ignored) {
                // Fall back to treating the value as opaque text.
            }
        }
        return truncateTextual(text);
    }

    /**
     * Returns the parsed node when {@code text} is a JSON object or array, otherwise {@code null}. A
     * cheap shape pre-check avoids paying for a parse attempt on ordinary (non-JSON) strings.
     */
    private JsonNode parseEmbeddedJsonContainer(String text) {
        String trimmed = text.strip();
        boolean looksLikeContainer = trimmed.length() >= 2
                && ((trimmed.charAt(0) == '{' && trimmed.charAt(trimmed.length() - 1) == '}')
                        || (trimmed.charAt(0) == '[' && trimmed.charAt(trimmed.length() - 1) == ']'));
        if (!looksLikeContainer) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(trimmed);
            return parsed.isContainerNode() ? parsed : null;
        }
        catch (JsonProcessingException ex) {
            return null;
        }
    }

    private JsonNode truncateTextual(String text) {
        if (text.length() > MAX_TRACE_TEXT_CHARS) {
            return objectMapper.createObjectNode()
                    .put("truncated", true)
                    .put("length", text.length())
                    .put("preview", text.substring(0, MAX_TRACE_TEXT_CHARS));
        }
        return objectMapper.getNodeFactory().textNode(text);
    }

    private String escapedSerializationError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("serializationError",
                    message == null ? "unknown" : message));
        }
        catch (JsonProcessingException ignored) {
            return "{\"serializationError\":\"unknown\"}";
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void ensureRunExists(String runId) {
        if (!runRepository.existsByRunIdAndOwnerId(runId, SecurityIdentity.currentOwnerId())) {
            throw new BusinessException("RUN_NOT_FOUND", "Run not found: " + runId);
        }
    }

    private RunEntity findRun(String runId) {
        return runRepository.findByRunIdAndOwnerId(runId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("RUN_NOT_FOUND", "Run not found: " + runId));
    }

    private RunEntity findRunForMutation(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException("RUN_NOT_FOUND", "Run not found: " + runId));
    }

    private RunStepEntity findStep(String stepId) {
        return runStepRepository.findById(stepId)
                .orElseThrow(() -> new BusinessException("STEP_NOT_FOUND", "Step not found: " + stepId));
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private TraceRun toTraceRun(RunEntity entity) {
        return new TraceRun(entity.getRunId(), entity.getStartedAt());
    }

    private TraceStep toTraceStep(RunStepEntity entity) {
        return new TraceStep(entity.getStepId(), entity.getRunId(), entity.getNodeName());
    }

    private RunResponse toRunResponse(RunEntity entity) {
        return new RunResponse(entity.getRunId(), entity.getType(), entity.getStatus(), entity.getInput(),
                entity.getOutput(), entity.getErrorMessage(), entity.getStartedAt(), entity.getEndedAt());
    }

    private RunStepResponse toRunStepResponse(RunStepEntity entity) {
        return new RunStepResponse(entity.getStepId(), entity.getRunId(), entity.getNodeName(),
                entity.getInputJson(), entity.getOutputJson(), entity.getErrorMessage(), entity.getStatus(),
                entity.getStartedAt(), entity.getEndedAt());
    }

    private record ErrorPayload(String type, String message) {
    }

}
