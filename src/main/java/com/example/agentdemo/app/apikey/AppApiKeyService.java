package com.example.agentdemo.app.apikey;

import com.example.agentdemo.app.AppService;
import com.example.agentdemo.app.apikey.dto.ApiKeyResponse;
import com.example.agentdemo.app.apikey.dto.CreateApiKeyRequest;
import com.example.agentdemo.app.apikey.dto.CreatedApiKeyResponse;
import com.example.agentdemo.audit.AuditService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Manages runtime API keys for apps. Keys are always scoped to a single app and carry only runtime
 * scopes ({@code app.run}); the plaintext is returned once at creation and only its hash is stored.
 */
@Service
public class AppApiKeyService {

    /** The only scopes a runtime key may hold — it can never reach the console management API. */
    static final String DEFAULT_SCOPES = "app.run";

    private final AppApiKeyRepository appApiKeyRepository;
    private final AppService appService;
    private final AuditService auditService;

    public AppApiKeyService(AppApiKeyRepository appApiKeyRepository, AppService appService,
            AuditService auditService) {
        this.appApiKeyRepository = appApiKeyRepository;
        this.appService = appService;
        this.auditService = auditService;
    }

    @Transactional
    public CreatedApiKeyResponse create(String appId, CreateApiKeyRequest request) {
        // Verifies the app exists and is owned by the caller before minting a key for it.
        appService.findApp(appId);
        String keyId = ApiKeySecrets.newKeyId();
        String plaintext = ApiKeySecrets.newPlaintextKey();
        String name = request == null ? null : normalize(request.name());
        AppApiKeyEntity entity = new AppApiKeyEntity(keyId, appId, ApiKeySecrets.hash(plaintext), name,
                DEFAULT_SCOPES);
        appApiKeyRepository.save(entity);
        auditService.recordSuccess("api-key.create", "app-api-key", keyId);
        return new CreatedApiKeyResponse(keyId, appId, name, scopeList(DEFAULT_SCOPES), plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(String appId) {
        appService.findApp(appId);
        return appApiKeyRepository
                .findByAppIdAndOwnerIdOrderByCreatedAtDesc(appId, SecurityIdentity.currentOwnerId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void revoke(String appId, String keyId) {
        appService.findApp(appId);
        AppApiKeyEntity entity = appApiKeyRepository
                .findByKeyIdAndAppIdAndOwnerId(keyId, appId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("API_KEY_NOT_FOUND", "API key not found: " + keyId));
        entity.revoke();
        appApiKeyRepository.save(entity);
        auditService.recordSuccess("api-key.revoke", "app-api-key", keyId);
    }

    private ApiKeyResponse toResponse(AppApiKeyEntity entity) {
        return new ApiKeyResponse(entity.getKeyId(), entity.getAppId(), entity.getName(),
                scopeList(entity.getScopes()), entity.getStatus(), entity.getCreatedAt(), entity.getLastUsedAt(),
                entity.getRevokedAt());
    }

    private List<String> scopeList(String scopes) {
        if (!StringUtils.hasText(scopes)) {
            return List.of();
        }
        return Arrays.stream(scopes.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

}
