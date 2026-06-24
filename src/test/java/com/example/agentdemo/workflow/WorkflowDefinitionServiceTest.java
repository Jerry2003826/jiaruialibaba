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
    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final WorkflowDefinitionService service = new WorkflowDefinitionService(repository, compiler,
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
        assertThat(response.workflowDefinition()).isEqualTo(definition);

        ArgumentCaptor<WorkflowDefinitionEntity> entityCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getDefinitionJson()).contains("\"nodes\"");
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

}
