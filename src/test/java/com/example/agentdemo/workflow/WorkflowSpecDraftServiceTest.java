package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSpecDraftServiceTest {

    @Test
    void automaticallyRepairsInvalidJsonBeforeReturningTheSpecification() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        String invalidJson = """
                {
                  "status": "READY"
                  "summary": "深度研究工作流"
                }
                """;
        String repairedJson = """
                {
                  "status": "READY",
                  "summary": "深度研究工作流",
                  "questions": [],
                  "spec": {
                    "domain": "deep-research",
                    "goal": "产出有来源的深度研究报告",
                    "requiredCapabilities": ["web-search", "report-generation"],
                    "outputAudience": "外部客户高管",
                    "testCases": ["调研一个公开市场"]
                  },
                  "generationPrompt": "按已确认的深度研究规格生成工作流"
                }
                """;
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok(invalidJson));
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("第 1 次自动修复")))
                .thenReturn(AiModelResult.ok(repairedJson));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest("创建深度研究工作流"));

        assertThat(response.status()).isEqualTo(WorkflowSpecDraftResponse.Status.READY);
        assertThat(response.spec()).containsEntry("domain", "deep-research");
        assertThat(response.generationPrompt()).contains("深度研究规格");
        verify(aiModelService, times(2)).generate(contains("Workflow Spec Gate"), org.mockito.ArgumentMatchers.anyString());
        verify(aiModelService).generate(contains("Workflow Spec Gate"),
                org.mockito.ArgumentMatchers.argThat(prompt -> prompt.contains(invalidJson.trim())
                        && prompt.contains("Unexpected character")));
    }

    @Test
    void fallsBackToAnEditableConservativeSpecAfterAllJsonRepairsFail() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(AiModelResult.ok("{\"status\":\"READY\" \"summary\":\"损坏的规格\"}"));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest(
                "为投资委员会生成带来源的深度研究报告"));

        assertThat(response.status()).isEqualTo(WorkflowSpecDraftResponse.Status.READY);
        assertThat(response.summary()).contains("保守规格");
        assertThat(response.spec())
                .containsEntry("goal", "为投资委员会生成带来源的深度研究报告")
                .containsEntry("requiredCapabilities", java.util.List.of())
                .containsEntry("testCases", java.util.List.of());
        assertThat(response.generationPrompt())
                .contains("只使用平台现有节点")
                .contains("为投资委员会生成带来源的深度研究报告");
        verify(aiModelService, times(3)).generate(contains("Workflow Spec Gate"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void returnsClarifyingQuestionsWhenRequirementIsUnderspecified() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "status": "NEEDS_CLARIFICATION",
                          "summary": "客户评价自动分流",
                          "questions": [
                            "正面评价是只生成营销通知，还是要调用营销系统接口？",
                            "售后问题是生成工单文本，还是要创建真实工单？"
                          ],
                          "clarifications": [
                            {
                              "question": "正面评价是只生成营销通知，还是要调用营销系统接口？",
                              "options": ["只生成内部营销通知", "调用营销系统接口", "先生成草稿，由人工确认"],
                              "freeformPrompt": "可补充品牌营销部门接收方式"
                            },
                            {
                              "question": "售后问题是生成工单文本，还是要创建真实工单？",
                              "options": ["只生成工单文本", "调用售后工单系统", "先提醒客服人工创建"],
                              "freeformPrompt": "可补充售后系统名称或工单字段"
                            }
                          ],
                          "spec": {
                            "goal": "根据客户评价进行自动分流",
                            "knownInputs": ["客户评论 message"],
                            "missingDecisions": ["部门动作方式", "外部系统边界"]
                          },
                          "generationPrompt": ""
                        }
                        """));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest("创建客户评价系统"));

        assertThat(response.status()).isEqualTo(WorkflowSpecDraftResponse.Status.NEEDS_CLARIFICATION);
        assertThat(response.summary()).isEqualTo("客户评价自动分流");
        assertThat(response.questions()).containsExactly(
                "正面评价是只生成营销通知，还是要调用营销系统接口？",
                "售后问题是生成工单文本，还是要创建真实工单？");
        assertThat(response.clarifications()).hasSize(2);
        assertThat(response.clarifications().get(0).options()).containsExactly(
                "只生成内部营销通知", "调用营销系统接口", "先生成草稿，由人工确认");
        assertThat(response.clarifications().get(0).freeformPrompt()).isEqualTo("可补充品牌营销部门接收方式");
        assertThat(response.spec()).containsEntry("goal", "根据客户评价进行自动分流");
        assertThat(response.generationPrompt()).isBlank();
        verify(aiModelService).generate(contains("Workflow Spec Gate"), contains("创建客户评价系统"));
    }

    @Test
    void returnsLockedGenerationPromptWhenRequirementIsReady() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "status": "READY",
                          "summary": "客户评价自动分流",
                          "questions": [],
                          "spec": {
                            "domain": "customer-service-ecommerce",
                            "goal": "输入客户评论后自动判断正负面并分流",
                            "inputs": [{"name": "message", "label": "客户评论"}],
                            "requiredCapabilities": ["sentiment-classification", "issue-routing"],
                            "outputAudience": "品牌营销、售后、运输和产品体验部门",
                            "routingRules": [
                              "positive -> 品牌营销通知",
                              "negative + after_sales -> 售后工单",
                              "negative + shipping -> 运输和产品体验通知"
                            ],
                            "failurePolicy": "不确定时进入 other 分支",
                            "testCases": ["这个产品很好用", "包装破损，物流很慢"]
                          },
                          "generationPrompt": "按以下锁定规格生成工作流：输入 message，先判断 sentiment，再判断 issueType。"
                        }
                        """));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest(
                "创建客户评价系统，当前版本只生成内部文案，不调用外部接口"));

        assertThat(response.status()).isEqualTo(WorkflowSpecDraftResponse.Status.READY);
        assertThat(response.questions()).isEmpty();
        assertThat(response.generationPrompt())
                .contains("按以下锁定规格生成工作流")
                .contains("sentiment")
                .contains("issueType");
        assertThat(response.spec()).containsEntry("failurePolicy", "不确定时进入 other 分支");
        assertThat(response.spec())
                .containsEntry("domain", "customer-service-ecommerce")
                .containsEntry("outputAudience", "品牌营销、售后、运输和产品体验部门");
        assertThat(response.spec().get("requiredCapabilities"))
                .isEqualTo(java.util.List.of("sentiment-classification", "issue-routing"));
        verify(aiModelService).generate(contains("\"requiredCapabilities\""), contains("创建客户评价系统"));
    }

    @Test
    void downgradesExternalApiSpecToClarificationWhenIntegrationContractIsIncomplete() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "status": "READY",
                          "summary": "发送订单到外部 API",
                          "questions": [],
                          "clarifications": [],
                          "spec": {
                            "goal": "调用外部 API 创建订单",
                            "failurePolicy": ""
                          },
                          "generationPrompt": "生成调用接口的工作流"
                        }
                        """));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest(
                "调用外部 API 创建订单"));

        assertThat(response.status()).isEqualTo(WorkflowSpecDraftResponse.Status.NEEDS_CLARIFICATION);
        assertThat(response.questions()).hasSize(5);
        assertThat(String.join(" ", response.questions()))
                .contains("接口地址", "请求方法", "鉴权", "请求字段", "响应", "失败");
        assertThat(response.clarifications())
                .allSatisfy(item -> {
                    assertThat(item.options()).hasSizeBetween(2, 3);
                    assertThat(item.freeformPrompt()).isNotBlank();
                });
        assertThat(response.generationPrompt()).isBlank();
    }

    @Test
    void keepsCompleteExternalApiContractReady() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "status": "READY",
                          "summary": "发送订单到外部 API",
                          "questions": [],
                          "clarifications": [],
                          "spec": {
                            "goal": "调用外部 API 创建订单",
                            "integrations": [{
                              "url": "https://orders.company.com/api/orders",
                              "method": "POST",
                              "authentication": "managed bearer credential",
                              "requestFields": ["orderId", "message"],
                              "responseUsage": "读取 json.ticketId 并输出",
                              "failurePolicy": "4xx/5xx 进入人工处理分支"
                            }],
                            "failurePolicy": "4xx/5xx 进入人工处理分支"
                          },
                          "generationPrompt": "按确认的 API 合同生成工作流"
                        }
                        """));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest(
                "调用外部 API 创建订单"));

        assertThat(response.status()).isEqualTo(WorkflowSpecDraftResponse.Status.READY);
        assertThat(response.questions()).isEmpty();
    }

    @Test
    void normalizesNewLockedSpecFieldsForOlderModelResponses() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "status": "READY",
                          "summary": "简单问答",
                          "questions": [],
                          "spec": {"goal": "回答用户问题"},
                          "generationPrompt": "按锁定规格生成简单问答工作流"
                        }
                        """));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest("创建简单问答"));

        assertThat(response.spec())
                .containsEntry("domain", "")
                .containsEntry("requiredCapabilities", java.util.List.of())
                .containsEntry("outputAudience", "")
                .containsEntry("testCases", java.util.List.of());
    }

    @Test
    void normalizesExplicitNullLockedSpecFieldsToStableTypes() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowSpecDraftService service = new WorkflowSpecDraftService(aiModelService, new ObjectMapper());
        when(aiModelService.generate(contains("Workflow Spec Gate"), contains("用户原始需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "status": "READY",
                          "summary": "简单问答",
                          "questions": [],
                          "spec": {
                            "domain": null,
                            "requiredCapabilities": null,
                            "outputAudience": null,
                            "testCases": null
                          },
                          "generationPrompt": "按锁定规格生成简单问答工作流"
                        }
                        """));

        WorkflowSpecDraftResponse response = service.draft(new WorkflowSpecDraftRequest("创建简单问答"));

        assertThat(response.spec())
                .containsEntry("domain", "")
                .containsEntry("requiredCapabilities", java.util.List.of())
                .containsEntry("outputAudience", "")
                .containsEntry("testCases", java.util.List.of());
        assertThat(response.spec().get("requiredCapabilities")).isInstanceOf(java.util.List.class);
        assertThat(response.spec().get("testCases")).isInstanceOf(java.util.List.class);
    }

}
