package com.example.agentdemo.chat.dto;

import java.time.Instant;

public record HealthResponse(String status, Instant currentTime, boolean modelConfigured, String model) {
}
