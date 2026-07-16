package com.example.agentdemo.workflow.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HttpCredentialRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 32) String type,
        @Size(max = 8192) String token,
        @Size(max = 256) String headerName,
        @Size(max = 8192) String value,
        @Size(max = 1024) String username,
        @Size(max = 8192) String password) {
}
