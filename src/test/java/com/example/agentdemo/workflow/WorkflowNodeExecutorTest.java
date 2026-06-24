package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolExecutionPolicy;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowNodeExecutorTest {

    @Test
    void toolNodeUsesToolGatewaySoRemoteToolsCanBeCalled() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new RemoteEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway);
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("mcp_1", "tool", Map.of(
                        "toolName", "remote_echo",
                        "arguments", Map.of("text", "{{input}}")
                )),
                state);

        assertThat(output).isInstanceOf(ToolExecutionLog.class);
        assertThat(state.lastOutput()).isEqualTo("remote:hello");
        assertThat(state.toolCalls()).hasSize(1);
    }

    private static final class RemoteEchoProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return new ToolExecutionLog(toolName, arguments, "remote:" + arguments.get("text"), true, null, now, now);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_echo", "Remote echo", providerName(), true));
        }

    }

}
