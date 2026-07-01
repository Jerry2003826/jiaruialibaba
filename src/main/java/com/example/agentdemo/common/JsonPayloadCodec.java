package com.example.agentdemo.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonPayloadCodec {

    private final ObjectMapper objectMapper;

    public JsonPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T read(String json, Class<T> type, String errorCode, String message) {
        try {
            return objectMapper.readValue(json, type);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException(errorCode, message, ex);
        }
    }

    public String write(Object value, String errorCode, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException(errorCode, message, ex);
        }
    }

    public JsonNode readTreeOrNull(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException ex) {
            return null;
        }
    }

    public <T> T readOrNull(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        }
        catch (JsonProcessingException ex) {
            return null;
        }
    }

}
