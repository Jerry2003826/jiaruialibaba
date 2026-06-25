package com.example.agentdemo.trace.dto;

import java.util.List;

public record RunPageResponse(
        List<RunResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
