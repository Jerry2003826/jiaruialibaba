package com.example.agentdemo.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

final class WorkflowOutputSchemaValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private WorkflowOutputSchemaValidator() {
    }

    static Optional<String> validate(Object outputSchema, Object output) {
        if (!(outputSchema instanceof Map<?, ?> schemaMap) || schemaMap.isEmpty()) {
            return Optional.empty();
        }
        JsonNode schema = OBJECT_MAPPER.valueToTree(schemaMap);
        return Optional.ofNullable(validateValue("", schema, output, true));
    }

    private static String validateValue(String path, JsonNode schema, Object value, boolean root) {
        JsonNode typeNode = schema.path("type");
        if (value == null) {
            if (!typeNode.isMissingNode() && !allowsNull(typeNode)) {
                return typeMismatch(path, root, firstType(typeNode));
            }
            return validateEnum(path, schema, value, root);
        }
        if (!matchesAnyType(value, typeNode)) {
            return typeMismatch(path, root, firstType(typeNode));
        }
        String enumError = validateEnum(path, schema, value, root);
        if (enumError != null) {
            return enumError;
        }
        if (value instanceof Map<?, ?> objectValue) {
            return validateObject(path, schema, objectValue, root);
        }
        if (value instanceof Collection<?> collectionValue && schema.path("items").isObject()) {
            return validateArrayItems(path, schema.path("items"), collectionValue);
        }
        return null;
    }

    private static String validateObject(String path, JsonNode schema, Map<?, ?> value, boolean root) {
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode requiredField : required) {
                String fieldName = requiredField.asText("");
                if (!fieldName.isBlank() && !value.containsKey(fieldName)) {
                    return "Missing required workflow node output field: " + fieldPath(path, fieldName);
                }
            }
        }

        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            for (Map.Entry<?, ?> entry : value.entrySet()) {
                String fieldName = String.valueOf(entry.getKey());
                JsonNode fieldSchema = properties.path(fieldName);
                if (!fieldSchema.isMissingNode()) {
                    String error = validateValue(fieldPath(path, fieldName), fieldSchema, entry.getValue(), false);
                    if (error != null) {
                        return error;
                    }
                }
                else if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").asBoolean()) {
                    return "Unsupported workflow node output field: " + fieldPath(path, fieldName);
                }
            }
        }
        else if (schema.path("additionalProperties").isBoolean()
                && !schema.path("additionalProperties").asBoolean()
                && !value.isEmpty()) {
            return "Unsupported workflow node output field: " + fieldPath(path, String.valueOf(value.keySet().iterator().next()));
        }
        return null;
    }

    private static String validateArrayItems(String path, JsonNode itemSchema, Collection<?> values) {
        int index = 0;
        for (Object item : values) {
            String error = validateValue(path + "[" + index + "]", itemSchema, item, false);
            if (error != null) {
                return error;
            }
            index++;
        }
        return null;
    }

    private static String validateEnum(String path, JsonNode schema, Object value, boolean root) {
        JsonNode enumValues = schema.path("enum");
        if (!enumValues.isArray()) {
            return null;
        }
        JsonNode actualValue = OBJECT_MAPPER.valueToTree(value);
        for (JsonNode enumValue : enumValues) {
            if (enumValue.equals(actualValue)) {
                return null;
            }
        }
        return (root ? "Workflow node output" : "Workflow node output field " + path)
                + " must be one of " + enumValues;
    }

    private static String typeMismatch(String path, boolean root, String expectedType) {
        return (root ? "Workflow node output" : "Workflow node output field " + path)
                + " must be " + (expectedType == null ? "a supported JSON Schema type" : expectedType);
    }

    private static String fieldPath(String path, String fieldName) {
        return path == null || path.isBlank() ? fieldName : path + "." + fieldName;
    }

    private static boolean matchesAnyType(Object value, JsonNode typeNode) {
        if (typeNode.isMissingNode()) {
            return true;
        }
        if (typeNode.isTextual()) {
            return matchesType(value, typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                String expectedType = candidate.asText();
                if (!"null".equals(expectedType) && matchesType(value, expectedType)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesType(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Collection<?> || value.getClass().isArray();
            case "null" -> value == null;
            default -> true;
        };
    }

    private static String firstType(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return typeNode.asText();
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if (!"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        return null;
    }

    private static boolean allowsNull(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return "null".equals(typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if ("null".equals(candidate.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

}
