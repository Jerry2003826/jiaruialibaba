package com.example.agentdemo.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Type-specific configuration for an app. Fields are optional and interpreted per {@link AppType}:
 * CHAT/AGENT use {@code systemPrompt}, {@code model}, {@code memoryEnabled} and
 * {@code memoryMaxMessages}; WORKFLOW apps bind a workflow via the app's dedicated columns and
 * generally leave these null. Unknown properties are ignored so the config schema can evolve.
 *
 * @param systemPrompt      system prompt for CHAT/AGENT apps (nullable)
 * @param model             model override, e.g. {@code qwen-plus} (nullable → server default)
 * @param memoryEnabled     whether conversation memory is used (null → enabled)
 * @param memoryMaxMessages max recent messages to load (nullable → server default)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppConfig(String systemPrompt, String model, Boolean memoryEnabled, Integer memoryMaxMessages) {

    public static AppConfig empty() {
        return new AppConfig(null, null, null, null);
    }

    public boolean memoryEnabledOrDefault() {
        return memoryEnabled == null || memoryEnabled;
    }

}
