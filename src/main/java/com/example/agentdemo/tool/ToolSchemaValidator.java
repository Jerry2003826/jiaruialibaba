package com.example.agentdemo.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ToolSchemaValidator {

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;

    public ToolSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    public Optional<String> validateForGateway(String inputSchema, Map<String, Object> arguments) {
        return validate(inputSchema, arguments, ValidationMessages.gateway());
    }

    public Optional<String> validateForToolTest(String inputSchema, Map<String, Object> arguments) {
        return validate(inputSchema, arguments, ValidationMessages.toolTest());
    }

    private Optional<String> validate(String inputSchema, Map<String, Object> arguments, ValidationMessages messages) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Optional.empty();
        }
        JsonNode schema;
        try {
            schema = objectMapper.readTree(inputSchema);
        }
        catch (JsonProcessingException ex) {
            return Optional.of(messages.invalidSchema(ex.getOriginalMessage()));
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String requiredError = validateRequiredArguments(schema, safeArguments, messages);
        if (requiredError != null) {
            return Optional.of(requiredError);
        }
        String shallowError = validateArgumentTypes(schema, safeArguments, messages);
        if (shallowError != null) {
            return Optional.of(shallowError);
        }
        return Optional.ofNullable(validateWithJsonSchema(schema, safeArguments, messages));
    }

    private String validateWithJsonSchema(JsonNode schema, Map<String, Object> arguments, ValidationMessages messages) {
        JsonSchema jsonSchema = schemaFactory.getSchema(schema);
        Set<ValidationMessage> errors = jsonSchema.validate(objectMapper.valueToTree(arguments));
        return errors.stream()
                .min(Comparator.comparing(ValidationMessage::getMessage))
                .map(error -> messages.schemaViolation(fieldName(error), error.getMessage()))
                .orElse(null);
    }

    private String fieldName(ValidationMessage error) {
        String path = error.getInstanceLocation() == null ? null : error.getInstanceLocation().toString();
        String property = error.getProperty();
        if ((path == null || path.equals("$") || path.isBlank()) && property != null && !property.isBlank()) {
            return property;
        }
        if (path == null || path.equals("$")) {
            return "input";
        }
        String normalized = path.startsWith("$.") ? path.substring(2) : path.replaceFirst("^/", "");
        if (normalized.isBlank()) {
            return "input";
        }
        return normalized;
    }

    private String validateRequiredArguments(JsonNode schema, Map<String, Object> arguments,
            ValidationMessages messages) {
        JsonNode required = schema.path("required");
        if (!required.isArray()) {
            return null;
        }
        for (JsonNode requiredField : required) {
            String fieldName = requiredField.asText("");
            if (!fieldName.isBlank() && !arguments.containsKey(fieldName)) {
                return messages.missingRequired(fieldName);
            }
        }
        return null;
    }

    private String validateArgumentTypes(JsonNode schema, Map<String, Object> arguments, ValidationMessages messages) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return null;
        }
        for (Map.Entry<String, Object> argument : arguments.entrySet()) {
            JsonNode property = properties.path(argument.getKey());
            if (property.isMissingNode()) {
                continue;
            }
            String validationError = validateValue(argument.getKey(), property, argument.getValue(), messages);
            if (validationError != null) {
                return validationError;
            }
        }
        return null;
    }

    private String validateValue(String argumentName, JsonNode schema, Object value, ValidationMessages messages) {
        String compositionError = validateCompositions(argumentName, schema, value, messages);
        if (compositionError != null) {
            return compositionError;
        }
        JsonNode typeNode = schema.path("type");
        if (value == null) {
            if (!typeNode.isMissingNode() && !allowsNull(typeNode)) {
                return messages.typeMismatch(argumentName, firstType(typeNode));
            }
            return validateEnum(argumentName, schema, value, messages);
        }
        if (!matchesAnyType(value, typeNode)) {
            return messages.typeMismatch(argumentName, firstType(typeNode));
        }
        return validateEnum(argumentName, schema, value, messages);
    }

    private String validateCompositions(String argumentName, JsonNode schema, Object value,
            ValidationMessages messages) {
        JsonNode oneOf = schema.path("oneOf");
        if (oneOf.isArray()) {
            int matches = matchingSchemaCount(argumentName, oneOf, value, messages);
            if (matches != 1) {
                return messages.oneOfMismatch(argumentName);
            }
        }
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray() && matchingSchemaCount(argumentName, anyOf, value, messages) == 0) {
            return messages.anyOfMismatch(argumentName);
        }
        return null;
    }

    private int matchingSchemaCount(String argumentName, JsonNode candidateSchemas, Object value,
            ValidationMessages messages) {
        int matches = 0;
        for (JsonNode candidateSchema : candidateSchemas) {
            if (validateValue(argumentName, candidateSchema, value, messages) == null) {
                matches++;
            }
        }
        return matches;
    }

    private String validateEnum(String argumentName, JsonNode schema, Object value, ValidationMessages messages) {
        JsonNode enumValues = schema.path("enum");
        if (!enumValues.isArray()) {
            return null;
        }
        JsonNode actualValue = objectMapper.valueToTree(value);
        for (JsonNode enumValue : enumValues) {
            if (enumValue.equals(actualValue)) {
                return null;
            }
        }
        return messages.enumMismatch(argumentName, enumValues);
    }

    private String firstType(JsonNode typeNode) {
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

    private boolean allowsNull(JsonNode typeNode) {
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

    private boolean matchesType(Object value, String expectedType) {
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

    private boolean matchesAnyType(Object value, JsonNode typeNode) {
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

    private record ValidationMessages(String invalidSchemaPrefix, String missingRequiredPrefix,
            String argumentPrefix) {

        private static ValidationMessages gateway() {
            return new ValidationMessages("Invalid MCP tool input schema: ", "Missing required MCP tool argument: ",
                    "MCP tool argument ");
        }

        private static ValidationMessages toolTest() {
            return new ValidationMessages("Invalid tool input schema: ", "Missing required tool argument: ",
                    "Tool argument ");
        }

        private String invalidSchema(String detail) {
            return invalidSchemaPrefix + detail;
        }

        private String missingRequired(String fieldName) {
            return missingRequiredPrefix + fieldName;
        }

        private String typeMismatch(String fieldName, String expectedType) {
            return argumentPrefix + fieldName + " must be "
                    + (expectedType == null ? "a supported JSON Schema type" : expectedType);
        }

        private String oneOfMismatch(String fieldName) {
            return argumentPrefix + fieldName + " must match exactly one oneOf schema";
        }

        private String anyOfMismatch(String fieldName) {
            return argumentPrefix + fieldName + " must match anyOf schema";
        }

        private String enumMismatch(String fieldName, JsonNode enumValues) {
            return argumentPrefix + fieldName + " must be one of " + enumValues;
        }

        private String schemaViolation(String fieldName, String detail) {
            return argumentPrefix + fieldName + " violates JSON Schema: " + detail;
        }

    }

}
