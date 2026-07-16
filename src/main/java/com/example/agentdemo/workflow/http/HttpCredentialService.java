package com.example.agentdemo.workflow.http;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class HttpCredentialService {

    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}");
    private static final List<String> TYPES = List.of("bearer", "api_key_header", "basic");

    private final HttpCredentialRepository repository;
    private final HttpCredentialCipher cipher;

    public HttpCredentialService(HttpCredentialRepository repository, HttpCredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<HttpCredentialResponse> list() {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(SecurityIdentity.currentOwnerId()).stream()
                .map(HttpCredentialResponse::from)
                .toList();
    }

    @Transactional
    public HttpCredentialResponse create(HttpCredentialRequest request) {
        NormalizedCredential normalized = normalize(request);
        HttpCredentialEntity entity = new HttpCredentialEntity(newCredentialId(), normalized.name(),
                normalized.type(), cipher.encrypt(normalized.values()));
        return HttpCredentialResponse.from(repository.save(entity));
    }

    @Transactional
    public HttpCredentialResponse update(String credentialId, HttpCredentialRequest request) {
        HttpCredentialEntity entity = requireOwned(credentialId);
        NormalizedCredential normalized = normalize(request);
        entity.update(normalized.name(), normalized.type(), cipher.encrypt(normalized.values()));
        return HttpCredentialResponse.from(repository.save(entity));
    }

    @Transactional
    public void delete(String credentialId) {
        repository.delete(requireOwned(credentialId));
    }

    @Transactional(readOnly = true)
    public HttpResolvedCredential resolve(String credentialId) {
        return resolveForOwner(credentialId, SecurityIdentity.currentOwnerId());
    }

    @Transactional(readOnly = true)
    public HttpResolvedCredential resolveForOwner(String credentialId, String ownerId) {
        HttpCredentialEntity entity = requireOwned(credentialId, ownerId);
        return new HttpResolvedCredential(entity.getCredentialId(), entity.getType(),
                cipher.decrypt(entity.getEncryptedPayload()));
    }

    private HttpCredentialEntity requireOwned(String credentialId) {
        return requireOwned(credentialId, SecurityIdentity.currentOwnerId());
    }

    private HttpCredentialEntity requireOwned(String credentialId, String ownerId) {
        if (!StringUtils.hasText(credentialId)) {
            throw new BusinessException("HTTP_CREDENTIAL_REQUIRED", "HTTP credential id is required");
        }
        String requiredOwnerId = StringUtils.hasText(ownerId) ? ownerId.trim() : SecurityIdentity.DEFAULT_OWNER_ID;
        return repository.findByCredentialIdAndOwnerId(credentialId.trim(), requiredOwnerId)
                .orElseThrow(() -> new BusinessException("HTTP_CREDENTIAL_NOT_FOUND",
                        "HTTP credential was not found or is not owned by the current user"));
    }

    private NormalizedCredential normalize(HttpCredentialRequest request) {
        String name = requireText(request.name(), "HTTP credential name is required");
        String type = requireText(request.type(), "HTTP credential type is required").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BusinessException("HTTP_CREDENTIAL_INVALID", "Unsupported HTTP credential type: " + type);
        }
        Map<String, String> values = new LinkedHashMap<>();
        switch (type) {
            case "bearer" -> values.put("token", requireText(request.token(), "Bearer token is required"));
            case "api_key_header" -> {
                String headerName = requireText(request.headerName(), "API key header name is required");
                if (!HEADER_NAME.matcher(headerName).matches()) {
                    throw new BusinessException("HTTP_CREDENTIAL_INVALID", "API key header name is invalid");
                }
                values.put("headerName", headerName);
                values.put("value", requireText(request.value(), "API key value is required"));
            }
            case "basic" -> {
                values.put("username", requireText(request.username(), "Basic auth username is required"));
                values.put("password", requireText(request.password(), "Basic auth password is required"));
            }
            default -> throw new IllegalStateException("Unexpected credential type: " + type);
        }
        return new NormalizedCredential(name, type, values);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("HTTP_CREDENTIAL_INVALID", message);
        }
        return value.trim();
    }

    private String newCredentialId() {
        return "cred_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record NormalizedCredential(String name, String type, Map<String, String> values) {
    }
}
