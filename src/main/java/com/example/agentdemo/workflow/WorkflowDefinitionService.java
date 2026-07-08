package com.example.agentdemo.workflow;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.example.agentdemo.common.PublicIdGenerator;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.security.SecurityIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
@Service
public class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository;
    private final WorkflowRunRecordRepository workflowRunRecordRepository;
    private final WorkflowCompiler workflowCompiler;
    private final WorkflowRuntimeProperties workflowRuntimeProperties;
    private final WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer;
    private final JsonPayloadCodec jsonPayloadCodec;
    private final PublicIdGenerator publicIdGenerator;

    @Autowired
    public WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            JsonPayloadCodec jsonPayloadCodec, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties, PublicIdGenerator publicIdGenerator,
            WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer) {
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowDefinitionRevisionRepository = workflowDefinitionRevisionRepository;
        this.workflowRunRecordRepository = workflowRunRecordRepository;
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntimeProperties = workflowRuntimeProperties;
        this.structuredOutputAutoconfigurer = structuredOutputAutoconfigurer;
        this.jsonPayloadCodec = jsonPayloadCodec;
        this.publicIdGenerator = publicIdGenerator;
    }

    public WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            ObjectMapper objectMapper, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties) {
        this(workflowDefinitionRepository, workflowDefinitionRevisionRepository, workflowCompiler,
                new JsonPayloadCodec(objectMapper), workflowRunRecordRepository, workflowRuntimeProperties,
                new PublicIdGenerator(), new WorkflowStructuredOutputAutoconfigurer());
    }

    WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            JsonPayloadCodec jsonPayloadCodec, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties, PublicIdGenerator publicIdGenerator) {
        this(workflowDefinitionRepository, workflowDefinitionRevisionRepository, workflowCompiler, jsonPayloadCodec,
                workflowRunRecordRepository, workflowRuntimeProperties, publicIdGenerator,
                new WorkflowStructuredOutputAutoconfigurer());
    }

    @Transactional
    public WorkflowDefinitionResponse save(WorkflowDefinitionSaveRequest request) {
        WorkflowDefinition workflowDefinition = structuredOutputAutoconfigurer.apply(request.workflowDefinition());
        workflowCompiler.compile(workflowDefinition);
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity(newId(), request.name().trim(),
                normalizeDescription(request.description()), toJson(workflowDefinition));
        applyMetadata(entity, request);
        WorkflowDefinitionEntity saved = workflowDefinitionRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional
    public WorkflowDefinitionResponse update(String definitionId, WorkflowDefinitionSaveRequest request) {
        WorkflowDefinition workflowDefinition = structuredOutputAutoconfigurer.apply(request.workflowDefinition());
        workflowCompiler.compile(workflowDefinition);
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        entity.updateDraft(request.name().trim(), normalizeDescription(request.description()),
                toJson(workflowDefinition));
        applyMetadata(entity, request);
        WorkflowDefinitionEntity saved = workflowDefinitionRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional
    @Audited(action = "workflow.publish", resourceType = "workflow", resourceId = "#definitionId")
    public WorkflowDefinitionResponse publish(String definitionId) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        entity.publish();
        WorkflowDefinitionEntity saved = workflowDefinitionRepository.save(entity);
        workflowDefinitionRevisionRepository.findByDefinitionIdAndVersionAndOwnerId(saved.getDefinitionId(),
                        saved.getVersion(), SecurityIdentity.currentOwnerId())
                .ifPresent(revision -> {
                    revision.markPublished();
                    workflowDefinitionRevisionRepository.save(revision);
                });
        return toResponse(saved);
    }

    @Transactional
    @Audited(action = "workflow.rollback", resourceType = "workflow", resourceId = "#definitionId")
    public WorkflowDefinitionResponse rollback(String definitionId, Integer version) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        WorkflowDefinitionRevisionEntity revision = findRevision(definitionId, version);
        WorkflowDefinition definition = fromJson(revision);
        workflowCompiler.compile(definition);
        entity.updateDraft(revision.getName(), revision.getDescription(), revision.getDefinitionJson());
        WorkflowDefinitionEntity saved = workflowDefinitionRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String definitionId) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        if (workflowRunRecordRepository.existsByDefinitionIdAndOwnerId(definitionId,
                SecurityIdentity.currentOwnerId())) {
            throw new BusinessException("WORKFLOW_DEFINITION_IN_USE",
                    "Workflow definition has run history and cannot be deleted: " + definitionId);
        }
        workflowDefinitionRevisionRepository.deleteAllByDefinitionIdAndOwnerId(definitionId,
                SecurityIdentity.currentOwnerId());
        workflowDefinitionRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionResponse> list() {
        return workflowDefinitionRepository.findAllByOwnerIdOrderByCreatedAtDesc(SecurityIdentity.currentOwnerId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionResponse get(String definitionId) {
        return toResponse(findEntity(definitionId));
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionRevisionResponse> listRevisions(String definitionId) {
        findEntity(definitionId);
        return workflowDefinitionRevisionRepository.findAllByDefinitionIdAndOwnerIdOrderByVersionDesc(definitionId,
                        SecurityIdentity.currentOwnerId())
                .stream()
                .map(this::toRevisionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition resolveDefinition(String definitionId) {
        return resolveDefinition(definitionId, null).workflowDefinition();
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionResolution resolveDefinition(String definitionId, Integer version) {
        if (version == null) {
            WorkflowDefinitionEntity entity = findEntity(definitionId);
            ensureRunnable(entity.getStatus());
            return new WorkflowDefinitionResolution(entity.getDefinitionId(), entity.getVersion(), fromJson(entity));
        }
        WorkflowDefinitionRevisionEntity revision = findRevision(definitionId, version);
        ensureRunnable(revision.getStatus());
        return new WorkflowDefinitionResolution(revision.getDefinitionId(), revision.getVersion(), fromJson(revision));
    }

    private void ensureRunnable(WorkflowDefinitionStatus status) {
        if (!workflowRuntimeProperties.isRequirePublishedForRun()) {
            return;
        }
        if (status != WorkflowDefinitionStatus.PUBLISHED) {
            throw new BusinessException("WORKFLOW_DEFINITION_NOT_PUBLISHED",
                    "Workflow definition must be published before it can be run");
        }
    }

    private WorkflowDefinitionEntity findEntity(String definitionId) {
        if (!StringUtils.hasText(definitionId)) {
            throw new BusinessException("WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition not found: " + definitionId);
        }
        return workflowDefinitionRepository.findByDefinitionIdAndOwnerId(definitionId,
                        SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("WORKFLOW_DEFINITION_NOT_FOUND",
                        "Workflow definition not found: " + definitionId));
    }

    private WorkflowDefinitionRevisionEntity findRevision(String definitionId, Integer version) {
        if (!StringUtils.hasText(definitionId) || version == null) {
            throw new BusinessException("WORKFLOW_REVISION_NOT_FOUND",
                    "Workflow definition revision not found: " + definitionId + ":" + version);
        }
        return workflowDefinitionRevisionRepository.findByDefinitionIdAndVersionAndOwnerId(definitionId, version,
                        SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("WORKFLOW_REVISION_NOT_FOUND",
                        "Workflow definition revision not found: " + definitionId + ":" + version));
    }

    private WorkflowDefinitionResponse toResponse(WorkflowDefinitionEntity entity) {
        return new WorkflowDefinitionResponse(entity.getDefinitionId(), entity.getName(), entity.getDescription(),
                fromJson(entity), layoutOf(entity), variablesOf(entity), entity.getVersion(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private void applyMetadata(WorkflowDefinitionEntity entity, WorkflowDefinitionSaveRequest request) {
        if (request.layout() != null) {
            entity.setLayoutJson(request.layout().toString());
        }
        if (request.variables() != null) {
            entity.setVariablesJson(toJson(request.variables()));
        }
    }

    private JsonNode layoutOf(WorkflowDefinitionEntity entity) {
        if (!StringUtils.hasText(entity.getLayoutJson())) {
            return null;
        }
        return jsonPayloadCodec.readTreeOrNull(entity.getLayoutJson());
    }

    private WorkflowVariableSchema variablesOf(WorkflowDefinitionEntity entity) {
        if (!StringUtils.hasText(entity.getVariablesJson())) {
            return null;
        }
        return jsonPayloadCodec.readOrNull(entity.getVariablesJson(), WorkflowVariableSchema.class);
    }

    private String toJson(WorkflowVariableSchema variables) {
        return jsonPayloadCodec.write(variables, "WORKFLOW_VARIABLES_SERIALIZATION_FAILED",
                "Failed to serialize workflow variables");
    }

    private WorkflowDefinitionRevisionResponse toRevisionResponse(WorkflowDefinitionRevisionEntity entity) {
        return new WorkflowDefinitionRevisionResponse(entity.getDefinitionId(), entity.getVersion(), entity.getStatus(),
                entity.getName(), entity.getDescription(), fromJson(entity), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private WorkflowDefinition fromJson(WorkflowDefinitionEntity entity) {
        return jsonPayloadCodec.read(entity.getDefinitionJson(), WorkflowDefinition.class,
                "WORKFLOW_DEFINITION_DESERIALIZATION_FAILED",
                "Failed to deserialize workflow definition: " + entity.getDefinitionId());
    }

    private WorkflowDefinition fromJson(WorkflowDefinitionRevisionEntity entity) {
        return jsonPayloadCodec.read(entity.getDefinitionJson(), WorkflowDefinition.class,
                "WORKFLOW_DEFINITION_DESERIALIZATION_FAILED",
                "Failed to deserialize workflow definition revision: " + entity.getDefinitionId() + ":"
                        + entity.getVersion());
    }

    private void saveRevision(WorkflowDefinitionEntity entity) {
        WorkflowDefinitionRevisionEntity revision = new WorkflowDefinitionRevisionEntity(entity.getDefinitionId(),
                entity.getVersion(), entity.getStatus(), entity.getName(), entity.getDescription(),
                entity.getDefinitionJson());
        workflowDefinitionRevisionRepository.save(revision);
    }

    private String toJson(WorkflowDefinition definition) {
        return jsonPayloadCodec.write(definition, "WORKFLOW_DEFINITION_SERIALIZATION_FAILED",
                "Failed to serialize workflow definition");
    }

    private String normalizeDescription(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }

    private String newId() {
        return publicIdGenerator.nextUuid();
    }

}
