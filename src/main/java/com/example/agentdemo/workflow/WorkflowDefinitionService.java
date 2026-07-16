package com.example.agentdemo.workflow;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.BusinessDataException;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.example.agentdemo.common.PublicIdGenerator;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.security.SecurityIdentity;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
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
    private final Supplier<WorkflowGovernanceOrchestrator> workflowGovernanceOrchestratorProvider;
    private final TransactionOperations transactionOperations;

    @Autowired
    public WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            JsonPayloadCodec jsonPayloadCodec, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties, PublicIdGenerator publicIdGenerator,
            WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            ObjectProvider<WorkflowGovernanceOrchestrator> workflowGovernanceOrchestratorProvider,
            PlatformTransactionManager transactionManager) {
        this(workflowDefinitionRepository, workflowDefinitionRevisionRepository, workflowCompiler, jsonPayloadCodec,
                workflowRunRecordRepository, workflowRuntimeProperties, publicIdGenerator,
                structuredOutputAutoconfigurer, workflowGovernanceOrchestratorProvider::getIfAvailable,
                new TransactionTemplate(transactionManager));
    }

    public WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            JsonPayloadCodec jsonPayloadCodec, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties, PublicIdGenerator publicIdGenerator,
            WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer) {
        this(workflowDefinitionRepository, workflowDefinitionRevisionRepository, workflowCompiler, jsonPayloadCodec,
                workflowRunRecordRepository, workflowRuntimeProperties, publicIdGenerator,
                structuredOutputAutoconfigurer, (Supplier<WorkflowGovernanceOrchestrator>) () -> null,
                (TransactionOperations) null);
    }

    WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            JsonPayloadCodec jsonPayloadCodec, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties, PublicIdGenerator publicIdGenerator,
            WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowGovernanceOrchestrator workflowGovernanceOrchestrator,
            TransactionOperations transactionOperations) {
        this(workflowDefinitionRepository, workflowDefinitionRevisionRepository, workflowCompiler, jsonPayloadCodec,
                workflowRunRecordRepository, workflowRuntimeProperties, publicIdGenerator,
                structuredOutputAutoconfigurer, () -> workflowGovernanceOrchestrator, transactionOperations);
    }

    private WorkflowDefinitionService(WorkflowDefinitionRepository workflowDefinitionRepository,
            WorkflowDefinitionRevisionRepository workflowDefinitionRevisionRepository, WorkflowCompiler workflowCompiler,
            JsonPayloadCodec jsonPayloadCodec, WorkflowRunRecordRepository workflowRunRecordRepository,
            WorkflowRuntimeProperties workflowRuntimeProperties, PublicIdGenerator publicIdGenerator,
            WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            Supplier<WorkflowGovernanceOrchestrator> workflowGovernanceOrchestratorProvider,
            TransactionOperations transactionOperations) {
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowDefinitionRevisionRepository = workflowDefinitionRevisionRepository;
        this.workflowRunRecordRepository = workflowRunRecordRepository;
        this.workflowCompiler = workflowCompiler;
        this.workflowRuntimeProperties = workflowRuntimeProperties;
        this.structuredOutputAutoconfigurer = structuredOutputAutoconfigurer;
        this.jsonPayloadCodec = jsonPayloadCodec;
        this.publicIdGenerator = publicIdGenerator;
        this.workflowGovernanceOrchestratorProvider = workflowGovernanceOrchestratorProvider;
        this.transactionOperations = transactionOperations;
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
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity(newId(), request.name().trim(),
                normalizeDescription(request.description()), toJson(workflowDefinition),
                lockedSpecJson(request.lockedSpec()));
        applyMetadata(entity, request, workflowDefinition);
        WorkflowDefinitionEntity saved = workflowDefinitionRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Transactional
    public WorkflowDefinitionResponse update(String definitionId, WorkflowDefinitionSaveRequest request) {
        WorkflowDefinition workflowDefinition = structuredOutputAutoconfigurer.apply(request.workflowDefinition());
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        String lockedSpecJson = request.lockedSpec() == null
                ? entity.getLockedSpecJson()
                : lockedSpecJson(request.lockedSpec());
        entity.updateDraft(request.name().trim(), normalizeDescription(request.description()),
                toJson(workflowDefinition), lockedSpecJson);
        applyMetadata(entity, request, workflowDefinition);
        WorkflowDefinitionEntity saved = workflowDefinitionRepository.save(entity);
        saveRevision(saved);
        return toResponse(saved);
    }

    @Audited(action = "workflow.publish", resourceType = "workflow", resourceId = "#definitionId")
    public WorkflowDefinitionResponse publish(String definitionId) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        if (entity.getStatus() == WorkflowDefinitionStatus.PUBLISHED) {
            return toResponse(entity);
        }
        WorkflowGovernanceOrchestrator workflowGovernanceOrchestrator = workflowGovernanceOrchestratorProvider.get();
        if (workflowGovernanceOrchestrator == null || transactionOperations == null) {
            throw new BusinessException("WORKFLOW_GOVERNANCE_NOT_CONFIGURED",
                    "Workflow governance is required before publication");
        }

        WorkflowDefinition definition = fromJson(entity);
        WorkflowGovernanceEvaluationResponse evaluation = workflowGovernanceOrchestrator.evaluate(
                definition, lockedSpec(entity), Map.of());
        if (evaluation.status() == WorkflowGenerationStatus.BLOCKED) {
            long failedRuntimeCases = evaluation.testResults().stream()
                    .filter(result -> result.status() != WorkflowEvaluationCaseStatus.PASSED)
                    .count();
            throw new BusinessDataException("WORKFLOW_GOVERNANCE_BLOCKED",
                    "Workflow governance blocked publication with "
                            + evaluation.governanceReport().blockers().size() + " static blocking finding(s) and "
                            + failedRuntimeCases + " failed runtime case(s)",
                    evaluation);
        }
        if (evaluation.status() == WorkflowGenerationStatus.INFRA_ERROR) {
            throw new BusinessDataException("WORKFLOW_GOVERNANCE_INFRA_ERROR",
                    "Workflow governance infrastructure is unavailable; publication was not changed",
                    evaluation);
        }

        Integer evaluatedVersion = entity.getVersion();
        String evaluatedDefinitionJson = entity.getDefinitionJson();
        String evaluatedLockedSpecJson = entity.getLockedSpecJson();
        WorkflowDefinitionResponse published = transactionOperations.execute(status ->
                publishEvaluated(definitionId, evaluatedVersion, evaluatedDefinitionJson, evaluatedLockedSpecJson));
        if (published == null) {
            throw new BusinessException("WORKFLOW_PUBLISH_FAILED", "Workflow publication returned no result");
        }
        return published;
    }

    private WorkflowDefinitionResponse publishEvaluated(String definitionId, Integer evaluatedVersion,
            String evaluatedDefinitionJson, String evaluatedLockedSpecJson) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        if (!Objects.equals(entity.getVersion(), evaluatedVersion)
                || !Objects.equals(entity.getDefinitionJson(), evaluatedDefinitionJson)
                || !Objects.equals(entity.getLockedSpecJson(), evaluatedLockedSpecJson)) {
            throw new BusinessException("WORKFLOW_DEFINITION_CHANGED",
                    "Workflow definition changed after governance evaluation; evaluate and publish again");
        }
        workflowCompiler.compile(fromJson(entity));
        return publishEntity(entity);
    }

    private WorkflowDefinitionResponse publishEntity(WorkflowDefinitionEntity entity) {
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

    private Object lockedSpec(WorkflowDefinitionEntity entity) {
        JsonNode persisted = lockedSpecOf(entity);
        if (persisted != null) {
            return persisted.isTextual() ? persisted.asText() : persisted;
        }
        // Legacy rows predate V16; retain their previous publish behavior until they are saved again.
        Map<String, Object> lockedSpec = new LinkedHashMap<>();
        lockedSpec.put("name", entity.getName());
        if (StringUtils.hasText(entity.getDescription())) {
            lockedSpec.put("description", entity.getDescription());
        }
        return lockedSpec;
    }

    @Transactional
    @Audited(action = "workflow.rollback", resourceType = "workflow", resourceId = "#definitionId")
    public WorkflowDefinitionResponse rollback(String definitionId, Integer version) {
        WorkflowDefinitionEntity entity = findEntity(definitionId);
        WorkflowDefinitionRevisionEntity revision = findRevision(definitionId, version);
        WorkflowDefinition definition = fromJson(revision);
        workflowCompiler.compile(definition);
        entity.updateDraft(revision.getName(), revision.getDescription(), revision.getDefinitionJson(),
                revision.getLockedSpecJson());
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
        WorkflowDefinition definition = fromJson(entity);
        WorkflowVariableSchema variables = variablesOf(entity);
        return new WorkflowDefinitionResponse(entity.getDefinitionId(), entity.getName(), entity.getDescription(),
                definition, layoutOf(entity), WorkflowVariableSchemaInferrer.infer(definition, variables),
                entity.getVersion(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt(), lockedSpecOf(entity));
    }

    private void applyMetadata(WorkflowDefinitionEntity entity, WorkflowDefinitionSaveRequest request,
            WorkflowDefinition definition) {
        if (request.layout() != null) {
            entity.setLayoutJson(request.layout().toString());
        }
        entity.setVariablesJson(toJson(WorkflowVariableSchemaInferrer.infer(definition, request.variables())));
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

    private JsonNode lockedSpecOf(WorkflowDefinitionEntity entity) {
        return WorkflowLockedSpecCodec.fromJson(jsonPayloadCodec, entity.getLockedSpecJson());
    }

    private JsonNode lockedSpecOf(WorkflowDefinitionRevisionEntity entity) {
        return WorkflowLockedSpecCodec.fromJson(jsonPayloadCodec, entity.getLockedSpecJson());
    }

    private String lockedSpecJson(JsonNode lockedSpec) {
        return WorkflowLockedSpecCodec.toJson(jsonPayloadCodec, lockedSpec);
    }

    private String toJson(WorkflowVariableSchema variables) {
        return jsonPayloadCodec.write(variables, "WORKFLOW_VARIABLES_SERIALIZATION_FAILED",
                "Failed to serialize workflow variables");
    }

    private WorkflowDefinitionRevisionResponse toRevisionResponse(WorkflowDefinitionRevisionEntity entity) {
        return new WorkflowDefinitionRevisionResponse(entity.getDefinitionId(), entity.getVersion(), entity.getStatus(),
                entity.getName(), entity.getDescription(), fromJson(entity), entity.getCreatedAt(),
                entity.getUpdatedAt(), lockedSpecOf(entity));
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
                entity.getDefinitionJson(), entity.getLockedSpecJson());
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
