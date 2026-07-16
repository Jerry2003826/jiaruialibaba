package com.example.agentdemo.tool.tavily;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TavilyCredentialRequest(
        @NotBlank @Size(max = 512) String apiKey) {
}
