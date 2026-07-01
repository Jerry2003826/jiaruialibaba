package com.example.agentdemo.app.dto;

import com.example.agentdemo.app.validation.ValidAppRunInput;

import java.util.Map;

/**
 * Runtime request to run a WORKFLOW app.
 *
 * @param input workflow input variables (may be null/empty)
 */
public record AppRunRequest(@ValidAppRunInput Map<String, Object> input) {
}
