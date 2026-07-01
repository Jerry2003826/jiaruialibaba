package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;

import java.util.List;

/**
 * Point-in-time view of a run's events derived from its trace steps.
 *
 * @param events   ordered events (node_started/succeeded/failed, plus run_done when terminal)
 * @param terminal whether the run has reached a terminal state
 * @param status   the run status at snapshot time
 */
public record WorkflowRunEventsSnapshot(List<WorkflowRunEvent> events, boolean terminal, RunStatus status) {
}
