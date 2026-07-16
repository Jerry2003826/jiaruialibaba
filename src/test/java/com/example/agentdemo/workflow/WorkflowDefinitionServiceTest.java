package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessDataException;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.example.agentdemo.common.PublicIdGenerator;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceFinding;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDefinitionServiceTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowDefinitionRevisionRepository revisionRepository = mock(WorkflowDefinitionRevisionRepository.class);
    private final WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final WorkflowRuntimeProperties workflowRuntimeProperties = new WorkflowRuntimeProperties();
    private final WorkflowDefinitionService service = new WorkflowDefinitionService(repository, revisionRepository, compiler,
            new ObjectMapper(), runRecordRepository, workflowRuntimeProperties);

    @BeforeEach
    void disablePublishGuardByDefault() {
        workflowRuntimeProperties.setRequirePublishedForRun(false);
    }

    @Test
    void savesWorkflowDefinitionAsDraft() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest("Support Bot", "Answers docs",
                definition);
        when(repository.save(any())).thenAnswer(invocation -> {
            WorkflowDefinitionEntity entity = invocation.getArgument(0);
            entity.prePersist();
            return entity;
        });

        WorkflowDefinitionResponse response = service.save(request);

        assertThat(response.definitionId()).isNotBlank();
        assertThat(UUID.fromString(response.definitionId())).isNotNull();
        assertThat(response.name()).isEqualTo("Support Bot");
        assertThat(response.description()).isEqualTo("Answers docs");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(response.workflowDefinition()).isEqualTo(definition);

        ArgumentCaptor<WorkflowDefinitionEntity> entityCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getDefinitionJson()).contains("\"nodes\"");

        ArgumentCaptor<WorkflowDefinitionRevisionEntity> revisionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getDefinitionId()).isEqualTo(response.definitionId());
        assertThat(revisionCaptor.getValue().getVersion()).isEqualTo(1);
        assertThat(revisionCaptor.getValue().getStatus()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(revisionCaptor.getValue().getDefinitionJson()).contains("\"nodes\"");
    }

    @Test
    void savesCanonicalLockedSpecOnDefinitionAndRevision() throws Exception {
        JsonNode lockedSpec = new ObjectMapper().readTree("""
                {"goal":"route reviews","domain":"customer-service-ecommerce"}
                """);
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest(
                "Review Router", "Routes reviews", definition, null, null, lockedSpec);
        when(repository.save(any())).thenAnswer(invocation -> {
            WorkflowDefinitionEntity entity = invocation.getArgument(0);
            entity.prePersist();
            return entity;
        });

        WorkflowDefinitionResponse response = service.save(request);

        assertThat(response.lockedSpec()).isEqualTo(lockedSpec);
        ArgumentCaptor<WorkflowDefinitionEntity> entityCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getLockedSpecJson())
                .isEqualTo("{\"domain\":\"customer-service-ecommerce\",\"goal\":\"route reviews\"}");
        ArgumentCaptor<WorkflowDefinitionRevisionEntity> revisionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getLockedSpecJson()).isEqualTo(entityCaptor.getValue().getLockedSpecJson());
    }

    @Test
    void saveUsesPublicIdGeneratorUuidStrategy() {
        PublicIdGenerator publicIdGenerator = mock(PublicIdGenerator.class);
        String generatedId = "123e4567-e89b-12d3-a456-426614174000";
        WorkflowDefinitionService serviceWithGenerator = new WorkflowDefinitionService(repository, revisionRepository,
                compiler, new JsonPayloadCodec(new ObjectMapper()), runRecordRepository, workflowRuntimeProperties,
                publicIdGenerator);
        WorkflowDefinition definition = validDefinition();
        when(publicIdGenerator.nextUuid()).thenReturn(generatedId);
        when(repository.save(any())).thenAnswer(invocation -> {
            WorkflowDefinitionEntity entity = invocation.getArgument(0);
            entity.prePersist();
            return entity;
        });

        WorkflowDefinitionResponse response = serviceWithGenerator
                .save(new WorkflowDefinitionSaveRequest("Support Bot", "Answers docs", definition));

        assertThat(response.definitionId()).isEqualTo(generatedId);
        assertThat(UUID.fromString(response.definitionId()).toString()).isEqualTo(generatedId);
    }

    @Test
    void savesIncompleteDraftWithoutCompilerValidation() {
        WorkflowDefinition draft = new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of());
        when(repository.save(any())).thenAnswer(invocation -> {
            WorkflowDefinitionEntity entity = invocation.getArgument(0);
            entity.prePersist();
            return entity;
        });

        WorkflowDefinitionResponse response = service.save(new WorkflowDefinitionSaveRequest("Draft", null, draft));

        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(response.workflowDefinition()).isEqualTo(draft);

        ArgumentCaptor<WorkflowDefinitionEntity> entityCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getDefinitionJson()).contains("\"edges\":[]");
    }

    @Test
    void resolvesStoredDefinitionById() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(entity));

        WorkflowDefinition resolved = service.resolveDefinition("wf-1");

        assertThat(resolved).isEqualTo(definition);
    }

    @Test
    void resolvesCurrentDefinitionWithVersionMetadata() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        entity.updateDraft("Support Bot v2", null, new ObjectMapper().writeValueAsString(definition));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(entity));

        WorkflowDefinitionResolution resolution = service.resolveDefinition("wf-1", null);

        assertThat(resolution.definitionId()).isEqualTo("wf-1");
        assertThat(resolution.version()).isEqualTo(2);
        assertThat(resolution.workflowDefinition()).isEqualTo(definition);
    }

    @Test
    void resolvesPinnedRevisionWithVersionMetadata() throws Exception {
        WorkflowDefinition definition = validDefinitionWithToolNode();
        WorkflowDefinitionRevisionEntity revision = new WorkflowDefinitionRevisionEntity("wf-1", 3,
                WorkflowDefinitionStatus.PUBLISHED, "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        when(revisionRepository.findByDefinitionIdAndVersionAndOwnerId("wf-1", 3, "workbench-dev"))
                .thenReturn(Optional.of(revision));

        WorkflowDefinitionResolution resolution = service.resolveDefinition("wf-1", 3);

        assertThat(resolution.definitionId()).isEqualTo("wf-1");
        assertThat(resolution.version()).isEqualTo(3);
        assertThat(resolution.workflowDefinition()).isEqualTo(definition);
    }

    @Test
    void throwsWhenDefinitionIdIsMissing() {
        when(repository.findByDefinitionIdAndOwnerId("missing", "workbench-dev")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveDefinition("missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_DEFINITION_NOT_FOUND"))
                .hasMessage("Workflow definition not found: missing");
    }

    @Test
    void listsDefinitionsInRepositoryOrder() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity first = new WorkflowDefinitionEntity("wf-1", "First", null,
                new ObjectMapper().writeValueAsString(definition));
        WorkflowDefinitionEntity second = new WorkflowDefinitionEntity("wf-2", "Second", "desc",
                new ObjectMapper().writeValueAsString(definition));
        when(repository.findAllByOwnerIdOrderByCreatedAtDesc("workbench-dev")).thenReturn(List.of(second, first));

        List<WorkflowDefinitionResponse> responses = service.list();

        assertThat(responses)
                .extracting(WorkflowDefinitionResponse::definitionId)
                .containsExactly("wf-2", "wf-1");
    }

    @Test
    void updatesStoredDefinitionWithNextVersionAndDraftStatus() throws Exception {
        WorkflowDefinition updated = validDefinitionWithToolNode();
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowDefinitionResponse response = service.update("wf-1",
                new WorkflowDefinitionSaveRequest("Support Bot v2", "Updated", updated));

        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.name()).isEqualTo("Support Bot v2");
        assertThat(response.description()).isEqualTo("Updated");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(response.workflowDefinition()).isEqualTo(updated);
        verify(repository).save(existing);

        ArgumentCaptor<WorkflowDefinitionRevisionEntity> revisionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getDefinitionId()).isEqualTo("wf-1");
        assertThat(revisionCaptor.getValue().getVersion()).isEqualTo(2);
        assertThat(revisionCaptor.getValue().getStatus()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(revisionCaptor.getValue().getDefinitionJson()).contains("getCurrentTime");
    }

    @Test
    void legacyUpdateWithoutLockedSpecPreservesExistingCanonicalSpec() throws Exception {
        WorkflowDefinition existingDefinition = validDefinition();
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity(
                "wf-1", "Review Router", null, new ObjectMapper().writeValueAsString(existingDefinition),
                "{\"domain\":\"customer-service-ecommerce\",\"goal\":\"route reviews\"}");
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowDefinitionResponse response = service.update("wf-1",
                new WorkflowDefinitionSaveRequest("Review Router v2", null, validDefinitionWithToolNode()));

        assertThat(response.lockedSpec().path("goal").asText()).isEqualTo("route reviews");
        assertThat(existing.getLockedSpecJson()).contains("customer-service-ecommerce");
        ArgumentCaptor<WorkflowDefinitionRevisionEntity> revisionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getLockedSpecJson()).isEqualTo(existing.getLockedSpecJson());
    }

    @Test
    void publishFailsClosedWhenGovernanceIsNotConfigured() throws Exception {
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.publish("wf-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("WORKFLOW_GOVERNANCE_NOT_CONFIGURED"));
        verify(repository, never()).save(any());
    }

    @Test
    void publishRunsGovernanceBeforeOpeningWriteTransaction() throws Exception {
        WorkflowGovernanceOrchestrator orchestrator = mock(WorkflowGovernanceOrchestrator.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        WorkflowDefinitionService governedService = governedService(orchestrator, transactions);
        WorkflowDefinition definition = validDefinition();
        String lockedSpecJson = "{\"domain\":\"customer-service-ecommerce\",\"goal\":\"route reviews\"}";
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", "Answers docs",
                new ObjectMapper().writeValueAsString(definition), lockedSpecJson);
        WorkflowDefinitionRevisionEntity currentRevision = new WorkflowDefinitionRevisionEntity("wf-1", 1,
                WorkflowDefinitionStatus.DRAFT, "Support Bot", "Answers docs",
                new ObjectMapper().writeValueAsString(definition), lockedSpecJson);
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisionRepository.findByDefinitionIdAndVersionAndOwnerId("wf-1", 1, "workbench-dev"))
                .thenReturn(Optional.of(currentRevision));
        when(orchestrator.evaluate(eq(definition), argThat(spec -> String.valueOf(spec).contains("route reviews")),
                eq(Map.of())))
                .thenReturn(governanceResponse(WorkflowGenerationStatus.READY, definition));
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        WorkflowDefinitionResponse response = governedService.publish("wf-1");

        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.PUBLISHED);
        InOrder order = inOrder(orchestrator, transactions);
        order.verify(orchestrator).evaluate(eq(definition),
                argThat(spec -> String.valueOf(spec).contains("route reviews")), eq(Map.of()));
        order.verify(transactions).execute(any(TransactionCallback.class));
        verify(repository).save(existing);
    }

    @Test
    void publishReturnsExistingPublishedVersionWithoutRerunningGovernance() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        existing.prePersist();
        existing.publish();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));

        WorkflowDefinitionResponse response = service.publish("wf-1");

        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.PUBLISHED);
        verify(repository, never()).save(any());
    }

    @Test
    void publishRejectsGovernanceBlockWithoutOpeningWriteTransaction() throws Exception {
        WorkflowGovernanceOrchestrator orchestrator = mock(WorkflowGovernanceOrchestrator.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        WorkflowDefinitionService governedService = governedService(orchestrator, transactions);
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        WorkflowGovernanceFinding blocker = new WorkflowGovernanceFinding(
                "core-test", WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC, "blocked", List.of("llm"), "repair", Map.of());
        WorkflowGovernanceEvaluationResponse evaluation = new WorkflowGovernanceEvaluationResponse(
                        WorkflowGenerationStatus.BLOCKED,
                        definition,
                        new WorkflowGovernanceReport(List.of(blocker)),
                        new WorkflowEvaluationReport(Map.of(), List.of()),
                        List.of(),
                        List.of(new WorkflowActiveRulePack("core", "1.0.0")));
        when(orchestrator.evaluate(eq(definition), argThat(spec -> String.valueOf(spec).contains("Support Bot")),
                eq(Map.of()))).thenReturn(evaluation);

        assertThatThrownBy(() -> governedService.publish("wf-1"))
                .isInstanceOfSatisfying(BusinessDataException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("WORKFLOW_GOVERNANCE_BLOCKED");
                    assertThat(error.getData()).isSameAs(evaluation);
                });

        verify(transactions, never()).execute(any(TransactionCallback.class));
        verify(repository, never()).save(any());
        assertThat(existing.getStatus()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
    }

    @Test
    void publishRejectsInfrastructureFailureWithDistinctCode() throws Exception {
        WorkflowGovernanceOrchestrator orchestrator = mock(WorkflowGovernanceOrchestrator.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        WorkflowDefinitionService governedService = governedService(orchestrator, transactions);
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        WorkflowGovernanceEvaluationResponse evaluation =
                governanceResponse(WorkflowGenerationStatus.INFRA_ERROR, definition);
        when(orchestrator.evaluate(eq(definition), argThat(spec -> String.valueOf(spec).contains("Support Bot")),
                eq(Map.of()))).thenReturn(evaluation);

        assertThatThrownBy(() -> governedService.publish("wf-1"))
                .isInstanceOfSatisfying(BusinessDataException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("WORKFLOW_GOVERNANCE_INFRA_ERROR");
                    assertThat(error.getData()).isSameAs(evaluation);
                });

        verify(transactions, never()).execute(any(TransactionCallback.class));
        verify(repository, never()).save(any());
    }

    @Test
    void publishDoesNotCompileOrMutateIncompleteDraftWhenGovernanceIsUnavailable() throws Exception {
        WorkflowDefinition invalidDraft = new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of());
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Draft Bot", null,
                new ObjectMapper().writeValueAsString(invalidDraft));
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.publish("wf-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("WORKFLOW_GOVERNANCE_NOT_CONFIGURED"));

        assertThat(existing.getStatus()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        verify(repository, never()).save(existing);
    }

    @Test
    void listsStoredRevisionsNewestFirst() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinition updated = validDefinitionWithToolNode();
        WorkflowDefinitionEntity current = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(updated));
        WorkflowDefinitionRevisionEntity revision2 = new WorkflowDefinitionRevisionEntity("wf-1", 2,
                WorkflowDefinitionStatus.DRAFT, "Support Bot v2", "Updated",
                new ObjectMapper().writeValueAsString(updated));
        WorkflowDefinitionRevisionEntity revision1 = new WorkflowDefinitionRevisionEntity("wf-1", 1,
                WorkflowDefinitionStatus.PUBLISHED, "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(current));
        when(revisionRepository.findAllByDefinitionIdAndOwnerIdOrderByVersionDesc("wf-1", "workbench-dev"))
                .thenReturn(List.of(revision2, revision1));

        List<WorkflowDefinitionRevisionResponse> revisions = service.listRevisions("wf-1");

        assertThat(revisions)
                .extracting(WorkflowDefinitionRevisionResponse::version)
                .containsExactly(2, 1);
        assertThat(revisions.get(0).workflowDefinition()).isEqualTo(updated);
        assertThat(revisions.get(1).status()).isEqualTo(WorkflowDefinitionStatus.PUBLISHED);
    }

    @Test
    void rollsBackToRevisionAsNewDraftVersion() throws Exception {
        WorkflowDefinition currentDefinition = validDefinitionWithToolNode();
        WorkflowDefinition rollbackDefinition = validDefinition();
        WorkflowDefinitionEntity current = new WorkflowDefinitionEntity("wf-1", "Support Bot v2", "Updated",
                new ObjectMapper().writeValueAsString(currentDefinition));
        current.updateDraft("Support Bot v2", "Updated", new ObjectMapper().writeValueAsString(currentDefinition));
        WorkflowDefinitionRevisionEntity targetRevision = new WorkflowDefinitionRevisionEntity("wf-1", 1,
                WorkflowDefinitionStatus.PUBLISHED, "Support Bot", null,
                new ObjectMapper().writeValueAsString(rollbackDefinition));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisionRepository.findByDefinitionIdAndVersionAndOwnerId("wf-1", 1, "workbench-dev"))
                .thenReturn(Optional.of(targetRevision));

        WorkflowDefinitionResponse response = service.rollback("wf-1", 1);

        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.name()).isEqualTo("Support Bot");
        assertThat(response.description()).isNull();
        assertThat(response.version()).isEqualTo(3);
        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(response.workflowDefinition()).isEqualTo(rollbackDefinition);

        ArgumentCaptor<WorkflowDefinitionRevisionEntity> revisionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getDefinitionId()).isEqualTo("wf-1");
        assertThat(revisionCaptor.getValue().getVersion()).isEqualTo(3);
        assertThat(revisionCaptor.getValue().getStatus()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(revisionCaptor.getValue().getDefinitionJson()).isEqualTo(targetRevision.getDefinitionJson());
    }

    @Test
    void rollbackRestoresTargetRevisionLockedSpecAsNewDraftVersion() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity current = new WorkflowDefinitionEntity(
                "wf-1", "Current", null, new ObjectMapper().writeValueAsString(definition),
                "{\"goal\":\"current goal\"}");
        current.prePersist();
        WorkflowDefinitionRevisionEntity target = new WorkflowDefinitionRevisionEntity(
                "wf-1", 1, WorkflowDefinitionStatus.PUBLISHED, "Original", null,
                new ObjectMapper().writeValueAsString(definition),
                "{\"domain\":\"customer-service-ecommerce\",\"goal\":\"original goal\"}");
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(current));
        when(revisionRepository.findByDefinitionIdAndVersionAndOwnerId("wf-1", 1, "workbench-dev"))
                .thenReturn(Optional.of(target));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowDefinitionResponse response = service.rollback("wf-1", 1);

        assertThat(response.lockedSpec().path("goal").asText()).isEqualTo("original goal");
        assertThat(current.getLockedSpecJson()).contains("original goal");
        ArgumentCaptor<WorkflowDefinitionRevisionEntity> revisionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getLockedSpecJson()).contains("original goal");
    }

    @Test
    void publishRejectsWhenLockedSpecChangesAfterEvaluation() throws Exception {
        WorkflowGovernanceOrchestrator orchestrator = mock(WorkflowGovernanceOrchestrator.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        WorkflowDefinitionService governedService = governedService(orchestrator, transactions);
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity(
                "wf-1", "Review Router", null, new ObjectMapper().writeValueAsString(definition),
                "{\"goal\":\"evaluated goal\"}");
        existing.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        when(orchestrator.evaluate(eq(definition), any(), eq(Map.of())))
                .thenReturn(governanceResponse(WorkflowGenerationStatus.READY, definition));
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            existing.setLockedSpecJson("{\"goal\":\"changed after evaluation\"}");
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        assertThatThrownBy(() -> governedService.publish("wf-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WORKFLOW_DEFINITION_CHANGED"));

        verify(repository, never()).save(any());
    }

    @Test
    void throwsWhenRollbackRevisionIsMissing() throws Exception {
        WorkflowDefinitionEntity current = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(current));
        when(revisionRepository.findByDefinitionIdAndVersionAndOwnerId("wf-1", 99, "workbench-dev"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback("wf-1", 99))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_REVISION_NOT_FOUND"))
                .hasMessage("Workflow definition revision not found: wf-1:99");
    }

    @Test
    void deletesDefinitionAndRevisionsWhenNoRunHistoryExists() throws Exception {
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        when(runRecordRepository.existsByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(false);

        service.delete("wf-1");

        verify(revisionRepository).deleteAllByDefinitionIdAndOwnerId("wf-1", "workbench-dev");
        verify(repository).delete(existing);
    }

    @Test
    void rejectsDeleteWhenRunHistoryExists() throws Exception {
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        when(repository.findByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(Optional.of(existing));
        when(runRecordRepository.existsByDefinitionIdAndOwnerId("wf-1", "workbench-dev")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("wf-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_DEFINITION_IN_USE"))
                .hasMessage("Workflow definition has run history and cannot be deleted: wf-1");
        verify(revisionRepository, never()).deleteAllByDefinitionIdAndOwnerId("wf-1", "workbench-dev");
        verify(repository, never()).delete(existing);
    }

    private WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm", "llm", Map.of("prompt", "Answer {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm"),
                        new WorkflowEdge("llm", "end")));
    }

    private WorkflowDefinition validDefinitionWithToolNode() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool"),
                        new WorkflowEdge("tool", "end")));
    }

    private WorkflowDefinitionService governedService(WorkflowGovernanceOrchestrator orchestrator,
            TransactionOperations transactions) {
        return new WorkflowDefinitionService(
                repository,
                revisionRepository,
                compiler,
                new JsonPayloadCodec(new ObjectMapper()),
                runRecordRepository,
                workflowRuntimeProperties,
                new PublicIdGenerator(),
                new WorkflowStructuredOutputAutoconfigurer(),
                orchestrator,
                transactions);
    }

    private WorkflowGovernanceEvaluationResponse governanceResponse(WorkflowGenerationStatus status,
            WorkflowDefinition definition) {
        return new WorkflowGovernanceEvaluationResponse(
                status,
                definition,
                new WorkflowGovernanceReport(List.of()),
                new WorkflowEvaluationReport(Map.of(), List.of()),
                List.of(),
                List.of(new WorkflowActiveRulePack("core", "1.0.0")));
    }

    @Test
    void resolveDefinitionRejectsDraftWhenPublishGuardEnabled() {
        workflowRuntimeProperties.setRequirePublishedForRun(true);
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity("def-1", "Draft Flow", "desc", "{}");
        entity.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("def-1", "workbench-dev")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resolveDefinition("def-1", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("WORKFLOW_DEFINITION_NOT_PUBLISHED");
    }

    @Test
    void resolveDefinitionAllowsDraftWhenPublishGuardDisabled() {
        workflowRuntimeProperties.setRequirePublishedForRun(false);
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity("def-1", "Draft Flow", "desc",
                "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\",\"config\":{}},{\"id\":\"end\",\"type\":\"end\",\"config\":{}}],"
                        + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}");
        entity.prePersist();
        when(repository.findByDefinitionIdAndOwnerId("def-1", "workbench-dev")).thenReturn(Optional.of(entity));

        WorkflowDefinitionResolution resolution = service.resolveDefinition("def-1", null);

        assertThat(resolution.definitionId()).isEqualTo("def-1");
    }

}
