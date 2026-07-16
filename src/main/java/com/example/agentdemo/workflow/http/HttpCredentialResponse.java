package com.example.agentdemo.workflow.http;

import java.time.Instant;

public record HttpCredentialResponse(
        String credentialId,
        String name,
        String type,
        boolean configured,
        Instant createdAt,
        Instant updatedAt) {

    static HttpCredentialResponse from(HttpCredentialEntity entity) {
        return new HttpCredentialResponse(entity.getCredentialId(), entity.getName(), entity.getType(), true,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
