package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;

/**
 * Result of a cancel request.
 *
 * @param runId     the run id
 * @param status    the run status at request time
 * @param requested whether a cancellation was actually signalled to an active run
 */
public record WorkflowRunCancelResponse(String runId, RunStatus status, boolean requested) {
}
