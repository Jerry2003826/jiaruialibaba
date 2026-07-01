package com.example.agentdemo.app.apikey.dto;

import com.example.agentdemo.app.apikey.AppApiKeyStatus;

import java.time.Instant;
import java.util.List;

/**
 * API view of an API key. Never contains the plaintext secret.
 */
public record ApiKeyResponse(String keyId, String appId, String name, List<String> scopes,
        AppApiKeyStatus status, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
}
