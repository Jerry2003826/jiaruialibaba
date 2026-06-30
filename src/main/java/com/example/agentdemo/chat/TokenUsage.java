package com.example.agentdemo.chat;

public record TokenUsage(String provider, String model, Integer promptTokens, Integer completionTokens,
        Integer totalTokens, Object nativeUsage) {
}
