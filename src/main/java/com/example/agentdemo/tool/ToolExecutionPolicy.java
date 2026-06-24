package com.example.agentdemo.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "demo.tools")
public class ToolExecutionPolicy {

    private boolean allowAllRemoteTools;

    private Set<String> allowedRemoteTools = new LinkedHashSet<>();

    public boolean canExecute(ToolDescriptor descriptor) {
        if (!descriptor.remote()) {
            return true;
        }
        return allowAllRemoteTools || allowedRemoteTools.contains(descriptor.name());
    }

    public boolean isAllowAllRemoteTools() {
        return allowAllRemoteTools;
    }

    public void setAllowAllRemoteTools(boolean allowAllRemoteTools) {
        this.allowAllRemoteTools = allowAllRemoteTools;
    }

    public Set<String> getAllowedRemoteTools() {
        return allowedRemoteTools;
    }

    public void setAllowedRemoteTools(Set<String> allowedRemoteTools) {
        this.allowedRemoteTools = normalize(allowedRemoteTools);
    }

    public static ToolExecutionPolicy allowOnlyRemoteTools(String... toolNames) {
        ToolExecutionPolicy policy = new ToolExecutionPolicy();
        policy.setAllowedRemoteTools(Arrays.stream(toolNames).collect(Collectors.toCollection(LinkedHashSet::new)));
        return policy;
    }

    private Set<String> normalize(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return toolNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
