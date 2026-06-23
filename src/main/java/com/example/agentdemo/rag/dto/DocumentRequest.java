package com.example.agentdemo.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentRequest(
        @Size(max = 256) String title,
        @NotBlank @Size(max = 20000) String content) {
}
