package com.example.agentdemo.workflow.http;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HttpCredentialRepository extends JpaRepository<HttpCredentialEntity, Long> {

    List<HttpCredentialEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<HttpCredentialEntity> findByCredentialIdAndOwnerId(String credentialId, String ownerId);
}
