package com.example.agentdemo.config;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    private final RagProperties ragProperties;

    public RagConfig(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    void validateRagProperties() {
        ragProperties.validate();
    }

}
