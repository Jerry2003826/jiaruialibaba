package com.example.agentdemo.audit.dto;

import com.example.agentdemo.audit.AuditActorType;

import java.time.Instant;

/**
 * API view of a single audit record.
 */
public record AuditLogResponse(Long id, AuditActorType actorType, String actorId, String action,
        String resourceType, String resourceId, String ip, String userAgent, String requestId,
        boolean success, String errorCode, Instant createdAt) {
}
