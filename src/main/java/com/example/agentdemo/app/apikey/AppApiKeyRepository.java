package com.example.agentdemo.app.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AppApiKeyEntity}.
 */
public interface AppApiKeyRepository extends JpaRepository<AppApiKeyEntity, Long> {

    /** Authentication lookup: an active key by its hash. Not owner-scoped (the key proves identity). */
    Optional<AppApiKeyEntity> findByKeyHashAndStatus(String keyHash, AppApiKeyStatus status);

    List<AppApiKeyEntity> findByAppIdAndOwnerIdOrderByCreatedAtDesc(String appId, String ownerId);

    Optional<AppApiKeyEntity> findByKeyIdAndAppIdAndOwnerId(String keyId, String appId, String ownerId);

}
