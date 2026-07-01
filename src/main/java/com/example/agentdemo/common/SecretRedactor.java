package com.example.agentdemo.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for detecting and masking sensitive material (Authorization headers, API
 * keys, DashScope / DashVector keys, MCP tokens and any {@code key}/{@code token}/{@code secret}/
 * {@code password} field). Trace serialization, audit metadata and log scrubbing all delegate here
 * so a secret can never leak into the database, an API response, a log line or the frontend.
 *
 * <p>Token-accounting fields (e.g. {@code promptTokens}, {@code totalTokens}) contain the substring
 * "token" but are not secrets, so they are explicitly exempt.
 */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "api_key", "apikey", "authorization", "cookie", "password", "secret", "token");

    private static final Set<String> TOKEN_ACCOUNTING_KEYS = Set.of(
            "tokenusage",
            "prompttokens",
            "completiontokens",
            "totaltokens",
            "inputtokens",
            "outputtokens",
            "cachedtokens",
            "reasoningtokens",
            "audiotokens",
            "acceptedpredictiontokens",
            "rejectedpredictiontokens",
            "prompttokensdetails",
            "completiontokensdetails",
            "inputtokensdetails",
            "outputtokensdetails",
            "totaltokensdetails");

    private SecretRedactor() {
    }

    /**
     * Returns {@code true} when a map/JSON key names a secret and its value must be masked.
     *
     * @param key the field name to test (may be {@code null})
     * @return whether the value under {@code key} is sensitive
     */
    public static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase().replace("-", "_");
        if (isTokenAccountingKey(normalized)) {
            return false;
        }
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private static boolean isTokenAccountingKey(String normalized) {
        String compact = normalized.replace("_", "");
        return TOKEN_ACCOUNTING_KEYS.contains(compact);
    }

    /**
     * Returns a shallow copy of {@code metadata} with every sensitive value replaced by
     * {@link #REDACTED}. Nested maps are redacted recursively. Intended for small audit/context
     * maps, not large trace payloads (use trace-level sanitization for those).
     *
     * @param metadata the metadata to redact (may be {@code null})
     * @return a redacted copy, or an empty map when {@code metadata} is {@code null}
     */
    public static Map<String, Object> redactMetadata(Map<String, ?> metadata) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        if (metadata == null) {
            return redacted;
        }
        metadata.forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                redacted.put(key, REDACTED);
            }
            else if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, ?> nestedMap = (Map<String, ?>) nested;
                redacted.put(key, redactMetadata(nestedMap));
            }
            else {
                redacted.put(key, value);
            }
        });
        return redacted;
    }

    /**
     * Recursively masks sensitive values in a mutable Jackson {@link JsonNode} tree in place and
     * returns it. Object keys are matched with {@link #isSensitiveKey(String)}.
     *
     * @param node the node to redact (may be {@code null})
     * @return the same node instance, redacted
     */
    public static JsonNode redactJson(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.fieldNames().forEachRemaining(name -> {
                if (isSensitiveKey(name)) {
                    objectNode.put(name, REDACTED);
                }
                else {
                    redactJson(objectNode.get(name));
                }
            });
        }
        else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(SecretRedactor::redactJson);
        }
        return node;
    }

}
