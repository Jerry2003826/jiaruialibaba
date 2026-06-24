package com.example.agentdemo.tool;

import com.example.agentdemo.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolControllerTest {

    @Test
    void listsRegisteredTools() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
        ToolController controller = new ToolController(gateway);

        ApiResponse<List<ToolDescriptor>> response = controller.listTools();

        assertThat(response.success()).isTrue();
        assertThat(response.data())
                .extracting(ToolDescriptor::name)
                .contains("getCurrentTime", "calculate");
    }

}
