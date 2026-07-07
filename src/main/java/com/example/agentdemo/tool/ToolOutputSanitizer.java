package com.example.agentdemo.tool;

import com.example.agentdemo.common.SecretRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ToolOutputSanitizer {

    private static final int DEFAULT_MAX_OUTPUT_CHARS = 16_384;
    private static final String TRUNCATED_SUFFIX = "[TRUNCATED]";

    private final ObjectMapper objectMapper;
    private final int maxOutputChars;

    public ToolOutputSanitizer(ObjectMapper objectMapper,
            @Value("${demo.tools.max-output-chars:" + DEFAULT_MAX_OUTPUT_CHARS + "}") int maxOutputChars) {
        this.objectMapper = objectMapper;
        this.maxOutputChars = Math.max(1, maxOutputChars);
    }

    Object sanitize(Object output) {
        if (output == null) {
            return null;
        }
        if (output instanceof String text) {
            return sanitizeString(text);
        }
        JsonNode redacted = SecretRedactor.redactJson(objectMapper.valueToTree(output));
        String serialized = serialize(redacted);
        if (serialized.length() > maxOutputChars) {
            return truncate(serialized);
        }
        return objectMapper.convertValue(redacted, Object.class);
    }

    private String sanitizeString(String text) {
        try {
            JsonNode redacted = SecretRedactor.redactJson(objectMapper.readTree(text));
            return truncate(serialize(redacted));
        }
        catch (JsonProcessingException ex) {
            return truncate(text);
        }
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(node);
        }
    }

    private String truncate(String text) {
        if (text.length() <= maxOutputChars) {
            return text;
        }
        if (maxOutputChars <= TRUNCATED_SUFFIX.length()) {
            return text.substring(0, maxOutputChars);
        }
        int prefixLength = maxOutputChars - TRUNCATED_SUFFIX.length();
        return text.substring(0, prefixLength) + TRUNCATED_SUFFIX;
    }

}
