package com.example.agentdemo.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Declared workflow variables. Inputs drive the run form; outputs document the result shape. The
 * schema is metadata (not executed) so it evolves independently of the node/edge DSL.
 *
 * @param inputs  declared input variables
 * @param outputs declared output variables
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowVariableSchema(List<InputVariable> inputs, List<OutputVariable> outputs) {

    public WorkflowVariableSchema {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    /**
     * @param name        variable name (input key)
     * @param type        type hint: string/number/boolean/object/array
     * @param required    whether the run form requires a value
     * @param defaultValue optional default value
     * @param description human-readable description
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputVariable(String name, String type, boolean required, Object defaultValue, String description) {
    }

    /**
     * @param name        output name
     * @param path        path into the workflow output (e.g. {@code answer} or {@code result.text})
     * @param description human-readable description
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputVariable(String name, String path, String description) {
    }

}
