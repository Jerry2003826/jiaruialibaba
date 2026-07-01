package com.example.agentdemo.app.dto;

import java.util.List;

/**
 * Paginated app listing.
 */
public record AppPageResponse(List<AppResponse> content, int page, int size, long totalElements, int totalPages) {
}
