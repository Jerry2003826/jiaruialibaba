package com.example.agentdemo.workflow.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkflowHttpProperties.class)
public class WorkflowHttpConfig {
}
