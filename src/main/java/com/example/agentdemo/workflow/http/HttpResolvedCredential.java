package com.example.agentdemo.workflow.http;

import java.util.Map;

public record HttpResolvedCredential(String credentialId, String type, Map<String, String> values) {

    public HttpResolvedCredential {
        values = Map.copyOf(values);
    }
}
