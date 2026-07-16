package com.example.agentdemo.workflow;

import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseResult;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;

import java.util.List;

public record WorkflowGovernanceEvaluationResponse(
        WorkflowGenerationStatus status,
        WorkflowDefinition workflowDefinition,
        WorkflowGovernanceReport governanceReport,
        WorkflowEvaluationReport evaluationReport,
        List<WorkflowEvaluationCaseResult> testResults,
        List<WorkflowActiveRulePack> activeRulePacks) {

    public WorkflowGovernanceEvaluationResponse {
        status = status == null ? WorkflowGenerationStatus.BLOCKED : status;
        governanceReport = governanceReport == null
                ? new WorkflowGovernanceReport(List.of())
                : governanceReport;
        evaluationReport = evaluationReport == null
                ? new WorkflowEvaluationReport(null, List.of())
                : evaluationReport;
        testResults = testResults == null ? evaluationReport.caseResults() : List.copyOf(testResults);
        activeRulePacks = activeRulePacks == null ? List.of() : List.copyOf(activeRulePacks);
    }
}
