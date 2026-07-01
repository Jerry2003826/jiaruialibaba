package com.example.agentdemo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecretRedactor}: secret keys are masked, token-accounting keys are exempt,
 * and nested maps / JSON trees are redacted recursively.
 */
class SecretRedactorTest {

    @Test
    void detectsSensitiveKeys() {
        assertThat(SecretRedactor.isSensitiveKey("authorization")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("Authorization")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("api_key")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("apiKey")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("accessKeyId")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("privateKey")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("credentials")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("dashscope_key")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("dashscope-api-key")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("password")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("accessToken")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("mcpToken")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("secret")).isTrue();
        assertThat(SecretRedactor.isSensitiveKey("cookie")).isTrue();
    }

    @Test
    void exemptsTokenAccountingKeys() {
        assertThat(SecretRedactor.isSensitiveKey("promptTokens")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("completionTokens")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("totalTokens")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("tokenUsage")).isFalse();
    }

    @Test
    void allowsOrdinaryKeys() {
        assertThat(SecretRedactor.isSensitiveKey("message")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("city")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("keyId")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("key_id")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("documentKey")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("cacheKey")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey("partitionKey")).isFalse();
        assertThat(SecretRedactor.isSensitiveKey(null)).isFalse();
    }

    @Test
    void redactsNestedMetadataMap() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("apiKey", "sk-should-hide");
        nested.put("privateKey", "private-value");
        nested.put("keep", "ok");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authorization", "Bearer abc");
        metadata.put("credentials", "user:secret");
        metadata.put("nested", nested);
        metadata.put("promptTokens", 42);
        metadata.put("keyId", "ak_123");

        Map<String, Object> redacted = SecretRedactor.redactMetadata(metadata);

        assertThat(redacted.get("authorization")).isEqualTo(SecretRedactor.REDACTED);
        assertThat(redacted.get("credentials")).isEqualTo(SecretRedactor.REDACTED);
        assertThat(redacted.get("promptTokens")).isEqualTo(42);
        assertThat(redacted.get("keyId")).isEqualTo("ak_123");
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedNested = (Map<String, Object>) redacted.get("nested");
        assertThat(redactedNested.get("apiKey")).isEqualTo(SecretRedactor.REDACTED);
        assertThat(redactedNested.get("privateKey")).isEqualTo(SecretRedactor.REDACTED);
        assertThat(redactedNested.get("keep")).isEqualTo("ok");
    }

    @Test
    void redactsJsonTreeInPlace() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("accessKeyId", "ak-secret");
        root.put("Authorization", "Bearer top-secret");
        root.put("city", "Beijing");
        root.put("totalTokens", 55);
        root.putObject("inner").put("password", "p@ss").put("keyId", "ak_123").put("keep", "yes");
        root.putArray("items").addObject().put("credentials", "user:pass");

        SecretRedactor.redactJson(root);

        assertThat(root.path("accessKeyId").asText()).isEqualTo(SecretRedactor.REDACTED);
        assertThat(root.path("Authorization").asText()).isEqualTo(SecretRedactor.REDACTED);
        assertThat(root.path("city").asText()).isEqualTo("Beijing");
        assertThat(root.path("totalTokens").asInt()).isEqualTo(55);
        assertThat(root.path("inner").path("password").asText()).isEqualTo(SecretRedactor.REDACTED);
        assertThat(root.path("inner").path("keyId").asText()).isEqualTo("ak_123");
        assertThat(root.path("inner").path("keep").asText()).isEqualTo("yes");
        assertThat(root.path("items").get(0).path("credentials").asText()).isEqualTo(SecretRedactor.REDACTED);
    }

}
