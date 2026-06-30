package com.example.agentdemo.chat;

public record AiModelResult(String answer, boolean fallback, String errorMessage, TokenUsage tokenUsage) {

    public static AiModelResult ok(String answer) {
        return ok(answer, null);
    }

    public static AiModelResult ok(String answer, TokenUsage tokenUsage) {
        return new AiModelResult(answer, false, null, tokenUsage);
    }

    public static AiModelResult fallback(String answer, String errorMessage) {
        return new AiModelResult(answer, true, errorMessage, null);
    }

}
