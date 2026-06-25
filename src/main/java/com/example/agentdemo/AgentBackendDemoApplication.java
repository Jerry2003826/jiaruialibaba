package com.example.agentdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentBackendDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBackendDemoApplication.class, args);
    }

}
