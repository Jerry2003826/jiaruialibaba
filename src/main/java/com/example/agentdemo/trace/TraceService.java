package com.example.agentdemo.trace;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.dto.RunPageResponse;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TraceService {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final ObjectMapper objectMapper;

    public TraceService(RunRepository runRepository, RunStepRepository runStepRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TraceRun startRun(RunType type, Object input) {
        RunEntity run = createRun(type, input);
        return toTraceRun(run);
    }

    private RunEntity createRun(RunType type, Object input) {
        RunEntity run = new RunEntity(newId(), type, RunStatus.RUNNING, toJson(input), Instant.now());
        return runRepository.save(run);
    }

    @Transactional
    public TraceStep startTraceStep(String runId, String nodeName, Object input) {
        RunStepEntity step = startStep(runId, nodeName, input);
        return toTraceStep(step);
    }

    private RunStepEntity startStep(String runId, String nodeName, Object input) {
        ensureRunExists(runId);
        RunStepEntity step = new RunStepEntity(newId(), runId, nodeName, toJson(input), StepStatus.RUNNING,
                Instant.now());
        return runStepRepository.save(step);
    }

    @Transactional
    public void completeStep(String stepId, Object output) {
        RunStepEntity step = findStep(stepId);
        step.setOutputJson(toJson(output));
        step.setStatus(StepStatus.SUCCEEDED);
        step.setEndedAt(Instant.now());
        runStepRepository.save(step);
    }

    @Transactional
    public void failStep(String stepId, Throwable error) {
        failStep(stepId, error, new ErrorPayload(error.getClass().getSimpleName(), error.getMessage()));
    }

    @Transactional
    public void failStep(String stepId, Throwable error, Object output) {
        RunStepEntity step = findStep(stepId);
        step.setErrorMessage(error.getMessage());
        step.setOutputJson(toJson(output));
        step.setStatus(StepStatus.FAILED);
        step.setEndedAt(Instant.now());
        runStepRepository.save(step);
    }

    @Transactional
    public void markRunSucceeded(String runId, Object output) {
        RunEntity run = findRun(runId);
        run.setOutput(toJson(output));
        run.setStatus(RunStatus.SUCCEEDED);
        run.setEndedAt(Instant.now());
        runRepository.save(run);
    }

    @Transactional
    public void markRunFailed(String runId, Throwable error) {
        RunEntity run = findRun(runId);
        run.setErrorMessage(error.getMessage());
        run.setOutput(toJson(new ErrorPayload(error.getClass().getSimpleName(), error.getMessage())));
        run.setStatus(RunStatus.FAILED);
        run.setEndedAt(Instant.now());
        runRepository.save(run);
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRuns() {
        return listRuns(null, null, 0, 20).content();
    }

    @Transactional(readOnly = true)
    public RunPageResponse listRuns(RunType type, RunStatus status, int page, int size) {
        validateRunQuery(page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<RunEntity> runPage = runRepository.findAll(RunSpecifications.filter(type, status), pageable);
        List<RunResponse> content = runPage.getContent().stream().map(this::toRunResponse).toList();
        return new RunPageResponse(content, runPage.getNumber(), runPage.getSize(), runPage.getTotalElements(),
                runPage.getTotalPages());
    }

    private void validateRunQuery(int page, int size) {
        if (page < 0) {
            throw new BusinessException("RUN_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("RUN_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(String runId) {
        return toRunResponse(findRun(runId));
    }

    @Transactional(readOnly = true)
    public Map<String, RunResponse> findRunsById(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return runRepository.findAllByRunIdIn(runIds)
                .stream()
                .map(this::toRunResponse)
                .collect(Collectors.toMap(RunResponse::runId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public List<RunStepResponse> listSteps(String runId) {
        ensureRunExists(runId);
        return runStepRepository.findByRunIdOrderByStartedAtAsc(runId)
                .stream()
                .map(this::toRunStepResponse)
                .toList();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getMessage() + "\"}";
        }
    }

    private void ensureRunExists(String runId) {
        if (!runRepository.existsById(runId)) {
            throw new BusinessException("RUN_NOT_FOUND", "Run not found: " + runId);
        }
    }

    private RunEntity findRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException("RUN_NOT_FOUND", "Run not found: " + runId));
    }

    private RunStepEntity findStep(String stepId) {
        return runStepRepository.findById(stepId)
                .orElseThrow(() -> new BusinessException("STEP_NOT_FOUND", "Step not found: " + stepId));
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private TraceRun toTraceRun(RunEntity entity) {
        return new TraceRun(entity.getRunId(), entity.getStartedAt());
    }

    private TraceStep toTraceStep(RunStepEntity entity) {
        return new TraceStep(entity.getStepId(), entity.getRunId(), entity.getNodeName());
    }

    private RunResponse toRunResponse(RunEntity entity) {
        return new RunResponse(entity.getRunId(), entity.getType(), entity.getStatus(), entity.getInput(),
                entity.getOutput(), entity.getErrorMessage(), entity.getStartedAt(), entity.getEndedAt());
    }

    private RunStepResponse toRunStepResponse(RunStepEntity entity) {
        return new RunStepResponse(entity.getStepId(), entity.getRunId(), entity.getNodeName(),
                entity.getInputJson(), entity.getOutputJson(), entity.getErrorMessage(), entity.getStatus(),
                entity.getStartedAt(), entity.getEndedAt());
    }

    private record ErrorPayload(String type, String message) {
    }

}
