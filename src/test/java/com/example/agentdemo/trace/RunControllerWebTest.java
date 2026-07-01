package com.example.agentdemo.trace;

import com.example.agentdemo.trace.dto.RunPageResponse;
import com.example.agentdemo.trace.dto.RunResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunControllerWebTest {

    @Test
    void listRunsRouteReturnsPagedResponse() throws Exception {
        TraceService traceService = mock(TraceService.class);
        when(traceService.listRuns(null, null, 0, 20)).thenReturn(new RunPageResponse(
                List.of(new RunResponse("run-1", RunType.CHAT, RunStatus.SUCCEEDED, "{}", "{}", null,
                        Instant.parse("2026-06-25T00:00:00Z"), Instant.parse("2026-06-25T00:00:01Z"))),
                0, 20, 1, 1));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new RunController(traceService, mock(com.example.agentdemo.usage.UsageRecordingService.class)))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/runs?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].runId").value("run-1"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

}
