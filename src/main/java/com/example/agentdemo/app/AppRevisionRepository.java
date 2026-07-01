package com.example.agentdemo.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AppRevisionEntity}, always owner-scoped.
 */
public interface AppRevisionRepository extends JpaRepository<AppRevisionEntity, Long> {

    Optional<AppRevisionEntity> findByAppIdAndVersionAndOwnerId(String appId, Integer version, String ownerId);

    List<AppRevisionEntity> findByAppIdAndOwnerIdOrderByVersionDesc(String appId, String ownerId);

    void deleteByAppIdAndOwnerId(String appId, String ownerId);

}
