package com.example.agentdemo.app;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.app.dto.AppChatRequest;
import com.example.agentdemo.app.dto.AppChatResponse;
import com.example.agentdemo.app.dto.AppResponse;
import com.example.agentdemo.app.dto.AppRunResultResponse;
import com.example.agentdemo.app.dto.CreateAppRequest;
import com.example.agentdemo.app.dto.UpdateAppRequest;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.TokenUsage;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.knowledge.KnowledgeBaseService;
import com.example.agentdemo.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.agentdemo.knowledge.dto.TextDocumentRequest;
import org.mockito.ArgumentCaptor;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.usage.UsageRecordingService;
import com.example.agentdemo.usage.UsageSummaryResponse;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowDefinitionResponse;
import com.example.agentdemo.workflow.WorkflowDefinitionSaveRequest;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level tests for the P0-3 app product model: CRUD, publish (with immutable revision +
 * pinned workflow), rollback, running a published WORKFLOW app, the published-run gate, and
 * archive-on-delete when run history exists.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.workflow.runtime=simple",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_app_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
class AppLifecycleIntegrationTest {

    @Autowired
    private AppService appService;

    @Autowired
    private AppRuntimeService appRuntimeService;

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private UsageRecordingService usageRecordingService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @MockBean
    private AiModelService aiModelService;

    @Test
    void createReadUpdateApp() {
        AppResponse created = appService.create(new CreateAppRequest("Support Chat", "helps", AppType.CHAT,
                new AppConfig("You are support", "qwen-plus", true, 10), null, null));

        assertThat(created.appId()).startsWith("app-");
        assertThat(created.status()).isEqualTo(AppStatus.DRAFT);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.config().systemPrompt()).isEqualTo("You are support");

        AppResponse fetched = appService.get(created.appId());
        assertThat(fetched.name()).isEqualTo("Support Chat");

        AppResponse updated = appService.update(created.appId(),
                new UpdateAppRequest("Support Chat v2", "helps more", new AppConfig("New prompt", null, false, null),
                        null, null));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.status()).isEqualTo(AppStatus.DRAFT);
        assertThat(updated.config().systemPrompt()).isEqualTo("New prompt");
        assertThat(appService.list(0, 20).content()).extracting(AppResponse::appId).contains(created.appId());
    }

    @Test
    void publishWorkflowAppPinsPublishedWorkflowAndRuns() {
        String workflowId = publishedWorkflow();
        AppResponse app = appService.create(new CreateAppRequest("Flow App", null, AppType.WORKFLOW, null,
                workflowId, null));

        AppResponse published = appService.publish(app.appId());
        assertThat(published.status()).isEqualTo(AppStatus.PUBLISHED);
        assertThat(published.publishedVersion()).isEqualTo(1);
        assertThat(published.workflowDefinitionVersion()).isEqualTo(1);

        AppRunResultResponse result = appRuntimeService.run(published.appId(), null);
        assertThat(result.runId()).isNotBlank();
        assertThat(result.workflowDefinitionId()).isEqualTo(workflowId);
        assertThat(result.workflowDefinitionVersion()).isEqualTo(1);

        RunEntity run = runRepository.findById(result.runId()).orElseThrow();
        assertThat(run.getAppId()).isEqualTo(published.appId());
    }

    @Test
    void publishFailsWhenBoundWorkflowIsNotPublished() {
        WorkflowDefinitionResponse draft = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Draft Flow", null, simpleWorkflow()));
        AppResponse app = appService.create(new CreateAppRequest("Flow App", null, AppType.WORKFLOW, null,
                draft.definitionId(), null));

        assertThatThrownBy(() -> appService.publish(app.appId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_DEFINITION_NOT_PUBLISHED"));
    }

    @Test
    void unpublishedAppCannotBeRunWhenPublishedRunRequired() {
        String workflowId = publishedWorkflow();
        AppResponse app = appService.create(new CreateAppRequest("Flow App", null, AppType.WORKFLOW, null,
                workflowId, null));

        assertThatThrownBy(() -> appRuntimeService.run(app.appId(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("APP_NOT_PUBLISHED"));
    }

    @Test
    void rollbackRestoresEarlierSnapshotAsNewDraft() {
        AppResponse created = appService.create(new CreateAppRequest("Chat", null, AppType.CHAT,
                new AppConfig("v1 prompt", null, null, null), null, null));
        appService.update(created.appId(),
                new UpdateAppRequest("Chat", null, new AppConfig("v2 prompt", null, null, null), null, null));

        AppResponse rolledBack = appService.rollback(created.appId(), 1);

        assertThat(rolledBack.version()).isEqualTo(3);
        assertThat(rolledBack.status()).isEqualTo(AppStatus.DRAFT);
        assertThat(rolledBack.config().systemPrompt()).isEqualTo("v1 prompt");
    }

    @Test
    void deleteArchivesAppWithRunHistory() {
        String workflowId = publishedWorkflow();
        AppResponse app = appService.publish(appService.create(
                new CreateAppRequest("Flow App", null, AppType.WORKFLOW, null, workflowId, null)).appId());
        appRuntimeService.run(app.appId(), null);

        appService.delete(app.appId());

        assertThat(appService.get(app.appId()).status()).isEqualTo(AppStatus.ARCHIVED);
    }

    @Test
    void chatAppUsesConfiguredSystemPromptAndModelAndRecordsUsage() {
        Mockito.when(aiModelService.generate(Mockito.eq("You are a pirate"), Mockito.anyList(), Mockito.anyString(),
                        Mockito.eq("qwen-max")))
                .thenReturn(AiModelResult.ok("Arr!", new TokenUsage("dashscope", "qwen-max", 12, 8, 20, null)));
        AppResponse app = appService.publish(appService.create(new CreateAppRequest("Pirate", null, AppType.CHAT,
                new AppConfig("You are a pirate", "qwen-max", true, null), null, null)).appId());

        AppChatResponse response = appRuntimeService.chat(app.appId(), new AppChatRequest(null, "hi"));

        assertThat(response.answer()).isEqualTo("Arr!");
        assertThat(response.appId()).isEqualTo(app.appId());
        RunEntity run = runRepository.findById(response.runId()).orElseThrow();
        assertThat(run.getAppId()).isEqualTo(app.appId());

        UsageSummaryResponse usage = usageRecordingService.summarize(response.runId());
        assertThat(usage.totalTokens()).isEqualTo(20);
        assertThat(usage.promptTokens()).isEqualTo(12);
        assertThat(usage.completionTokens()).isEqualTo(8);
        assertThat(usage.model()).isEqualTo("qwen-max");
        assertThat(usage.calls()).isEqualTo(1);
    }

    @Test
    void chatAppWithBoundKnowledgeBaseRetrievesCitationsAndAugmentsPrompt() {
        Mockito.when(aiModelService.generate(Mockito.anyString(), Mockito.anyList(), Mockito.anyString(),
                Mockito.any())).thenReturn(AiModelResult.ok("done", null));
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Policies", null, null)).kbId();
        knowledgeBaseService.addTextDocument(kbId,
                new TextDocumentRequest("Returns", "Our returns policy allows refunds within 30 days."));
        AppResponse app = appService.publish(appService.create(new CreateAppRequest("KB Chat", null, AppType.CHAT,
                new AppConfig("You help", null, true, null, List.of(kbId)), null, null)).appId());

        AppChatResponse response = appRuntimeService.chat(app.appId(),
                new AppChatRequest(null, "returns refund policy"));

        assertThat(response.citations()).isNotEmpty();
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(aiModelService).generate(Mockito.anyString(), Mockito.anyList(), prompt.capture(),
                Mockito.any());
        assertThat(prompt.getValue()).contains("Retrieved knowledge base context", "returns policy");
    }

    private String publishedWorkflow() {
        WorkflowDefinitionResponse saved = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Flow", null, simpleWorkflow()));
        workflowDefinitionService.publish(saved.definitionId());
        return saved.definitionId();
    }

    private WorkflowDefinition simpleWorkflow() {
        return new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

}
