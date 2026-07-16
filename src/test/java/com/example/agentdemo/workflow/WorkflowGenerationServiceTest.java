package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContext;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContextService;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCase;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseKind;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseResult;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseStatus;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationErrorOrigin;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationService;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceFinding;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceService;
import com.example.agentdemo.workflow.governance.WorkflowRuleCatalog;
import com.example.agentdemo.workflow.governance.WorkflowRulePack;
import com.example.agentdemo.workflow.http.HttpCredentialResponse;
import com.example.agentdemo.workflow.http.HttpCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowGenerationServiceTest {

    @Test
    void generationFailsClosedBeforeCallingTheModelWhenGovernanceIsNotConfigured() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService service = new WorkflowGenerationService(
                aiModelService, new ObjectMapper(), new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));

        assertThatThrownBy(() -> service.generate(new WorkflowGenerationRequest("创建客服工作流")))
                .isInstanceOfSatisfying(com.example.agentdemo.common.BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("WORKFLOW_GOVERNANCE_NOT_CONFIGURED"));
        verifyNoInteractions(aiModelService);
    }

    @Test
    void generationUsesCompleteLockedSpecForContextWhileKeepingBusinessInstruction() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        JsonNode lockedSpec = new ObjectMapper().readTree("""
                {"goal":"route reviews","domain":"customer-service-ecommerce","requiredCapabilities":["sentiment"]}
                """);
        when(contextService.build(eq(null), argThat(value -> value.contains("route reviews")
                        && value.contains("requiredCapabilities")), eq(null)))
                .thenReturn(mockBuilderContext("LOCKED_SPEC_CONTEXT"));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("规格贯穿", "物流太慢")));
        WorkflowGenerationService service = serviceWithContext(aiModelService, contextService);

        WorkflowGenerationResponse response = service.generate(
                new WorkflowGenerationRequest("请生成客户评价分流", lockedSpec));

        assertThat(response.lockedSpec()).isEqualTo(lockedSpec);
        verify(aiModelService).generate(anyString(), argThat(message ->
                message.contains("请生成客户评价分流") && message.contains("LOCKED_SPEC_CONTEXT")));
    }

    @Test
    void editAndRepairReuseStringLockedSpecAndReturnIt() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        JsonNode lockedSpec = new ObjectMapper().getNodeFactory().textNode("customer support locked specification");
        when(contextService.build(eq(null), eq("customer support locked specification"), any()))
                .thenReturn(mockBuilderContext("STRING_LOCKED_SPEC_CONTEXT"));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("编辑规格", "我要退货")),
                        AiModelResult.ok(generatedSmokeWorkflow("修复规格", "我要退货")));
        WorkflowGenerationService service = serviceWithContext(aiModelService, contextService);

        WorkflowGenerationResponse edited = service.edit(new WorkflowEditRequest(
                "增加退货处理", "客服流程", "订单售后", linearDefinition(), lockedSpec));
        WorkflowGenerationResponse repaired = service.repair(new WorkflowRepairRequest(
                "修复退货处理", "条件错误", "客服流程", "订单售后", linearDefinition(), lockedSpec));

        assertThat(edited.lockedSpec()).isEqualTo(lockedSpec);
        assertThat(repaired.lockedSpec()).isEqualTo(lockedSpec);
        verify(aiModelService).generate(anyString(), argThat(message -> message.contains("增加退货处理")));
        verify(aiModelService).generate(anyString(), argThat(message -> message.contains("修复退货处理")));
    }

    @Test
    void generationPromptIncludesDynamicBuilderContextWithoutGetCurrentTimeFallback() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        when(contextService.build(eq(null), contains("客服订单物流"), eq(null)))
                .thenReturn(mockBuilderContext("GENERATION_CONTEXT"));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("动态上下文", "订单 123 的物流在哪里")));
        WorkflowGenerationService aiService = testService(aiModelService, null, null, contextService);

        aiService.generate(new WorkflowGenerationRequest("创建客服订单物流工作流"));

        verify(aiModelService).generate(argThat(systemPrompt ->
                        !systemPrompt.contains("不确定工具名时，优先使用 getCurrentTime")
                                && systemPrompt.contains("缺少所需能力")
                                && systemPrompt.contains("- http_request:")
                                && systemPrompt.contains("- report_export:")
                                && systemPrompt.contains("- custom:")
                                && systemPrompt.contains("custom 不能访问网络、执行代码")
                                && systemPrompt.contains("Base64")
                                && systemPrompt.contains("- variable_aggregator:")),
                argThat(userPrompt -> userPrompt.contains("GENERATION_CONTEXT")
                        && userPrompt.contains("UNTRUSTED_BUILDER_KNOWLEDGE")));
    }

    @Test
    void generationReceivesOnlySafeHttpCredentialMetadata() {
        AiModelService aiModelService = mock(AiModelService.class);
        HttpCredentialService credentialService = mock(HttpCredentialService.class);
        when(credentialService.list()).thenReturn(List.of(new HttpCredentialResponse(
                "cred_orders", "正式订单接口", "bearer", true,
                Instant.parse("2026-07-15T00:00:00Z"), Instant.parse("2026-07-15T00:00:00Z"))));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("订单接口", "创建订单")));
        WorkflowGenerationService service = testService(
                aiModelService, null, null, null, credentialService);

        service.generate(new WorkflowGenerationRequest("调用已配置的正式订单接口"));

        verify(aiModelService).generate(anyString(), argThat(userPrompt ->
                userPrompt.contains("cred_orders | 正式订单接口 | bearer")
                        && userPrompt.contains("不含密钥")));
    }

    @Test
    void editPromptIncludesDynamicBuilderContext() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        when(contextService.build(eq(null), contains("工作流编辑任务"), eq(null)))
                .thenReturn(mockBuilderContext("EDIT_CONTEXT"));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("编辑上下文", "我要退货")));
        WorkflowGenerationService aiService = testService(aiModelService, null, null, contextService);

        aiService.edit(new WorkflowEditRequest(
                "增加退货处理",
                "客服流程",
                "订单售后",
                linearDefinition()));

        verify(aiModelService).generate(anyString(), contains("EDIT_CONTEXT"));
    }

    @Test
    void repairPromptIncludesDynamicBuilderContextAndPreviousFailure() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        when(contextService.build(eq(null), contains("工作流修复任务"), eq("物流工具不存在")))
                .thenReturn(mockBuilderContext("REPAIR_CONTEXT"));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("修复上下文", "订单 123")));
        WorkflowGenerationService aiService = testService(aiModelService, null, null, contextService);

        aiService.repair(new WorkflowRepairRequest(
                "修复订单物流查询",
                "物流工具不存在",
                "客服流程",
                "订单售后",
                linearDefinition()));

        verify(contextService).build(eq(null), contains("工作流修复任务"), eq("物流工具不存在"));
        verify(aiModelService).generate(anyString(), contains("REPAIR_CONTEXT"));
    }

    @Test
    void streamingGenerationPreservesCompleteBuilderContextBoundaries() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        when(contextService.build(eq(null), contains("流式客服生成"), eq(null)))
                .thenReturn(boundedBuilderContext("STREAM_GENERATION_CONTEXT"));
        stubStreamingWorkflow(aiModelService, generatedSmokeWorkflow("流式生成上下文", "查询订单"));
        WorkflowGenerationService service = serviceWithContext(aiModelService, contextService);

        service.generateStreaming(new WorkflowGenerationRequest("流式客服生成"), (event, data) -> {
        });

        verify(aiModelService).streamUntilComplete(anyString(), argThat(message -> hasPreservedBoundaries(
                message, "STREAM_GENERATION_CONTEXT")), any(), any());
    }

    @Test
    void streamingEditPreservesCompleteBuilderContextBoundaries() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        when(contextService.build(eq(null), contains("工作流编辑任务"), eq(null)))
                .thenReturn(boundedBuilderContext("STREAM_EDIT_CONTEXT"));
        stubStreamingWorkflow(aiModelService, generatedSmokeWorkflow("流式编辑上下文", "我要退货"));
        WorkflowGenerationService service = serviceWithContext(aiModelService, contextService);

        service.editStreaming(new WorkflowEditRequest(
                "增加退货处理", "客服流程", "订单售后", linearDefinition()), (event, data) -> {
                });

        verify(aiModelService).streamUntilComplete(anyString(), argThat(message -> hasPreservedBoundaries(
                message, "STREAM_EDIT_CONTEXT")), any(), any());
    }

    @Test
    void streamingRepairPreservesCompleteBuilderContextBoundariesAndInitialFailure() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        when(contextService.build(eq(null), contains("工作流修复任务"), eq("物流工具不存在")))
                .thenReturn(boundedBuilderContext("STREAM_REPAIR_CONTEXT"));
        stubStreamingWorkflow(aiModelService, generatedSmokeWorkflow("流式修复上下文", "订单 123"));
        WorkflowGenerationService service = serviceWithContext(aiModelService, contextService);

        service.repairStreaming(new WorkflowRepairRequest(
                "修复订单物流查询", "物流工具不存在", "客服流程", "订单售后", linearDefinition()),
                (event, data) -> {
                });

        verify(contextService).build(eq(null), contains("工作流修复任务"), eq("物流工具不存在"));
        verify(aiModelService).streamUntilComplete(anyString(), argThat(message -> hasPreservedBoundaries(
                message, "STREAM_REPAIR_CONTEXT")), any(), any());
    }

    @Test
    void streamingGenerationKeepsCompleteJsonWhenProviderOmitsCompletionSignal() {
        AiModelService aiModelService = mock(AiModelService.class);
        String completeWorkflow = generatedSmokeWorkflow("流式完整结果", "查询退货政策");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(2);
            onChunk.accept(completeWorkflow);
            java.util.function.BooleanSupplier completion = invocation.getArgument(3);
            assertThat(completion.getAsBoolean()).isTrue();
            return null;
        }).when(aiModelService).streamUntilComplete(anyString(), anyString(), any(), any());
        WorkflowGenerationService service = testService(aiModelService);

        WorkflowGenerationResponse response = service.generateStreaming(
                new WorkflowGenerationRequest("创建知识库客服工作流"), (event, data) -> {
                });

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.name()).isEqualTo("流式完整结果");
        assertThat(response.testResults()).hasSize(1);
    }

    @Test
    void usesAiGeneratedWorkflowWhenModelReturnsValidJson() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        when(aiModelService.generate(contains("工作流编排器"), contains("用户需求")))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "AI 自主编排",
                          "description": "AI designed",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","config":{}},
                              {"id":"classify","type":"llm","config":{"prompt":"Classify {{input}}"}},
                              {"id":"answer","type":"llm","config":{"prompt":"Answer using {{lastOutput}} and {{input}}"}},
                              {"id":"end","type":"end","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"classify"},
                              {"from":"classify","to":"answer"},
                              {"from":"answer","to":"end"}
                            ]
                          },
                          "notes": ["选择两步 LLM 编排"]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("先分类再回答"));

        assertThat(response.name()).isEqualTo("AI 自主编排");
        assertThat(response.notes()).containsExactly("选择两步 LLM 编排");
        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::id)
                .containsExactly("start", "classify", "answer", "end");
        verify(aiModelService).generate(contains("工作流编排器"), contains("用户需求"));
    }

    @Test
    void streamsAiGeneratedWorkflowChunksBeforeReturningDefinition() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<String> onChunk = invocation.getArgument(2);
                    onChunk.accept("""
                            {
                              "name": "流式 AI 编排",
                              "description": "streamed",
                              "workflowDefinition": {
                                "nodes": [
                                  {"id":"start","type":"start","config":{}},
                                  {"id":"llm_1","type":"llm","config":{"prompt":"Answer {{input}}"}},
                            """);
                    onChunk.accept("""
                                  {"id":"end","type":"end","config":{}}
                                ],
                                "edges": [
                                  {"from":"start","to":"llm_1"},
                                  {"from":"llm_1","to":"end"}
                                ]
                              },
                              "notes": ["流式输出"]
                            }
                            """);
                    return null;
                }).when(aiModelService).streamUntilComplete(
                        contains("工作流编排器"), contains("用户需求"), any(), any());
        List<String> events = new ArrayList<>();
        List<String> deltas = new ArrayList<>();

        WorkflowGenerationResponse response = aiService.generateStreaming(new WorkflowGenerationRequest("直接回答"),
                (event, data) -> {
                    events.add(event);
                    Object delta = data.get("delta");
                    if (delta != null) {
                        deltas.add(String.valueOf(delta));
                    }
                });

        assertThat(response.name()).isEqualTo("流式 AI 编排");
        assertThat(response.notes()).containsExactly("流式输出");
        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::type)
                .containsExactly("start", "llm", "end");
        assertThat(events).contains("status", "message");
        assertThat(String.join("", deltas)).contains("流式 AI 编排").contains("\"edges\"");
        verify(aiModelService).streamUntilComplete(
                contains("工作流编排器"), contains("用户需求"), any(), any());
    }

    @Test
    void returnsStructuredBlockedWhenInvalidJsonAndRepairsAllFail() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.generate(contains("工作流编排器"), contains("用户需求")))
                .thenReturn(AiModelResult.ok("我无法返回 JSON"));

        WorkflowGenerationResponse response = aiService.generate(
                new WorkflowGenerationRequest("先检索知识库，再调用计算工具"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.workflowDefinition()).isNull();
        assertThat(response.repairAttempts()).isEqualTo(2);
        assertThat(response.governanceReport().blockers()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("core-workflow-validity");
            assertThat(finding.message()).contains("模型没有返回 JSON 对象");
        });
        verify(aiModelService, times(3)).generate(anyString(), anyString());
    }

    @Test
    void keepsLastEditableCandidateWhenLaterRepairResponsesAreInvalidJson() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("CANDIDATE_FALLBACK_CONTEXT");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context)))
                .thenReturn(blockedGovernanceReport("core-design-check", "llm_1"));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("可编辑候选蓝图", "测试")),
                        AiModelResult.ok("第一次修复没有返回 JSON"),
                        AiModelResult.ok("第二次修复仍然没有返回 JSON"));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建可编辑工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.name()).isEqualTo("可编辑候选蓝图");
        assertThat(response.workflowDefinition()).isNotNull();
        assertThat(response.repairAttempts()).isEqualTo(2);
        assertThat(response.notes()).anyMatch(note -> note.contains("模型没有返回 JSON 对象"));
        verify(aiModelService, times(3)).generate(anyString(), anyString());
    }

    @Test
    void repairsAiWorkflowWhenFirstTopologyFailsValidation() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "非法汇合流程",
                          "description": "first attempt",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","config":{}},
                              {"id":"join","type":"join","config":{}},
                              {"id":"end","type":"end","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"join"},
                              {"from":"join","to":"end"}
                            ]
                          },
                          "notes": ["第一次尝试"]
                        }
                        """), AiModelResult.ok("""
                        {
                          "name": "修复后的问答流程",
                          "description": "repaired attempt",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","config":{}},
                              {"id":"llm_1","type":"llm","config":{"prompt":"Answer {{input}} clearly"}},
                              {"id":"end","type":"end","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_1"},
                              {"from":"llm_1","to":"end"}
                            ]
                          },
                          "notes": [
                            "诊断结论：非法汇合节点导致拓扑校验失败",
                            "修复计划：改为 start、llm、end 线性流程",
                            "实际改动：删除非法汇合并补齐线性连线",
                            "验证样例：真实输入已通过自动运行"
                          ]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("直接回答用户问题"));

        assertThat(response.name()).isEqualTo("修复后的问答流程");
        assertThat(response.notes()).hasSize(4);
        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::type)
                .containsExactly("start", "llm", "end");
        verify(aiModelService).generate(contains("工作流编排器"), contains("校验错误"));
    }

    @Test
    void repairsAiWorkflowWhenFirstTemplateVariableIsUnsupported() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "变量错误流程",
                          "description": "first attempt",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","config":{}},
                              {"id":"llm_judge","type":"llm","config":{"prompt":"Return true or false for {{input}}"}},
                              {"id":"condition_1","type":"condition","config":{"left":"{{llm_judge.output}}","operator":"equals","right":"true"}},
                              {"id":"llm_true","type":"llm","config":{"prompt":"Answer with context {{context}}"}},
                              {"id":"llm_false","type":"llm","config":{"prompt":"Answer directly {{input}}"}},
                              {"id":"end","type":"end","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_judge"},
                              {"from":"llm_judge","to":"condition_1"},
                              {"from":"condition_1","to":"llm_true","condition":"true"},
                              {"from":"condition_1","to":"llm_false","condition":"false"},
                              {"from":"llm_true","to":"end"},
                              {"from":"llm_false","to":"end"}
                            ]
                          },
                          "notes": ["第一次变量写错"]
                        }
                        """), AiModelResult.ok("""
                        {
                          "name": "变量修复流程",
                          "description": "repaired attempt",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","config":{}},
                              {"id":"llm_judge","type":"llm","config":{"prompt":"Return true or false for {{input}}"}},
                              {"id":"condition_1","type":"condition","config":{"left":"{{nodes.llm_judge.answer}}","operator":"equals","right":"true"}},
                              {"id":"llm_true","type":"llm","config":{"prompt":"Answer with context {{context}}"}},
                              {"id":"llm_false","type":"llm","config":{"prompt":"Answer directly {{input}}"}},
                              {"id":"end","type":"end","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_judge"},
                              {"from":"llm_judge","to":"condition_1"},
                              {"from":"condition_1","to":"llm_true","condition":"true"},
                              {"from":"condition_1","to":"llm_false","condition":"false"},
                              {"from":"llm_true","to":"end"},
                              {"from":"llm_false","to":"end"}
                            ]
                          },
                          "notes": ["按变量规则修复"]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("判断后回答"));

        assertThat(response.name()).isEqualTo("变量修复流程");
        assertThat(response.notes()).containsExactly("按变量规则修复");
        assertThat(response.workflowDefinition().nodes().get(2).config())
                .containsEntry("left", "{{nodes.llm_judge.answer}}");
        verify(aiModelService).generate(contains("工作流编排器"), contains("不支持的模板变量"));
    }

    @Test
    void generatesRetrieverAndLlmWorkflowFromKnowledgeBasePrompt() {
        WorkflowGenerationService service = aiServiceReturning("""
                {
                  "name": "知识库问答工作流",
                  "description": "先检索知识库再回答",
                  "workflowDefinition": {
                    "nodes": [
                      {"id":"start","type":"start","config":{}},
                      {"id":"retriever_1","type":"retriever","config":{"topK":3}},
                      {"id":"llm_1","type":"llm","config":{"prompt":"Answer with {{context}} and {{input}}"}},
                      {"id":"end","type":"end","config":{}}
                    ],
                    "edges": [
                      {"from":"start","to":"retriever_1"},
                      {"from":"retriever_1","to":"llm_1"},
                      {"from":"llm_1","to":"end"}
                    ]
                  },
                  "notes": ["AI 选择知识库检索"]
                }
                """);

        WorkflowGenerationResponse response = service.generate(
                new WorkflowGenerationRequest("请生成一个工作流：先检索知识库文档，再让大模型结合上下文回答用户问题"));

        assertThat(response.name()).isEqualTo("知识库问答工作流");
        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::type)
                .containsExactly("start", "retriever", "llm", "end");
        assertThat(edgePairs(response.workflowDefinition()))
                .containsExactly("start->retriever_1", "retriever_1->llm_1", "llm_1->end");
        assertThat(response.workflowDefinition().nodes().get(1).config())
                .containsEntry("topK", 3);
        assertThat(response.workflowDefinition().nodes().get(2).config().get("prompt").toString())
                .contains("{{context}}")
                .contains("{{input}}");
    }

    @Test
    void insertsToolNodeWhenPromptMentionsCalculationTool() {
        WorkflowGenerationService service = aiServiceReturning("""
                {
                  "name": "知识库计算总结工作流",
                  "description": "先检索再计算最后总结",
                  "workflowDefinition": {
                    "nodes": [
                      {"id":"start","type":"start","config":{}},
                      {"id":"retriever_1","type":"retriever","config":{"topK":3}},
                      {"id":"tool_1","type":"tool","config":{"toolName":"calculate","expression":"{{input.expression}}"}},
                      {"id":"llm_1","type":"llm","config":{"prompt":"Summarize {{lastOutput}} with {{context}} and {{input}}"}},
                      {"id":"end","type":"end","config":{}}
                    ],
                    "edges": [
                      {"from":"start","to":"retriever_1"},
                      {"from":"retriever_1","to":"tool_1"},
                      {"from":"tool_1","to":"llm_1"},
                      {"from":"llm_1","to":"end"}
                    ]
                  },
                  "notes": ["AI 选择检索和计算工具"]
                }
                """);

        WorkflowGenerationResponse response = service.generate(
                new WorkflowGenerationRequest("生成一个工作流：先检索知识库，再调用计算工具，最后让大模型总结"));

        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::type)
                .containsExactly("start", "retriever", "tool", "llm", "end");
        assertThat(edgePairs(response.workflowDefinition()))
                .containsExactly("start->retriever_1", "retriever_1->tool_1", "tool_1->llm_1", "llm_1->end");
        WorkflowNode toolNode = response.workflowDefinition().nodes().get(2);
        assertThat(toolNode.config())
                .containsEntry("toolName", "calculate")
                .containsEntry("expression", "{{input.expression}}");
    }

    @Test
    void repairsCustomerReviewWorkflowWhenParsedRoutingLacksStructuredContract() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "客户评价分流初稿",
                          "description": "缺少结构化输出契约",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","label":"接收客户评价","config":{}},
                              {"id":"llm_sentiment","type":"llm","label":"判断评价情绪","config":{"prompt":"判断客户评价是正面还是负面：{{input.message}}"}},
                              {"id":"condition_sentiment","type":"condition","label":"按情绪分流","config":{"left":"{{nodes.llm_sentiment.parsed.sentiment}}","operator":"equals","right":"positive"}},
                              {"id":"llm_positive","type":"llm","label":"生成品牌营销通知","config":{"prompt":"生成品牌营销通知：{{input.message}}"}},
                              {"id":"llm_negative","type":"llm","label":"生成客服处理建议","config":{"prompt":"生成客服处理建议：{{input.message}}"}},
                              {"id":"end","type":"end","label":"结束输出","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_sentiment"},
                              {"from":"llm_sentiment","to":"condition_sentiment"},
                              {"from":"condition_sentiment","to":"llm_positive","condition":"true"},
                              {"from":"condition_sentiment","to":"llm_negative","condition":"false"},
                              {"from":"llm_positive","to":"end"},
                              {"from":"llm_negative","to":"end"}
                            ]
                          },
                          "notes": ["第一次缺少 outputSchema"]
                        }
                        """), AiModelResult.ok("""
                        {
                          "name": "客户评价分流修复",
                          "description": "带结构化输出契约",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","label":"接收客户评价","config":{}},
                              {"id":"llm_sentiment","type":"llm","label":"判断评价情绪","config":{
                                "prompt":"判断客户评价是正面还是负面：{{input.message}}。只输出合法 JSON。",
                                "outputMode":"json",
                                "outputSchema":{
                                  "type":"object",
                                  "required":["sentiment","reason"],
                                  "additionalProperties":false,
                                  "properties":{
                                    "sentiment":{"type":"string","title":"评价倾向","enum":["positive","negative"]},
                                    "reason":{"type":"string","title":"判断原因"}
                                  }
                                },
                                "writeState":{"sentiment":"{{lastOutput.parsed.sentiment}}","sentimentReason":"{{lastOutput.parsed.reason}}"}
                              }},
                              {"id":"condition_sentiment","type":"condition","label":"按情绪分流","config":{"left":"{{nodes.llm_sentiment.parsed.sentiment}}","operator":"equals","right":"positive"}},
                              {"id":"llm_positive","type":"llm","label":"生成品牌营销通知","config":{"prompt":"根据客户评价生成品牌营销通知：{{input.message}}"}},
                              {"id":"llm_negative","type":"llm","label":"生成客服处理建议","config":{"prompt":"根据客户评价生成客服处理建议：{{input.message}}"}},
                              {"id":"end","type":"end","label":"结束输出","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_sentiment"},
                              {"from":"llm_sentiment","to":"condition_sentiment"},
                              {"from":"condition_sentiment","to":"llm_positive","condition":"true"},
                              {"from":"condition_sentiment","to":"llm_negative","condition":"false"},
                              {"from":"llm_positive","to":"end"},
                              {"from":"llm_negative","to":"end"}
                            ]
                          },
                          "notes": ["按结构化输出契约修复"]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest(
                "做一个客户评价系统，判断正面负面，正面发给品牌营销，负面给客服处理"));

        assertThat(response.name()).isEqualTo("客户评价分流修复");
        WorkflowNode sentimentNode = response.workflowDefinition().nodes().stream()
                .filter(node -> node.id().equals("llm_sentiment"))
                .findFirst()
                .orElseThrow();
        assertThat(sentimentNode.config())
                .containsEntry("outputMode", "json")
                .containsKeys("outputSchema", "writeState");
        verify(aiModelService).generate(contains("工作流编排器"), contains("结构化输出字段"));
    }

    @Test
    void normalizesGeneratedWorkflowWithSavePipelineBeforeReturning() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "客服意图路由",
                          "description": "模型未显式写结构化契约，平台应按保存规则补齐",
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","label":"接收消息","config":{}},
                              {"id":"llm_intent","type":"llm","label":"意图识别","config":{"prompt":"请识别用户客服意图并决定后续客服流程：{{input.message}}"}},
                              {"id":"condition_order","type":"condition","label":"是否查订单","config":{"left":"{{nodes.llm_intent.parsed.intent}}","operator":"equals","right":"order_query"}},
                              {"id":"llm_order","type":"llm","label":"订单回复","config":{"prompt":"回复订单问题：{{input.message}}"}},
                              {"id":"llm_other","type":"llm","label":"通用回复","config":{"prompt":"通用客服回复：{{input.message}}"}},
                              {"id":"end","type":"end","label":"结束","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_intent"},
                              {"from":"llm_intent","to":"condition_order"},
                              {"from":"condition_order","to":"llm_order","condition":"true"},
                              {"from":"condition_order","to":"llm_other","condition":"false"},
                              {"from":"llm_order","to":"end"},
                              {"from":"llm_other","to":"end"}
                            ]
                          },
                          "notes": ["平台补齐结构化输出契约后才返回"]
                        }
                        """), AiModelResult.ok("""
                        {
                          "name": "不应走到修复",
                          "description": "如果第一轮归一化校验正确，不应该请求修复",
                          "workflowDefinition": {"nodes":[],"edges":[]},
                          "notes": []
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("做一个客服意图路由"));

        WorkflowNode intentNode = response.workflowDefinition().nodes().stream()
                .filter(node -> node.id().equals("llm_intent"))
                .findFirst()
                .orElseThrow();
        assertThat(intentNode.config())
                .containsEntry("outputMode", "json")
                .containsEntry("autoStructuredOutputContract", "customer_service_intent")
                .containsKeys("outputSchema", "writeState");
        verify(aiModelService, times(1)).generate(contains("工作流编排器"), anyString());
    }

    @Test
    void runsAiGeneratedWorkflowWithModelProvidedTestInputBeforeReturning() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowRuntime workflowRuntime = mock(WorkflowRuntime.class);
        TraceService traceService = mock(TraceService.class);
        WorkflowGenerationService aiService = testService(aiModelService, workflowRuntime, traceService, null);
        Map<String, Object> testInput = Map.of("message", "物流太慢了，包裹晚到了三天。");
        when(traceService.startRun(eq(RunType.WORKFLOW), any()))
                .thenReturn(new TraceRun("generation-smoke-run", Instant.parse("2026-07-09T00:00:00Z")));
        when(workflowRuntime.run(eq("generation-smoke-run"), any(), eq(testInput)))
                .thenReturn(new WorkflowRuntime.WorkflowExecutionResult(
                        Map.of("answer", "已生成运输问题处理建议"),
                        List.of(new WorkflowStepSummary("start", "start", "SUCCEEDED", testInput),
                                new WorkflowStepSummary("end", "end", "SUCCEEDED", "ok"))));
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "客户评价自动分流系统",
                          "description": "自动判断客户评价并分流",
                          "testInput": {"message":"物流太慢了，包裹晚到了三天。"},
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","label":"接收评价","config":{}},
                              {"id":"llm_1","type":"llm","label":"生成处理建议","config":{"prompt":"根据客户评价生成处理建议：{{input.message}}"}},
                              {"id":"end","type":"end","label":"结束","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_1"},
                              {"from":"llm_1","to":"end"}
                            ]
                          },
                          "notes": ["生成后自动运行一条物流差评样例"]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("创建客户评价分流"));

        assertThat(response.testResult()).isNotNull();
        assertThat(response.testResult().input()).isEqualTo(testInput);
        assertThat(response.testResult().runId()).isEqualTo("generation-smoke-run");
        assertThat(response.testResult().stepCount()).isEqualTo(2);
        verify(workflowRuntime).run(eq("generation-smoke-run"), any(), eq(testInput));
        verify(traceService).markRunSucceeded(eq("generation-smoke-run"), any());
    }

    @Test
    void editsExistingWorkflowFromNaturalLanguageInstruction() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        WorkflowDefinition currentDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of(), "开始入口", null),
                        new WorkflowNode("llm_positive", "llm",
                                Map.of("prompt", "生成品牌营销通知：{{input.message}}"), "品牌营销通知", null),
                        new WorkflowNode("end", "end", Map.of(), "结束输出", null)),
                List.of(
                        new WorkflowEdge("start", "llm_positive", null, "进入营销通知", null),
                        new WorkflowEdge("llm_positive", "end", null, "输出结果", null)));
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok("""
                        {
                          "name": "客户评价处理系统",
                          "description": "在现有正面评价流程上增加负面运输处理",
                          "testInput": {"message":"物流太慢了，包裹晚到三天"},
                          "workflowDefinition": {
                            "nodes": [
                              {"id":"start","type":"start","label":"开始入口","config":{}},
                              {"id":"llm_positive","type":"llm","label":"品牌营销通知","config":{"prompt":"生成品牌营销通知：{{input.message}}"}},
                              {"id":"llm_shipping","type":"llm","label":"运输部门通知","config":{"prompt":"生成运输部门和产品体验部门通知：{{input.message}}"}},
                              {"id":"end","type":"end","label":"结束输出","config":{}}
                            ],
                            "edges": [
                              {"from":"start","to":"llm_positive","label":"进入营销通知"},
                              {"from":"llm_positive","to":"llm_shipping","label":"补充运输处理"},
                              {"from":"llm_shipping","to":"end","label":"输出结果"}
                            ]
                          },
                          "notes": ["保留原有品牌营销节点，并新增运输通知节点"]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.edit(new WorkflowEditRequest(
                "增加一个负面运输问题处理，通知运输部门和产品体验部门",
                "客户评价系统",
                "现有正面评价流程",
                currentDefinition));

        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::id)
                .contains("llm_positive", "llm_shipping");
        verify(aiModelService).generate(contains("工作流编排器"), argThat(message ->
                message.contains("当前工作流 JSON")
                        && message.contains("\"llm_positive\"")
                        && message.contains("增加一个负面运输问题处理")));
    }

    @Test
    void repairPromptForcesSuperpowersWorkflow() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("修复后工作流", "我要退货，订单号123456")));
        WorkflowDefinition currentDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_transfer", "llm",
                                Map.of("prompt", "输出转人工 JSON：{{input.message}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_transfer"),
                        new WorkflowEdge("llm_transfer", "end")));

        aiService.repair(new WorkflowRepairRequest(
                "客户退货时要给客户自然语言回复",
                "客户对话里看到了裸 JSON",
                "售后物流智能分流",
                "当前画布",
                currentDefinition));

        verify(aiModelService).generate(contains("工作流编排器"), argThat(message ->
                message.contains("Superpowers 修复流程")
                        && message.contains("1. Diagnose")
                        && message.contains("2. Plan")
                        && message.contains("3. Implement")
                        && message.contains("4. Verify")
                        && message.contains("notes 必须包含")
                        && message.contains("不要绕过流程")));
    }

    @Test
    void repairsGeneratedWorkflowWhenAutomaticRuntimeTestFails() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowRuntime workflowRuntime = mock(WorkflowRuntime.class);
        TraceService traceService = mock(TraceService.class);
        WorkflowGenerationService aiService = testService(aiModelService, workflowRuntime, traceService, null);
        Map<String, Object> brokenInput = Map.of("message", "这个产品太差了，快递还晚到了。");
        Map<String, Object> repairedInput = Map.of("message", "包装很好，体验不错。");
        when(traceService.startRun(eq(RunType.WORKFLOW), any()))
                .thenReturn(new TraceRun("generation-smoke-run-1", Instant.parse("2026-07-09T00:00:00Z")),
                        new TraceRun("generation-smoke-run-2", Instant.parse("2026-07-09T00:00:01Z")));
        when(workflowRuntime.run(eq("generation-smoke-run-1"), any(), eq(brokenInput)))
                .thenThrow(new IllegalStateException("LLM structured output missing sentiment"));
        when(workflowRuntime.run(eq("generation-smoke-run-2"), any(), eq(repairedInput)))
                .thenReturn(new WorkflowRuntime.WorkflowExecutionResult(
                        Map.of("answer", "已生成品牌营销通知"),
                        List.of(new WorkflowStepSummary("start", "start", "SUCCEEDED", repairedInput),
                                new WorkflowStepSummary("end", "end", "SUCCEEDED", "ok"))));
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("第一版试跑失败", brokenInput.get("message"))),
                        AiModelResult.ok(withSuperpowersNotes(
                                generatedSmokeWorkflow("第二版试跑通过", repairedInput.get("message")))));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("创建客户评价分流"));

        assertThat(response.name()).isEqualTo("第二版试跑通过");
        assertThat(response.testResult()).isNotNull();
        assertThat(response.testResult().input()).isEqualTo(repairedInput);
        assertThat(response.testResult().runId()).isEqualTo("generation-smoke-run-2");
        verify(traceService).markRunFailed(eq("generation-smoke-run-1"), any());
        verify(traceService).markRunSucceeded(eq("generation-smoke-run-2"), any());
        verify(aiModelService).generate(contains("工作流编排器"), argThat(message ->
                message.contains("自动测试运行失败")
                        && message.contains("Superpowers 修复流程")
                        && message.contains("1. Diagnose")
                        && message.contains("2. Plan")
                        && message.contains("3. Implement")
                        && message.contains("4. Verify")
                        && message.contains("notes 必须包含")
                        && message.contains("不要绕过流程")));
    }

    @Test
    void staticGovernanceBlockersSkipRuntimeAndReturnStructuredBlockedAfterThreeCandidates() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("STATIC_BLOCK_CONTEXT");
        WorkflowGovernanceReport blocked = blockedGovernanceReport("core-registered-tools", "tool_missing");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context))).thenReturn(blocked);
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("阻断候选一", "测试")),
                        AiModelResult.ok(generatedSmokeWorkflow("阻断候选二", "测试")),
                        AiModelResult.ok(generatedSmokeWorkflow("阻断候选三", "测试")));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建订单客服工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.governanceReport().blockers())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .containsExactly("core-registered-tools");
        assertThat(response.repairAttempts()).isEqualTo(2);
        verifyNoInteractions(evaluationService);
        verify(aiModelService, times(3)).generate(anyString(), anyString());
        verify(aiModelService, times(2)).generate(anyString(), argThat(message ->
                message.contains("core-registered-tools")
                        && message.contains("tool_missing")
                        && message.contains("1. Diagnose")
                        && message.contains("4. Verify")));
    }

    @Test
    void runtimeDesignFailureFeedsCompleteEvidenceIntoRepairAndOnlyPassingCandidateIsReady() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("RUNTIME_REPAIR_CONTEXT");
        WorkflowEvaluationCaseResult failedCase = evaluationResult(
                WorkflowEvaluationCaseStatus.DESIGN_FAILED,
                WorkflowEvaluationErrorOrigin.DESIGN,
                "tracking-delay",
                "EVALUATION_ASSERTION_FAILED",
                "start,classify,end");
        WorkflowEvaluationCaseResult passedCase = evaluationResult(
                WorkflowEvaluationCaseStatus.PASSED,
                null,
                "tracking-delay",
                null,
                "start,classify,lookup,end");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any()))
                .thenReturn(new WorkflowEvaluationReport(Map.of("message", "订单测试"), List.of(failedCase)),
                        new WorkflowEvaluationReport(Map.of("message", "订单测试"), List.of(passedCase)));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("运行失败候选", "订单测试")),
                        AiModelResult.ok(withSuperpowersNotes(
                                generatedSmokeWorkflow("运行通过候选", "订单测试"))));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建订单客服工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.name()).isEqualTo("运行通过候选");
        assertThat(response.repairAttempts()).isEqualTo(1);
        assertThat(response.testResults()).containsExactly(passedCase);
        assertThat(response.testResult()).isNotNull();
        assertThat(response.testResult().input()).containsEntry("message", "订单测试");
        assertThat(response.testResult().output()).isEqualTo(Map.of("answer", "测试输出"));
        verify(evaluationService, times(2)).evaluate(any(), argThat(cases ->
                cases.size() == 8
                        && cases.getFirst().id().equals("tracking-delay")
                        && cases.getLast().id().equals("missing-order-result-tool-failure")), any());
        verify(aiModelService).generate(anyString(), argThat(message ->
                message.contains("tracking-delay")
                        && message.contains("EVALUATION_ASSERTION_FAILED")
                        && message.contains("\"executedPath\":[\"start\",\"classify\",\"end\"]")
                        && message.contains("Superpowers 修复流程")));
    }

    @Test
    void unresolvedInfrastructureFailureReturnsInfraErrorWithoutRequestingModelRepair() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("INFRA_CONTEXT");
        WorkflowEvaluationCaseResult infraCase = evaluationResult(
                WorkflowEvaluationCaseStatus.INFRA_ERROR,
                WorkflowEvaluationErrorOrigin.PROVIDER,
                "tracking-delay",
                "ALIBABA_LLM_UNAVAILABLE",
                "start,classify");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any()))
                .thenReturn(new WorkflowEvaluationReport(Map.of("message", "订单测试"), List.of(infraCase)));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("基础设施异常候选", "订单测试")));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建订单客服工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.INFRA_ERROR);
        assertThat(response.testResults()).containsExactly(infraCase);
        assertThat(response.repairAttempts()).isZero();
        verify(aiModelService).generate(anyString(), anyString());
        verify(aiModelService, never()).generate(anyString(), contains("第 1 次自动修复"));
    }

    @Test
    void mixedInfrastructureAndDesignFailuresStillRepairTheCandidate() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("MIXED_FAILURE_CONTEXT");
        WorkflowEvaluationCaseResult infraCase = evaluationResult(
                WorkflowEvaluationCaseStatus.INFRA_ERROR,
                WorkflowEvaluationErrorOrigin.PROVIDER,
                "provider-outage",
                "ALIBABA_LLM_UNAVAILABLE",
                "start,classify");
        WorkflowEvaluationCaseResult designCase = evaluationResult(
                WorkflowEvaluationCaseStatus.DESIGN_FAILED,
                WorkflowEvaluationErrorOrigin.DESIGN,
                "tracking-delay",
                "EVALUATION_ASSERTION_FAILED",
                "start,classify,end");
        WorkflowEvaluationCaseResult passedCase = evaluationResult(
                WorkflowEvaluationCaseStatus.PASSED,
                null,
                "tracking-delay",
                null,
                "start,classify,lookup,end");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any()))
                .thenReturn(new WorkflowEvaluationReport(
                                Map.of("message", "订单测试"), List.of(infraCase, designCase)),
                        new WorkflowEvaluationReport(Map.of("message", "订单测试"), List.of(passedCase)));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("混合失败候选", "订单测试")),
                        AiModelResult.ok(withSuperpowersNotes(
                                generatedSmokeWorkflow("修复通过候选", "订单测试"))));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建订单客服工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.name()).isEqualTo("修复通过候选");
        assertThat(response.repairAttempts()).isEqualTo(1);
        verify(aiModelService, times(2)).generate(anyString(), anyString());
        verify(aiModelService).generate(anyString(), argThat(message ->
                message.contains("tracking-delay")
                        && message.contains("EVALUATION_ASSERTION_FAILED")
                        && message.contains("Superpowers 修复流程")));
    }

    @Test
    void caseDeadlineExceededRepairsTheCandidateInsteadOfEndingAsInfrastructureError() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("DEADLINE_CONTEXT");
        WorkflowEvaluationCaseResult timedOutCase = evaluationResult(
                WorkflowEvaluationCaseStatus.CANCELED,
                WorkflowEvaluationErrorOrigin.CANCELED,
                "deep-research",
                "EVALUATION_CASE_DEADLINE_EXCEEDED",
                "start,llm_parse,llm_research");
        WorkflowEvaluationCaseResult passedCase = evaluationResult(
                WorkflowEvaluationCaseStatus.PASSED,
                null,
                "deep-research",
                null,
                "start,llm_research,end");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any()))
                .thenReturn(new WorkflowEvaluationReport(Map.of("message", "研究主题"), List.of(timedOutCase)),
                        new WorkflowEvaluationReport(Map.of("message", "研究主题"), List.of(passedCase)));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("超时候选", "研究主题")),
                        AiModelResult.ok(withSuperpowersNotes(
                                generatedSmokeWorkflow("精简候选", "研究主题"))));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建深度研究工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.name()).isEqualTo("精简候选");
        assertThat(response.repairAttempts()).isEqualTo(1);
        verify(aiModelService).generate(anyString(), argThat(message ->
                message.contains("EVALUATION_CASE_DEADLINE_EXCEEDED")
                        && message.contains("Superpowers 修复流程")));
    }

    @Test
    void graphDetectedCustomerServiceDomainSchedulesMandatoryEightCasesEvenWhenLockedContextIsCore() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowRuleCatalog ruleCatalog = new WorkflowRuleCatalog();
        WorkflowBuilderContext coreContext = new WorkflowBuilderContext(
                "core", "generic assistant", "",
                ruleCatalog.activePacks(null), List.of(), List.of(), List.of(), "CORE_CONTEXT");
        when(contextService.build(eq(null), anyString(), nullable(String.class))).thenReturn(coreContext);
        when(governanceService.evaluateStatic(any(), eq(coreContext)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<WorkflowEvaluationCase> cases = invocation.getArgument(1, List.class);
            List<WorkflowEvaluationCaseResult> passedCases = cases.stream()
                    .map(workflowCase -> evaluationResult(
                            WorkflowEvaluationCaseStatus.PASSED,
                            null,
                            workflowCase.id(),
                            null,
                            "start,end"))
                    .toList();
            return new WorkflowEvaluationReport(Map.of("message", "订单测试"), passedCases);
        });
        String customerServiceWorkflow = generatedSmokeWorkflow("订单客服工作流", "订单 20260630001")
                .replace("根据客户评价生成处理建议", "根据订单物流信息生成客服处理建议");
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(customerServiceWorkflow));
        WorkflowGenerationService service = new WorkflowGenerationService(
                aiModelService,
                new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                new WorkflowStructuredOutputAutoconfigurer(),
                null,
                null,
                contextService,
                governanceService,
                evaluationService,
                ruleCatalog);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建一个通用助手"));

        assertThat(response.status())
                .as("response=%s", response)
                .isEqualTo(WorkflowGenerationStatus.READY);
        verify(evaluationService).evaluate(any(), argThat(cases ->
                cases.size() == 8
                        && cases.getFirst().id().equals("tracking-delay")
                        && cases.getLast().id().equals("missing-order-result-tool-failure")), any());
    }

    @Test
    void repairedCandidateCannotBeReadyUntilAllSuperpowersEvidenceCategoriesArePresent() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowBuilderContext context = builderContextWithCases("SUPERPOWERS_EVIDENCE_CONTEXT");
        WorkflowEvaluationCaseResult failedCase = evaluationResult(
                WorkflowEvaluationCaseStatus.DESIGN_FAILED,
                WorkflowEvaluationErrorOrigin.DESIGN,
                "tracking-delay",
                "EVALUATION_ASSERTION_FAILED",
                "start,end");
        WorkflowEvaluationCaseResult passedCase = evaluationResult(
                WorkflowEvaluationCaseStatus.PASSED,
                null,
                "tracking-delay",
                null,
                "start,lookup,end");
        when(contextService.build(eq(null), anyString(), any())).thenReturn(context);
        when(governanceService.evaluateStatic(any(), eq(context)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any()))
                .thenReturn(new WorkflowEvaluationReport(Map.of("message", "订单测试"), List.of(failedCase)),
                        new WorkflowEvaluationReport(Map.of("message", "订单测试"), List.of(passedCase)));
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok(generatedSmokeWorkflow("第一版", "订单测试")),
                        AiModelResult.ok(generatedSmokeWorkflow("缺少证据的修复版", "订单测试")),
                        AiModelResult.ok(withSuperpowersNotes(
                                generatedSmokeWorkflow("证据完整的修复版", "订单测试"))));
        WorkflowGenerationService service = governedService(
                aiModelService, contextService, governanceService, evaluationService);

        WorkflowGenerationResponse response = service.generate(new WorkflowGenerationRequest("创建订单客服工作流"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.name()).isEqualTo("证据完整的修复版");
        assertThat(response.repairAttempts()).isEqualTo(2);
        assertThat(response.notes()).hasSize(4);
        verify(evaluationService, times(2)).evaluate(any(), any(), any());
        verify(aiModelService).generate(anyString(), argThat(message ->
                message.contains("core-superpowers-repair-evidence")
                        && message.contains("诊断、计划、实际改动、验证")));
    }

    @Test
    void keepsRepairingWhenFirstRepairUsesUnwrittenStateVariable() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = testService(aiModelService);
        when(aiModelService.generate(contains("工作流编排器"), anyString()))
                .thenReturn(AiModelResult.ok(customerReviewWithStateCondition("初稿仍缺状态写入", false)),
                        AiModelResult.ok(customerReviewWithStateCondition("第一次修复仍缺状态写入", false)),
                        AiModelResult.ok(customerReviewWithStateCondition("第二次修复补齐状态写入", true)));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest(
                "创建客户评价分流：先判断正负面，正面给品牌营销，负面给客服"));

        assertThat(response.name()).isEqualTo("第二次修复补齐状态写入");
        WorkflowNode conditionNode = response.workflowDefinition().nodes().stream()
                .filter(node -> node.id().equals("cond_sentiment"))
                .findFirst()
                .orElseThrow();
        assertThat(conditionNode.config()).containsEntry("left", "{{state.sentiment}}");
        WorkflowNode sentimentNode = response.workflowDefinition().nodes().stream()
                .filter(node -> node.id().equals("llm_sentiment"))
                .findFirst()
                .orElseThrow();
        assertThat(sentimentNode.config()).containsKey("writeState");
        verify(aiModelService).generate(contains("工作流编排器"), contains("第 2 次自动修复"));
    }

    private WorkflowBuilderContext mockBuilderContext(String marker) {
        return new WorkflowBuilderContext(
                "core",
                "locked spec",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "UNTRUSTED_BUILDER_KNOWLEDGE\n" + marker);
    }

    private WorkflowBuilderContext builderContextWithCases(String marker) {
        WorkflowEvaluationCase evaluationCase = new WorkflowEvaluationCase(
                "tracking-delay", "Track order", "Use real lookup",
                WorkflowEvaluationCaseKind.RUNTIME,
                Map.of("message", "Track order"),
                List.of());
        WorkflowRulePack pack = new WorkflowRulePack(
                "customer-service-ecommerce", "test", List.of("customer-service-ecommerce"),
                List.of(), List.of(), List.of(evaluationCase));
        return new WorkflowBuilderContext(
                "customer-service-ecommerce",
                "locked spec",
                "",
                List.of(pack),
                List.of(),
                List.of(),
                List.of(),
                "UNTRUSTED_BUILDER_KNOWLEDGE\n" + marker);
    }

    private WorkflowGovernanceReport blockedGovernanceReport(String ruleId, String nodeId) {
        return new WorkflowGovernanceReport(List.of(new WorkflowGovernanceFinding(
                ruleId,
                WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC,
                "Blocked candidate",
                List.of(nodeId),
                "Use a registered capability",
                Map.of("missingTool", "crm_lookup"))));
    }

    private WorkflowEvaluationCaseResult evaluationResult(
            WorkflowEvaluationCaseStatus status,
            WorkflowEvaluationErrorOrigin origin,
            String caseId,
            String errorCode,
            String path) {
        return new WorkflowEvaluationCaseResult(
                caseId,
                Map.of("message", "订单测试"),
                status,
                List.of("run-1"),
                List.of(path.split(",")),
                List.of(),
                Map.of("answer", "测试输出"),
                "测试输出",
                origin,
                errorCode);
    }

    private WorkflowGenerationService governedService(
            AiModelService aiModelService,
            WorkflowBuilderContextService contextService,
            WorkflowGovernanceService governanceService,
            WorkflowEvaluationService evaluationService) {
        return new WorkflowGenerationService(
                aiModelService,
                new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                new WorkflowStructuredOutputAutoconfigurer(),
                null,
                null,
                contextService,
                governanceService,
                evaluationService,
                new WorkflowRuleCatalog());
    }

    private WorkflowGenerationService testService(AiModelService aiModelService) {
        return testService(aiModelService, null, null, null);
    }

    private WorkflowGenerationService testService(AiModelService aiModelService,
            WorkflowRuntime workflowRuntime,
            TraceService traceService,
            WorkflowBuilderContextService contextService) {
        return testService(aiModelService, workflowRuntime, traceService, contextService, null);
    }

    private WorkflowGenerationService testService(AiModelService aiModelService,
            WorkflowRuntime workflowRuntime,
            TraceService traceService,
            WorkflowBuilderContextService contextService,
            HttpCredentialService httpCredentialService) {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowNodeSchemaRegistry schemaRegistry = new WorkflowNodeSchemaRegistry();
        WorkflowCompiler compiler = new WorkflowCompiler(schemaRegistry);
        WorkflowStructuredOutputAutoconfigurer autoconfigurer = new WorkflowStructuredOutputAutoconfigurer();
        WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
        WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
        WorkflowRuleCatalog ruleCatalog = new WorkflowRuleCatalog();
        when(governanceService.evaluateStatic(any(), nullable(WorkflowBuilderContext.class)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(evaluationService.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            WorkflowExecutionPlan plan = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> input = invocation.getArgument(2, Map.class);
            if (workflowRuntime == null || traceService == null) {
                WorkflowEvaluationCaseResult passed = new WorkflowEvaluationCaseResult(
                        "unit-runtime-case",
                        input,
                        WorkflowEvaluationCaseStatus.PASSED,
                        List.of("unit-governance-run"),
                        List.of("start", "end"),
                        List.of(),
                        Map.of("answer", "unit governance passed"),
                        "unit governance passed",
                        null,
                        null);
                return new WorkflowEvaluationReport(input, List.of(passed));
            }

            TraceRun traceRun = traceService.startRun(RunType.WORKFLOW, Map.of(
                    "kind", "workflow_generation_governance_test"));
            String runId = traceRun == null ? "unit-governance-run" : traceRun.runId();
            try {
                WorkflowRuntime.WorkflowExecutionResult result = workflowRuntime.run(runId, plan, input);
                traceService.markRunSucceeded(runId, result.output());
                List<String> path = result.steps().stream().map(WorkflowStepSummary::nodeId).toList();
                WorkflowEvaluationCaseResult passed = new WorkflowEvaluationCaseResult(
                        "model-supplemental-input",
                        input,
                        WorkflowEvaluationCaseStatus.PASSED,
                        List.of(runId),
                        path,
                        List.of(),
                        result.output(),
                        String.valueOf(result.output()),
                        null,
                        null);
                return new WorkflowEvaluationReport(input, List.of(passed));
            }
            catch (RuntimeException error) {
                traceService.markRunFailed(runId, error);
                WorkflowEvaluationCaseResult failed = new WorkflowEvaluationCaseResult(
                        "model-supplemental-input",
                        input,
                        WorkflowEvaluationCaseStatus.DESIGN_FAILED,
                        List.of(runId),
                        List.of(),
                        List.of(),
                        null,
                        "自动测试运行失败: " + error.getMessage(),
                        WorkflowEvaluationErrorOrigin.DESIGN,
                        "WORKFLOW_GENERATION_TEST_FAILED");
                return new WorkflowEvaluationReport(input, List.of(failed));
            }
        });
        WorkflowGovernanceOrchestrator orchestrator = new WorkflowGovernanceOrchestrator(
                autoconfigurer,
                compiler,
                new WorkflowDefinitionContractValidator(),
                contextService == null ? mock(WorkflowBuilderContextService.class) : contextService,
                governanceService,
                evaluationService,
                ruleCatalog,
                objectMapper);
        return new WorkflowGenerationService(
                aiModelService,
                objectMapper,
                compiler,
                autoconfigurer,
                workflowRuntime,
                traceService,
                contextService,
                governanceService,
                evaluationService,
                ruleCatalog,
                orchestrator,
                schemaRegistry,
                httpCredentialService);
    }

    private WorkflowBuilderContext boundedBuilderContext(String marker) {
        return new WorkflowBuilderContext(
                "core",
                "locked spec",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                """
                        WORKFLOW_BUILDER_CONTEXT
                        UNTRUSTED_BUILDER_KNOWLEDGE_BEGIN
                        %s
                        UNTRUSTED_BUILDER_KNOWLEDGE_END
                        END_WORKFLOW_BUILDER_CONTEXT
                        """.formatted(marker));
    }

    private WorkflowGenerationService serviceWithContext(AiModelService aiModelService,
            WorkflowBuilderContextService contextService) {
        return testService(aiModelService, null, null, contextService);
    }

    private void stubStreamingWorkflow(AiModelService aiModelService, String response) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(2);
            onChunk.accept(response);
            return null;
        }).when(aiModelService).streamUntilComplete(anyString(), anyString(), any(), any());
    }

    private boolean hasPreservedBoundaries(String message, String marker) {
        int contextStart = message.indexOf("WORKFLOW_BUILDER_CONTEXT");
        int untrustedStart = message.indexOf("UNTRUSTED_BUILDER_KNOWLEDGE_BEGIN");
        int content = message.indexOf(marker);
        int untrustedEnd = message.indexOf("UNTRUSTED_BUILDER_KNOWLEDGE_END");
        int contextEnd = message.indexOf("END_WORKFLOW_BUILDER_CONTEXT");
        return contextStart >= 0
                && contextStart < untrustedStart
                && untrustedStart < content
                && content < untrustedEnd
                && untrustedEnd < contextEnd;
    }

    private WorkflowDefinition linearDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_reply", "llm", Map.of("prompt", "回复客户：{{input.message}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_reply"),
                        new WorkflowEdge("llm_reply", "end")));
    }

    private List<String> edgePairs(WorkflowDefinition definition) {
        return definition.edges().stream()
                .map(edge -> edge.from() + "->" + edge.to())
                .toList();
    }

    private String customerReviewWithStateCondition(String name, boolean includeWriteState) {
        String writeState = includeWriteState
                ? """
                  ,
                                  "writeState":{"sentiment":"{{lastOutput.parsed.sentiment}}"}
                  """
                : "";
        return """
                {
                  "name": "%s",
                  "description": "客户评价分流",
                  "workflowDefinition": {
                    "nodes": [
                      {"id":"start","type":"start","label":"接收评价","config":{}},
                      {"id":"llm_sentiment","type":"llm","label":"情绪判断","config":{
                        "prompt":"判断客户评价是 positive 还是 negative：{{input.message}}。只输出合法 JSON。",
                        "outputMode":"json",
                        "outputSchema":{
                          "type":"object",
                          "required":["sentiment"],
                          "properties":{"sentiment":{"type":"string","title":"情绪","enum":["positive","negative"]}}
                        }%s
                      }},
                      {"id":"cond_sentiment","type":"condition","label":"是否正面","config":{"left":"{{state.sentiment}}","operator":"equals","right":"positive"}},
                      {"id":"llm_positive","type":"llm","label":"品牌营销通知","config":{"prompt":"生成品牌营销通知：{{input.message}}"}},
                      {"id":"llm_negative","type":"llm","label":"客服处理建议","config":{"prompt":"生成客服处理建议：{{input.message}}"}},
                      {"id":"end","type":"end","label":"结束输出","config":{}}
                    ],
                    "edges": [
                      {"from":"start","to":"llm_sentiment"},
                      {"from":"llm_sentiment","to":"cond_sentiment"},
                      {"from":"cond_sentiment","to":"llm_positive","condition":"true"},
                      {"from":"cond_sentiment","to":"llm_negative","condition":"false"},
                      {"from":"llm_positive","to":"end"},
                      {"from":"llm_negative","to":"end"}
                    ]
                  },
                  "notes": ["测试 state 修复"]
                }
                """.formatted(name, writeState);
    }

    private String generatedSmokeWorkflow(String name, Object message) {
        return """
                {
                  "name": "%s",
                  "description": "客户评价自动分流",
                  "testInput": {"message":"%s"},
                  "workflowDefinition": {
                    "nodes": [
                      {"id":"start","type":"start","label":"接收评价","config":{}},
                      {"id":"llm_reply","type":"llm","label":"生成通知","config":{"prompt":"根据客户评价生成处理建议：{{input.message}}"}},
                      {"id":"end","type":"end","label":"结束输出","config":{}}
                    ],
                    "edges": [
                      {"from":"start","to":"llm_reply"},
                      {"from":"llm_reply","to":"end"}
                    ]
                  },
                  "notes": ["自动生成测试样例并试运行"]
                }
                """.formatted(name, message);
    }

    private String withSuperpowersNotes(String generatedWorkflow) {
        return generatedWorkflow.replace(
                "\"notes\": [\"自动生成测试样例并试运行\"]",
                "\"notes\": [\"诊断结论：运行案例暴露路由缺口\","
                        + "\"修复计划：保留业务节点并补齐真实工具路径\","
                        + "\"实际改动：更新条件、工具参数和失败分支\","
                        + "\"验证样例：订单测试已通过完整运行案例\"]");
    }

    private WorkflowGenerationService aiServiceReturning(String answer) {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(contains("工作流编排器"), contains("用户需求")))
                .thenReturn(AiModelResult.ok(answer));
        return testService(aiModelService);
    }

}
