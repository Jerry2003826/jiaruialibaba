package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowPromptDraftServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void draftsBusinessInstructionFromBriefRequirement() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowPromptDraftService service = new WorkflowPromptDraftService(aiModelService);
        when(aiModelService.generate(contains("大模型节点"), contains("判断客户评论正负面")))
                .thenReturn(AiModelResult.ok("""
                        ```text
                        你是客户评价分类助手。请判断客户评论属于正面还是负面，并只输出 JSON。
                        ```
                        """));

        WorkflowPromptDraftResponse response = service.draft(new WorkflowPromptDraftRequest(
                "判断客户评论正负面", "评价分类", "输入内容 · 用户消息"));

        assertThat(response.instruction())
                .contains("你是客户评价分类助手。请判断客户评论属于正面还是负面，并只输出 JSON。")
                .contains("sentiment", "positive", "negative");
        verify(aiModelService).generate(contains("只保留这两个枚举"), contains("输入内容 · 用户消息"));
    }

    @Test
    void draftsStructuredOutputContractForSentimentClassification() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowPromptDraftService service = new WorkflowPromptDraftService(aiModelService);
        when(aiModelService.generate(contains("大模型节点"), contains("判断客户评论是正面还是负面")))
                .thenReturn(AiModelResult.ok("请判断客户评论是正面还是负面，并输出分类原因。"));

        WorkflowPromptDraftResponse response = service.draft(new WorkflowPromptDraftRequest(
                "判断客户评论是正面还是负面", "评价分类", "输入内容 · 用户消息"));
        Map<String, Object> payload = objectMapper.convertValue(response, new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> outputSchema = (Map<String, Object>) payload.get("outputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) outputSchema.get("properties");

        assertThat(payload).containsEntry("outputMode", "json");
        assertThat(properties).containsKeys("sentiment", "reason");
        assertThat(properties.get("sentiment").toString()).contains("positive", "negative");
        assertThat(payload.get("writeState"))
                .isEqualTo(Map.of(
                        "sentiment", "{{lastOutput.parsed.sentiment}}",
                        "reason", "{{lastOutput.parsed.reason}}"));
        assertThat(response.instruction()).contains("sentiment", "positive", "negative");
    }

    @Test
    void failsWhenModelServiceIsMissing() {
        WorkflowPromptDraftService service = new WorkflowPromptDraftService();

        assertThatThrownBy(() -> service.draft(new WorkflowPromptDraftRequest("判断正负面", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("workflow prompt drafting");
    }
}
