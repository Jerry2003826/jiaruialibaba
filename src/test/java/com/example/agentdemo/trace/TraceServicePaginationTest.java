package com.example.agentdemo.trace;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.dto.RunPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceServicePaginationTest {

    @Test
    void listRunsReturnsPagedResponseWithFilters() {
        RunRepository runRepository = mock(RunRepository.class);
        RunStepRepository runStepRepository = mock(RunStepRepository.class);
        TraceService traceService = new TraceService(runRepository, runStepRepository, new com.fasterxml.jackson.databind.ObjectMapper());
        RunEntity entity = new RunEntity("run-1", RunType.CHAT, RunStatus.SUCCEEDED, "{}", Instant.now());
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startedAt"));
        when(runRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        RunPageResponse page = traceService.listRuns(RunType.CHAT, RunStatus.SUCCEEDED, 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().runId()).isEqualTo("run-1");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void listRunsRejectsInvalidPageSize() {
        RunRepository runRepository = mock(RunRepository.class);
        TraceService traceService = new TraceService(runRepository, mock(RunStepRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> traceService.listRuns(null, null, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("RUN_QUERY_INVALID");
    }

}
