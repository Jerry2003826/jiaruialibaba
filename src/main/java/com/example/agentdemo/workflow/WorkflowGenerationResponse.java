package com.example.agentdemo.workflow;

import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseResult;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record WorkflowGenerationResponse(
        String name,
        String description,
        WorkflowDefinition workflowDefinition,
        List<String> notes,
        WorkflowGenerationTestResult testResult,
        WorkflowGenerationStatus status,
        WorkflowGovernanceReport governanceReport,
        List<WorkflowEvaluationCaseResult> testResults,
        int repairAttempts,
        List<WorkflowActiveRulePack> activeRulePacks,
        JsonNode lockedSpec,
        WorkflowVariableSchema variables) {

    public WorkflowGenerationResponse {
        notes = notes == null ? List.of() : List.copyOf(notes);
        status = status == null ? WorkflowGenerationStatus.READY : status;
        governanceReport = governanceReport == null
                ? new WorkflowGovernanceReport(List.of())
                : governanceReport;
        testResults = testResults == null ? List.of() : List.copyOf(testResults);
        repairAttempts = Math.max(0, repairAttempts);
        activeRulePacks = activeRulePacks == null ? List.of() : List.copyOf(activeRulePacks);
        lockedSpec = lockedSpec == null ? null : lockedSpec.deepCopy();
        variables = WorkflowVariableSchemaInferrer.infer(workflowDefinition, variables);
    }

    public WorkflowGenerationResponse(String name, String description, WorkflowDefinition workflowDefinition,
            List<String> notes, WorkflowGenerationTestResult testResult, WorkflowGenerationStatus status,
            WorkflowGovernanceReport governanceReport, List<WorkflowEvaluationCaseResult> testResults,
            int repairAttempts, List<WorkflowActiveRulePack> activeRulePacks, JsonNode lockedSpec) {
        this(name, description, workflowDefinition, notes, testResult, status, governanceReport, testResults,
                repairAttempts, activeRulePacks, lockedSpec, null);
    }

    public WorkflowGenerationResponse(String name, String description, WorkflowDefinition workflowDefinition,
            List<String> notes, WorkflowGenerationTestResult testResult, WorkflowGenerationStatus status,
            WorkflowGovernanceReport governanceReport, List<WorkflowEvaluationCaseResult> testResults,
            int repairAttempts, List<WorkflowActiveRulePack> activeRulePacks) {
        this(name, description, workflowDefinition, notes, testResult, status, governanceReport, testResults,
                repairAttempts, activeRulePacks, null);
    }

    public WorkflowGenerationResponse(String name, String description, WorkflowDefinition workflowDefinition,
            List<String> notes, WorkflowGenerationTestResult testResult, WorkflowGenerationStatus status,
            WorkflowGovernanceReport governanceReport, List<WorkflowEvaluationCaseResult> testResults,
            int repairAttempts) {
        this(name, description, workflowDefinition, notes, testResult, status, governanceReport, testResults,
                repairAttempts, List.of(), null);
    }

    public WorkflowGenerationResponse(String name, String description, WorkflowDefinition workflowDefinition,
            List<String> notes, WorkflowGenerationTestResult testResult) {
        this(name, description, workflowDefinition, notes, testResult,
                WorkflowGenerationStatus.READY, new WorkflowGovernanceReport(List.of()), List.of(), 0, List.of());
    }

    public WorkflowGenerationResponse(String name, String description, WorkflowDefinition workflowDefinition,
            List<String> notes) {
        this(name, description, workflowDefinition, notes, null);
    }

}
