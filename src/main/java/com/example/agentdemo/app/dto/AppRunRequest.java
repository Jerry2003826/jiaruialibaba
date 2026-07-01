package com.example.agentdemo.app.dto;

import java.util.Map;

/**
 * Runtime request to run a WORKFLOW app.
 *
 * @param input workflow input variables (may be null/empty)
 */
public record AppRunRequest(Map<String, Object> input) {
}
