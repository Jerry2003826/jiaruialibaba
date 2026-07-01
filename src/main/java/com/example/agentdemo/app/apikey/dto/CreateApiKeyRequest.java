package com.example.agentdemo.app.apikey.dto;

import jakarta.validation.constraints.Size;

/**
 * Request to create a runtime API key for an app.
 *
 * @param name optional human-readable label
 */
public record CreateApiKeyRequest(@Size(max = 128) String name) {
}
