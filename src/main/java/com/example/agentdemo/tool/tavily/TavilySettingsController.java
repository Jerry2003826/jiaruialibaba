package com.example.agentdemo.tool.tavily;

import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/tavily")
public class TavilySettingsController {

    private final TavilyCredentialService credentialService;

    public TavilySettingsController(TavilyCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public ApiResponse<TavilyCredentialStatus> status() {
        return ApiResponse.ok(credentialService.status());
    }

    @PutMapping
    public ApiResponse<TavilyCredentialStatus> configure(@Valid @RequestBody TavilyCredentialRequest request) {
        credentialService.configure(request.apiKey());
        return ApiResponse.ok(credentialService.status());
    }

    @DeleteMapping
    public ApiResponse<TavilyCredentialStatus> clearRuntimeCredential() {
        credentialService.clearRuntimeCredential();
        return ApiResponse.ok(credentialService.status());
    }
}
