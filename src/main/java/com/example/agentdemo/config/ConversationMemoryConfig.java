package com.example.agentdemo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ConversationMemoryProperties.class, WorkflowRuntimeProperties.class})
public class ConversationMemoryConfig {
}
