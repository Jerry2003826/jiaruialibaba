package com.example.agentdemo.chat;

public record AiModelResult(String answer, boolean fallback, String errorMessage) {

    public static AiModelResult ok(String answer) {
        return new AiModelResult(answer, false, null);
    }

    public static AiModelResult fallback(String answer, String errorMessage) {
        return new AiModelResult(answer, true, errorMessage);
    }

}
