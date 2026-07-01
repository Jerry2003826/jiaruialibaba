package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowGenerationServiceTest {

    @Test
    void usesAiGeneratedWorkflowWhenModelReturnsValidJson() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = new WorkflowGenerationService(aiModelService, new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
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
        WorkflowGenerationService aiService = new WorkflowGenerationService(aiModelService, new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
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
                }).when(aiModelService).stream(contains("工作流编排器"), contains("用户需求"), any());
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
        verify(aiModelService).stream(contains("工作流编排器"), contains("用户需求"), any());
    }

    @Test
    void failsWhenAiReturnsInvalidJsonAndRepairAlsoFails() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = new WorkflowGenerationService(aiModelService, new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
        when(aiModelService.generate(contains("工作流编排器"), contains("用户需求")))
                .thenReturn(AiModelResult.ok("我无法返回 JSON"));

        assertThatThrownBy(() -> aiService.generate(new WorkflowGenerationRequest("先检索知识库，再调用计算工具")))
                .isInstanceOf(com.example.agentdemo.common.BusinessException.class)
                .extracting(ex -> ((com.example.agentdemo.common.BusinessException) ex).getCode())
                .isEqualTo("WORKFLOW_GENERATION_FAILED");
    }

    @Test
    void repairsAiWorkflowWhenFirstTopologyFailsValidation() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = new WorkflowGenerationService(aiModelService, new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
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
                          "notes": ["按校验错误改成线性流程"]
                        }
                        """));

        WorkflowGenerationResponse response = aiService.generate(new WorkflowGenerationRequest("直接回答用户问题"));

        assertThat(response.name()).isEqualTo("修复后的问答流程");
        assertThat(response.notes()).containsExactly("按校验错误改成线性流程");
        assertThat(response.workflowDefinition().nodes())
                .extracting(WorkflowNode::type)
                .containsExactly("start", "llm", "end");
        verify(aiModelService).generate(contains("工作流编排器"), contains("校验错误"));
    }

    @Test
    void repairsAiWorkflowWhenFirstTemplateVariableIsUnsupported() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGenerationService aiService = new WorkflowGenerationService(aiModelService, new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
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

    private List<String> edgePairs(WorkflowDefinition definition) {
        return definition.edges().stream()
                .map(edge -> edge.from() + "->" + edge.to())
                .toList();
    }

    private WorkflowGenerationService aiServiceReturning(String answer) {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(contains("工作流编排器"), contains("用户需求")))
                .thenReturn(AiModelResult.ok(answer));
        return new WorkflowGenerationService(aiModelService, new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
    }

}
