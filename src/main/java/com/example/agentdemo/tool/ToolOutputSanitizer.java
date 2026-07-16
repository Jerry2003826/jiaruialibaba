package com.example.agentdemo.tool;

import com.example.agentdemo.common.SecretRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

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
            return truncateStructured(redacted);
        }
        return objectMapper.convertValue(redacted, Object.class);
    }

    private Object truncateStructured(JsonNode redacted) {
        JsonNode compacted = redacted.deepCopy();
        String serialized = serialize(compacted);
        while (serialized.length() > maxOutputChars) {
            int excess = serialized.length() - maxOutputChars;
            TextLocation text = longestText(compacted, null);
            if (text != null && shrink(text, excess)) {
                serialized = serialize(compacted);
                continue;
            }
            ArrayNode array = largestArray(compacted);
            if (array != null && !array.isEmpty()) {
                int targetSize = Math.max(0, array.size() / 2);
                while (array.size() > targetSize) {
                    array.remove(array.size() - 1);
                }
                serialized = serialize(compacted);
                continue;
            }
            ObjectNode object = largestObject(compacted);
            if (object != null && !object.isEmpty()) {
                int targetSize = Math.max(0, object.size() / 2);
                java.util.List<String> fields = new java.util.ArrayList<>();
                object.fieldNames().forEachRemaining(fields::add);
                for (int index = fields.size() - 1; object.size() > targetSize && index >= 0; index--) {
                    object.remove(fields.get(index));
                }
                serialized = serialize(compacted);
                continue;
            }
            return truncate(serialized);
        }
        return objectMapper.convertValue(compacted, Object.class);
    }

    private boolean shrink(TextLocation location, int excess) {
        String value = location.value();
        if (value.length() <= TRUNCATED_SUFFIX.length()) {
            return false;
        }
        int targetLength = Math.max(TRUNCATED_SUFFIX.length(), value.length() - excess);
        if (targetLength >= value.length()) {
            targetLength = Math.max(TRUNCATED_SUFFIX.length(), value.length() / 2);
        }
        int prefixLength = Math.max(0, targetLength - TRUNCATED_SUFFIX.length());
        String replacement = value.substring(0, Math.min(prefixLength, value.length())) + TRUNCATED_SUFFIX;
        if (location.parent() instanceof ObjectNode object) {
            object.set(location.fieldName(), TextNode.valueOf(replacement));
        }
        else if (location.parent() instanceof ArrayNode array) {
            array.set(location.index(), TextNode.valueOf(replacement));
        }
        else {
            return false;
        }
        return true;
    }

    private TextLocation longestText(JsonNode node, TextLocation best) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if (child.isTextual()) {
                    best = longer(best, new TextLocation(object, field.getKey(), -1, child.textValue()));
                }
                else {
                    best = longestText(child, best);
                }
            }
        }
        else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isTextual()) {
                    best = longer(best, new TextLocation(array, null, index, child.textValue()));
                }
                else {
                    best = longestText(child, best);
                }
            }
        }
        return best;
    }

    private TextLocation longer(TextLocation current, TextLocation candidate) {
        if (candidate.value().length() <= TRUNCATED_SUFFIX.length()) {
            return current;
        }
        return current == null || candidate.value().length() > current.value().length() ? candidate : current;
    }

    private ArrayNode largestArray(JsonNode node) {
        ArrayNode largest = node instanceof ArrayNode array ? array : null;
        for (JsonNode child : node) {
            ArrayNode candidate = largestArray(child);
            if (candidate != null && (largest == null || candidate.size() > largest.size())) {
                largest = candidate;
            }
        }
        return largest;
    }

    private ObjectNode largestObject(JsonNode node) {
        ObjectNode largest = node instanceof ObjectNode object ? object : null;
        for (JsonNode child : node) {
            ObjectNode candidate = largestObject(child);
            if (candidate != null && (largest == null || candidate.size() > largest.size())) {
                largest = candidate;
            }
        }
        return largest;
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

    private record TextLocation(JsonNode parent, String fieldName, int index, String value) {
    }

}
