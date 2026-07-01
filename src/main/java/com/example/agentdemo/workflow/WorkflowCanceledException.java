package com.example.agentdemo.workflow;

/**
 * Thrown at a node boundary when a run has been cancelled via
 * {@link WorkflowRunBudgetRegistry#cancel(String)}. {@code WorkflowService.run} distinguishes it
 * from a failure and marks the run {@code CANCELED}.
 */
public class WorkflowCanceledException extends RuntimeException {

    public WorkflowCanceledException(String runId) {
        super("Workflow run " + runId + " was canceled");
    }

}
