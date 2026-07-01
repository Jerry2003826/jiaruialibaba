package com.example.agentdemo.app.apikey;

import com.example.agentdemo.app.apikey.dto.ApiKeyResponse;
import com.example.agentdemo.app.apikey.dto.CreateApiKeyRequest;
import com.example.agentdemo.app.apikey.dto.CreatedApiKeyResponse;
import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Console management of runtime API keys. Requires {@code app.write} to create/revoke and
 * {@code app.read} to list. The plaintext key is returned only from the create endpoint.
 */
@RestController
@RequestMapping("/api/apps/{appId}/api-keys")
public class AppApiKeyController {

    private final AppApiKeyService appApiKeyService;

    public AppApiKeyController(AppApiKeyService appApiKeyService) {
        this.appApiKeyService = appApiKeyService;
    }

    @PostMapping
    public ApiResponse<CreatedApiKeyResponse> create(@PathVariable String appId,
            @Valid @RequestBody(required = false) CreateApiKeyRequest request) {
        return ApiResponse.ok(appApiKeyService.create(appId, request));
    }

    @GetMapping
    public ApiResponse<List<ApiKeyResponse>> list(@PathVariable String appId) {
        return ApiResponse.ok(appApiKeyService.list(appId));
    }

    @DeleteMapping("/{keyId}")
    public ApiResponse<Void> revoke(@PathVariable String appId, @PathVariable String keyId) {
        appApiKeyService.revoke(appId, keyId);
        return ApiResponse.ok(null);
    }

}
