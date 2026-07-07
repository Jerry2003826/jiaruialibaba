package com.example.agentdemo.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemaValidatorTest {

    private final ToolSchemaValidator validator = new ToolSchemaValidator(new ObjectMapper());

    @Test
    void reportsMissingRequiredFieldForGatewayValidation() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "text": {"type": "string"}
                  },
                  "required": ["text"]
                }
                """;

        assertThat(validator.validateForGateway(schema, Map.of()))
                .hasValue("Missing required MCP tool argument: text");
    }

    @Test
    void reportsTypeMismatchForGatewayValidation() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "count": {"type": "integer"}
                  },
                  "required": ["count"]
                }
                """;

        assertThat(validator.validateForGateway(schema, Map.of("count", "three")))
                .hasValue("MCP tool argument count must be integer");
    }

    @Test
    void reportsEnumMismatchForToolTestValidation() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mode": {"type": "string", "enum": ["read", "write"]}
                  },
                  "required": ["mode"]
                }
                """;

        assertThat(validator.validateForToolTest(schema, Map.of("mode", "delete")))
                .hasValue("Tool argument mode must be one of [\"read\",\"write\"]");
    }

    @Test
    void rejectsUnexpectedTopLevelPropertiesWhenAdditionalPropertiesIsFalse() {
        String schema = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "query": {"type": "string"}
                  }
                }
                """;

        assertThat(validator.validateForGateway(schema, Map.of("query", "status", "admin", true)))
                .hasValueSatisfying(message -> assertThat(message).contains("admin"));
    }

    @Test
    void rejectsNestedPropertiesAndStringAndNumericBounds() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "customer": {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["name", "age"],
                      "properties": {
                        "name": {"type": "string", "maxLength": 4},
                        "age": {"type": "integer", "minimum": 18, "maximum": 120}
                      }
                    }
                  },
                  "required": ["customer"]
                }
                """;

        assertThat(validator.validateForGateway(schema,
                Map.of("customer", Map.of("name", "Alice", "age", 17, "role", "admin"))))
                .hasValueSatisfying(message -> assertThat(message)
                        .containsAnyOf("customer", "name", "age", "role"));
    }

}
