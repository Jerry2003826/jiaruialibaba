package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WorkflowLockedSpecCodec {

    private WorkflowLockedSpecCodec() {
    }

    static JsonNode normalize(ObjectMapper objectMapper, JsonNode value) {
        return normalize(value, text -> {
            try {
                return objectMapper.readTree(text);
            }
            catch (JsonProcessingException ignored) {
                return null;
            }
        });
    }

    static String toJson(JsonPayloadCodec codec, JsonNode value) {
        JsonNode normalized = normalize(value, codec::readTreeOrNull);
        if (normalized == null) {
            return null;
        }
        return codec.write(normalized, "WORKFLOW_LOCKED_SPEC_SERIALIZATION_FAILED",
                "Failed to serialize workflow locked specification");
    }

    static JsonNode fromJson(JsonPayloadCodec codec, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalize(codec.readTreeOrNull(value), codec::readTreeOrNull);
    }

    static String contextText(ObjectMapper objectMapper, JsonNode value) {
        JsonNode normalized = normalize(objectMapper, value);
        if (normalized == null) {
            return "";
        }
        if (normalized.isTextual()) {
            return normalized.asText();
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        }
        catch (JsonProcessingException error) {
            throw new BusinessException("WORKFLOW_LOCKED_SPEC_SERIALIZATION_FAILED",
                    "Failed to serialize workflow locked specification", error);
        }
    }

    private static JsonNode normalize(JsonNode value, JsonParser parser) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        JsonNode candidate = value;
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            JsonNode parsed = parser.parse(text);
            candidate = parsed == null ? JsonNodeFactory.instance.textNode(text) : parsed;
        }
        return canonicalize(candidate);
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> result.set(name, canonicalize(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value.deepCopy();
    }

    @FunctionalInterface
    private interface JsonParser {
        JsonNode parse(String value);
    }
}
