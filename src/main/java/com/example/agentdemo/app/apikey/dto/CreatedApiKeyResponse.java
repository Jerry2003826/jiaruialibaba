package com.example.agentdemo.app.apikey.dto;

import java.util.List;

/**
 * Response returned only at creation time. {@code plaintextKey} is shown exactly once and is never
 * retrievable again.
 */
public record CreatedApiKeyResponse(String keyId, String appId, String name, List<String> scopes,
        String plaintextKey) {
}
