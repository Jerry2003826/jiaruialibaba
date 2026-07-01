package com.example.agentdemo.common;

import com.example.agentdemo.tool.ToolExecutionLog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsRemoteToolErrorsToBadGateway() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ToolExecutionLog.ERROR_REMOTE_TOOL, "remote failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ToolExecutionLog.ERROR_REMOTE_TOOL);
    }

    @Test
    void keepsValidationAndPolicyErrorsAsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ToolExecutionLog.ERROR_TOOL_NOT_ALLOWED, "not allowed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void illegalArgumentResponseDoesNotExposeRawExceptionMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("secret backend path /tmp/private-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Bad request");
    }

}
