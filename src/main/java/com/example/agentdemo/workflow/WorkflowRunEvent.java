package com.example.agentdemo.workflow;

import java.util.Map;

/**
 * A single workflow run event streamed over SSE: {@code node_started}, {@code node_succeeded},
 * {@code node_failed} or {@code run_done}.
 *
 * @param event the SSE event name
 * @param data  the event payload
 */
public record WorkflowRunEvent(String event, Map<String, Object> data) {
}
