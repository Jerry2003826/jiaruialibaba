package com.example.agentdemo.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, dependency-free liveness probe for load balancers and uptime checks. It intentionally
 * returns only {@code {"status":"UP"}} and never touches the model, vector store or configuration
 * state; deeper diagnostics live behind the authenticated {@code GET /api/health} endpoint. The
 * richer standard probe is also available at {@code GET /actuator/health}.
 */
@RestController
public class HealthzController {

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }

}
