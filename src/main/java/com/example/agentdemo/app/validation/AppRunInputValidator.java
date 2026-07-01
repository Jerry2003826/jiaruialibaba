package com.example.agentdemo.app.validation;

import com.example.agentdemo.app.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AppRunInputValidator implements ConstraintValidator<ValidAppRunInput, Map<String, Object>> {

    private static final int MAX_TOP_LEVEL_FIELDS = 100;
    private static final int MAX_DEPTH = 20;

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public AppRunInputValidator(ObjectMapper objectMapper, AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @Override
    public boolean isValid(Map<String, Object> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.size() > MAX_TOP_LEVEL_FIELDS) {
            return violation(context, "must contain at most 100 top-level fields");
        }
        if (depthOf(value, 1) > MAX_DEPTH) {
            return violation(context, "must have nesting depth at most 20");
        }
        try {
            if (objectMapper.writeValueAsBytes(value).length > appProperties.getMaxRunInputBytes()) {
                return violation(context, "must serialize to at most " + appProperties.getMaxRunInputBytes()
                        + " bytes");
            }
        }
        catch (JsonProcessingException ex) {
            return violation(context, "must be serializable as JSON");
        }
        return true;
    }

    private int depthOf(Object value, int currentDepth) {
        if (value instanceof Map<?, ?> map) {
            int maxDepth = currentDepth;
            for (Object nestedValue : map.values()) {
                maxDepth = Math.max(maxDepth, depthOf(nestedValue, currentDepth + 1));
            }
            return maxDepth;
        }
        if (value instanceof List<?> list) {
            int maxDepth = currentDepth;
            for (Object nestedValue : list) {
                maxDepth = Math.max(maxDepth, depthOf(nestedValue, currentDepth + 1));
            }
            return maxDepth;
        }
        if (value instanceof Object[] array) {
            int maxDepth = currentDepth;
            for (Object nestedValue : array) {
                maxDepth = Math.max(maxDepth, depthOf(nestedValue, currentDepth + 1));
            }
            return maxDepth;
        }
        return currentDepth;
    }

    private boolean violation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
