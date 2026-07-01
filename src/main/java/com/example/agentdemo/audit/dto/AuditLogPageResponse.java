package com.example.agentdemo.audit.dto;

import java.util.List;

/**
 * Paginated audit log listing.
 */
public record AuditLogPageResponse(List<AuditLogResponse> content, int page, int size,
        long totalElements, int totalPages) {
}
