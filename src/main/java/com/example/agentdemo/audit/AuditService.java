package com.example.agentdemo.audit;

import com.example.agentdemo.audit.dto.AuditLogPageResponse;
import com.example.agentdemo.audit.dto.AuditLogResponse;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.observability.CorrelationId;
import com.example.agentdemo.security.SecurityIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Writes and reads the audit trail. Writes run in their own transaction ({@code REQUIRES_NEW}) and
 * never propagate failures, so auditing can neither roll back nor break the operation being
 * audited. Only action metadata is stored — never prompt content or secrets.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final AuditActorResolver auditActorResolver;

    public AuditService(AuditLogRepository auditLogRepository, AuditActorResolver auditActorResolver) {
        this.auditLogRepository = auditLogRepository;
        this.auditActorResolver = auditActorResolver;
    }

    public void recordSuccess(String action, String resourceType, String resourceId) {
        record(action, resourceType, resourceId, true, null);
    }

    public void recordFailure(String action, String resourceType, String resourceId, String errorCode) {
        record(action, resourceType, resourceId, false, errorCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, boolean success, String errorCode) {
        try {
            AuditActor actor = auditActorResolver.resolve();
            AuditLogEntity entity = new AuditLogEntity(actor.ownerId(), actor.type(), actor.actorId(), action,
                    resourceType, truncate(resourceId), actor.ip(), actor.userAgent(), CorrelationId.get(),
                    success, errorCode);
            auditLogRepository.save(entity);
        }
        catch (RuntimeException ex) {
            // Auditing must never break the audited operation.
            log.warn("Failed to write audit log for action={} resourceType={} success={}", action, resourceType,
                    success, ex);
        }
    }

    @Transactional(readOnly = true)
    public AuditLogPageResponse list(String resourceType, int page, int size) {
        if (page < 0) {
            throw new BusinessException("AUDIT_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("AUDIT_QUERY_INVALID", "size must be between 1 and 100");
        }
        String ownerId = SecurityIdentity.currentOwnerId();
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> result = StringUtils.hasText(resourceType)
                ? auditLogRepository.findByOwnerIdAndResourceTypeOrderByCreatedAtDescIdDesc(ownerId,
                        resourceType.trim(), pageable)
                : auditLogRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId, pageable);
        return new AuditLogPageResponse(result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private String truncate(String resourceId) {
        if (resourceId == null) {
            return null;
        }
        return resourceId.length() <= 128 ? resourceId : resourceId.substring(0, 128);
    }

    private AuditLogResponse toResponse(AuditLogEntity entity) {
        return new AuditLogResponse(entity.getId(), entity.getActorType(), entity.getActorId(), entity.getAction(),
                entity.getResourceType(), entity.getResourceId(), entity.getIp(), entity.getUserAgent(),
                entity.getRequestId(), entity.isSuccess(), entity.getErrorCode(), entity.getCreatedAt());
    }

}
