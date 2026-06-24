package com.example.agentdemo.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "demo.mcp.registry")
public class McpServerRegistryProperties {

    private List<Server> servers = new ArrayList<>();

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers == null ? new ArrayList<>() : new ArrayList<>(servers);
    }

    public static class Server {

        private String name;

        private boolean enabled;

        private String transport = "stdio";

        private String connectionName;

        private String description;

        private String commandEnv;

        private Set<String> requiredEnv = new LinkedHashSet<>();

        private Set<String> toolsets = new LinkedHashSet<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = normalizeText(name);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = StringUtils.hasText(transport) ? transport.trim() : "stdio";
        }

        public String getConnectionName() {
            return connectionName;
        }

        public void setConnectionName(String connectionName) {
            this.connectionName = normalizeText(connectionName);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = normalizeText(description);
        }

        public String getCommandEnv() {
            return commandEnv;
        }

        public void setCommandEnv(String commandEnv) {
            this.commandEnv = normalizeText(commandEnv);
        }

        public Set<String> getRequiredEnv() {
            return requiredEnv;
        }

        public void setRequiredEnv(Set<String> requiredEnv) {
            this.requiredEnv = normalizeSet(requiredEnv);
        }

        public Set<String> getToolsets() {
            return toolsets;
        }

        public void setToolsets(Set<String> toolsets) {
            this.toolsets = normalizeSet(toolsets);
        }

        private static String normalizeText(String value) {
            return StringUtils.hasText(value) ? value.trim() : "";
        }

        private static Set<String> normalizeSet(Set<String> values) {
            Set<String> normalized = new LinkedHashSet<>();
            if (values == null) {
                return normalized;
            }
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    normalized.add(value.trim());
                }
            }
            return normalized;
        }

    }

}
