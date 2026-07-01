package com.example.agentdemo.app.dto;

import com.example.agentdemo.app.AppStatus;

import java.time.Instant;

/**
 * API view of an app revision (metadata only; the snapshot is internal).
 */
public record AppRevisionResponse(String appId, int version, AppStatus status, Instant createdAt) {
}
