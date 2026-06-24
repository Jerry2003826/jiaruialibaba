package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDefinitionServiceTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowDefinitionRevisionRepository revisionRepository = mock(WorkflowDefinitionRevisionRepository.class);
    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final WorkflowDefinitionService service = new WorkflowDefinitionService(repository, revisionRepository, compiler,
            new ObjectMapper());

    @Test
    void savesWorkflowDefinitionAfterCompilerValidation() {
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
    void rejectsInvalidWorkflowDefinitionBeforeSaving() {
        WorkflowDefinition invalid = new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of("unknown", true)),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));

        assertThatThrownBy(() -> service.save(new WorkflowDefinitionSaveRequest("Bad", null, invalid)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported config key");
    }

    @Test
    void resolvesStoredDefinitionById() throws Exception {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(definition));
        when(repository.findByDefinitionId("wf-1")).thenReturn(Optional.of(entity));

        WorkflowDefinition resolved = service.resolveDefinition("wf-1");

        assertThat(resolved).isEqualTo(definition);
    }

    @Test
    void throwsWhenDefinitionIdIsMissing() {
        when(repository.findByDefinitionId("missing")).thenReturn(Optional.empty());

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
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(second, first));

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
        when(repository.findByDefinitionId("wf-1")).thenReturn(Optional.of(existing));
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
    void publishesStoredDefinitionWithoutChangingVersion() throws Exception {
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        WorkflowDefinitionRevisionEntity currentRevision = new WorkflowDefinitionRevisionEntity("wf-1", 1,
                WorkflowDefinitionStatus.DRAFT, "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        existing.prePersist();
        when(repository.findByDefinitionId("wf-1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisionRepository.findByDefinitionIdAndVersion("wf-1", 1)).thenReturn(Optional.of(currentRevision));

        WorkflowDefinitionResponse response = service.publish("wf-1");

        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(WorkflowDefinitionStatus.PUBLISHED);
        assertThat(currentRevision.getStatus()).isEqualTo(WorkflowDefinitionStatus.PUBLISHED);
        verify(repository).save(existing);
        verify(revisionRepository).save(currentRevision);
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
        when(repository.findByDefinitionId("wf-1")).thenReturn(Optional.of(current));
        when(revisionRepository.findAllByDefinitionIdOrderByVersionDesc("wf-1"))
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
        when(repository.findByDefinitionId("wf-1")).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisionRepository.findByDefinitionIdAndVersion("wf-1", 1)).thenReturn(Optional.of(targetRevision));

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
    void throwsWhenRollbackRevisionIsMissing() throws Exception {
        WorkflowDefinitionEntity current = new WorkflowDefinitionEntity("wf-1", "Support Bot", null,
                new ObjectMapper().writeValueAsString(validDefinition()));
        when(repository.findByDefinitionId("wf-1")).thenReturn(Optional.of(current));
        when(revisionRepository.findByDefinitionIdAndVersion("wf-1", 99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback("wf-1", 99))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_REVISION_NOT_FOUND"))
                .hasMessage("Workflow definition revision not found: wf-1:99");
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

}
