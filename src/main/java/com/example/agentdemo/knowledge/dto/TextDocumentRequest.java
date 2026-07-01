package com.example.agentdemo.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to ingest a text document into a knowledge base.
 *
 * @param title   optional title (defaults to a generated one)
 * @param content the document text (required)
 */
public record TextDocumentRequest(
        @Size(max = 256) String title,
        @NotBlank @Size(max = 500000) String content) {
}
