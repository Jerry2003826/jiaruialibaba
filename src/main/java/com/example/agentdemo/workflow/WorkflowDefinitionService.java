package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowCompiler workflowCompiler;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowCompiler workflowCompiler, ObjectMapper objectMapper) {
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowCompiler = workflowCompiler;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowDefinitionResponse save(WorkflowDefinitionSaveRequest request) {
        workflowCompiler.compile(request.workflowDefinition());
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity(newId(), request.name().trim(),
                normalizeDescription(request.description()), toJson(request.workflowDefinition()));
        return toResponse(workflowDefinitionRepository.save(entity));
    }

    @Transactional
    public WorkflowDefinitionResponse update(String definitionId, WorkflowDefinitionSaveRequest request) {
        workflowCompiler.compile(request.workflowDefinition());
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        entity.updateDraft(request.name().trim(), normalizeDescription(request.description()),
                toJson(request.workflowDefinition()));
        return toResponse(workflowDefinitionRepository.save(entity));
    }

    @Transactional
    public WorkflowDefinitionResponse publish(String definitionId) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        entity.publish();
        return toResponse(workflowDefinitionRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionResponse> list() {
        return workflowDefinitionRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionResponse get(String definitionId) {
        return toResponse(findEntity(definitionId));
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition resolveDefinition(String definitionId) {
        return fromJson(findEntity(definitionId));
    }

    private WorkflowDefinitionEntity findEntity(String definitionId) {
        if (!StringUtils.hasText(definitionId)) {
            throw new BusinessException("WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition not found: " + definitionId);
        }
        return workflowDefinitionRepository.findByDefinitionId(definitionId)
                .orElseThrow(() -> new BusinessException("WORKFLOW_DEFINITION_NOT_FOUND",
                        "Workflow definition not found: " + definitionId));
    }

    private WorkflowDefinitionResponse toResponse(WorkflowDefinitionEntity entity) {
        return new WorkflowDefinitionResponse(entity.getDefinitionId(), entity.getName(), entity.getDescription(),
                fromJson(entity), entity.getVersion(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private WorkflowDefinition fromJson(WorkflowDefinitionEntity entity) {
        try {
            return objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("WORKFLOW_DEFINITION_DESERIALIZATION_FAILED",
                    "Failed to deserialize workflow definition: " + entity.getDefinitionId(), ex);
        }
    }

    private String toJson(WorkflowDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("WORKFLOW_DEFINITION_SERIALIZATION_FAILED",
                    "Failed to serialize workflow definition", ex);
        }
    }

    private String normalizeDescription(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

}
