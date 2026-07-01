package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppPageResponse;
import com.example.agentdemo.app.dto.AppResponse;
import com.example.agentdemo.app.dto.CreateAppRequest;
import com.example.agentdemo.app.dto.UpdateAppRequest;
import com.example.agentdemo.audit.AuditService;
import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.security.SecurityIdentity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.workflow.WorkflowDefinitionResolution;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Manages the app lifecycle: create, update (new draft version), publish (pins an immutable
 * revision snapshot), rollback and delete/archive. WORKFLOW apps bind a published workflow, whose
 * version is resolved and pinned at publish time so runs stay reproducible.
 */
@Service
public class AppService {

    private final AppRepository appRepository;
    private final AppRevisionRepository appRevisionRepository;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final RunRepository runRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AppService(AppRepository appRepository, AppRevisionRepository appRevisionRepository,
            WorkflowDefinitionService workflowDefinitionService, RunRepository runRepository,
            AuditService auditService, ObjectMapper objectMapper) {
        this.appRepository = appRepository;
        this.appRevisionRepository = appRevisionRepository;
        this.workflowDefinitionService = workflowDefinitionService;
        this.runRepository = runRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Audited(action = "app.create", resourceType = "app", resourceId = "#result.appId()")
    public AppResponse create(CreateAppRequest request) {
        AppType type = request.type();
        validateWorkflowBinding(type, request.workflowDefinitionId());
        AppConfig config = request.config() == null ? AppConfig.empty() : request.config();
        AppEntity entity = new AppEntity(newAppId(), request.name().trim(), normalize(request.description()), type,
                toJson(config), normalize(request.workflowDefinitionId()), request.workflowDefinitionVersion());
        AppEntity saved = appRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AppPageResponse list(int page, int size) {
        if (page < 0) {
            throw new BusinessException("APP_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("APP_QUERY_INVALID", "size must be between 1 and 100");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AppEntity> result = appRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(
                SecurityIdentity.currentOwnerId(), pageable);
        return new AppPageResponse(result.getContent().stream().map(this::toResponse).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AppResponse get(String appId) {
        return toResponse(findApp(appId));
    }

    @Transactional
    @Audited(action = "app.update", resourceType = "app", resourceId = "#appId")
    public AppResponse update(String appId, UpdateAppRequest request) {
        AppEntity entity = findApp(appId);
        validateWorkflowBinding(entity.getType(), request.workflowDefinitionId());
        AppConfig config = request.config() == null ? AppConfig.empty() : request.config();
        entity.updateDraft(request.name().trim(), normalize(request.description()), toJson(config),
                normalize(request.workflowDefinitionId()), request.workflowDefinitionVersion());
        AppEntity saved = appRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional
    @Audited(action = "app.publish", resourceType = "app", resourceId = "#appId")
    public AppResponse publish(String appId) {
        AppEntity entity = findApp(appId);
        Integer pinnedWorkflowVersion = null;
        if (entity.getType() == AppType.WORKFLOW) {
            if (!StringUtils.hasText(entity.getWorkflowDefinitionId())) {
                throw new BusinessException("APP_WORKFLOW_BINDING_REQUIRED",
                        "A WORKFLOW app must bind a workflowDefinitionId before publishing");
            }
            // Resolves (and requires published) the bound workflow, pinning the concrete version.
            WorkflowDefinitionResolution resolution = workflowDefinitionService.resolveDefinition(
                    entity.getWorkflowDefinitionId(), entity.getWorkflowDefinitionVersion());
            pinnedWorkflowVersion = resolution.version();
        }
        entity.publish(pinnedWorkflowVersion);
        AppEntity saved = appRepository.save(entity);
        // Finalize the current version's revision as the immutable, published snapshot (with the
        // pinned workflow version baked in) so runtime always serves reproducible state.
        AppRevisionEntity revision = appRevisionRepository
                .findByAppIdAndVersionAndOwnerId(appId, saved.getVersion(), SecurityIdentity.currentOwnerId())
                .orElseGet(() -> new AppRevisionEntity(appId, saved.getVersion(), AppStatus.DRAFT,
                        toJson(snapshotOf(saved))));
        revision.publishSnapshot(toJson(snapshotOf(saved)));
        appRevisionRepository.save(revision);
        return toResponse(saved);
    }

    @Transactional
    @Audited(action = "app.rollback", resourceType = "app", resourceId = "#appId")
    public AppResponse rollback(String appId, Integer version) {
        AppEntity entity = findApp(appId);
        AppSnapshot snapshot = fromJson(findRevision(appId, version).getSnapshotJson());
        entity.updateDraft(snapshot.name(), snapshot.description(), toJson(snapshot.config()),
                snapshot.workflowDefinitionId(), snapshot.workflowDefinitionVersion());
        AppEntity saved = appRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String appId) {
        AppEntity entity = findApp(appId);
        String ownerId = SecurityIdentity.currentOwnerId();
        if (runRepository.existsByOwnerIdAndAppId(ownerId, appId)) {
            entity.archive();
            appRepository.save(entity);
            auditService.recordSuccess("app.archive", "app", appId);
            return;
        }
        appRevisionRepository.deleteByAppIdAndOwnerId(appId, ownerId);
        appRepository.delete(entity);
        auditService.recordSuccess("app.delete", "app", appId);
    }

    @Transactional(readOnly = true)
    public List<AppRevisionEntity> revisions(String appId) {
        findApp(appId);
        return appRevisionRepository.findByAppIdAndOwnerIdOrderByVersionDesc(appId,
                SecurityIdentity.currentOwnerId());
    }

    AppEntity findApp(String appId) {
        if (!StringUtils.hasText(appId)) {
            throw new BusinessException("APP_NOT_FOUND", "App not found: " + appId);
        }
        return appRepository.findByAppIdAndOwnerId(appId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("APP_NOT_FOUND", "App not found: " + appId));
    }

    AppRevisionEntity findRevision(String appId, Integer version) {
        if (version == null) {
            throw new BusinessException("APP_REVISION_NOT_FOUND", "App revision not found: " + appId + ":null");
        }
        return appRevisionRepository.findByAppIdAndVersionAndOwnerId(appId, version, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("APP_REVISION_NOT_FOUND",
                        "App revision not found: " + appId + ":" + version));
    }

    private void validateWorkflowBinding(AppType type, String workflowDefinitionId) {
        if (type == AppType.WORKFLOW && !StringUtils.hasText(workflowDefinitionId)) {
            throw new BusinessException("APP_WORKFLOW_BINDING_REQUIRED",
                    "A WORKFLOW app requires a workflowDefinitionId");
        }
    }

    private void saveRevision(AppEntity entity) {
        appRevisionRepository.save(new AppRevisionEntity(entity.getAppId(), entity.getVersion(), entity.getStatus(),
                toJson(snapshotOf(entity))));
    }

    private AppSnapshot snapshotOf(AppEntity entity) {
        return new AppSnapshot(entity.getName(), entity.getDescription(), entity.getType(),
                configFromJson(entity.getConfigJson()), entity.getWorkflowDefinitionId(),
                entity.getWorkflowDefinitionVersion());
    }

    private AppConfig configFromJson(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return AppConfig.empty();
        }
        try {
            return objectMapper.readValue(configJson, AppConfig.class);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("APP_CONFIG_DESERIALIZATION_FAILED", "Failed to read app config", ex);
        }
    }

    private AppResponse toResponse(AppEntity entity) {
        return new AppResponse(entity.getAppId(), entity.getName(), entity.getDescription(), entity.getType(),
                entity.getStatus(), configFromJson(entity.getConfigJson()), entity.getWorkflowDefinitionId(),
                entity.getWorkflowDefinitionVersion(), entity.getVersion(), entity.getPublishedVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private AppSnapshot fromJson(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, AppSnapshot.class);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("APP_SNAPSHOT_DESERIALIZATION_FAILED", "Failed to read app snapshot", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("APP_SERIALIZATION_FAILED", "Failed to serialize app payload", ex);
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String newAppId() {
        return "app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

}
