package com.example.agentdemo.trace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, String>, JpaSpecificationExecutor<RunEntity> {

    List<RunEntity> findAllByOrderByStartedAtDesc();

    List<RunEntity> findAllByRunIdIn(List<String> runIds);

    List<RunEntity> findAllByOwnerIdAndRunIdIn(String ownerId, List<String> runIds);

    Page<RunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<RunEntity> findByRunIdAndOwnerId(String runId, String ownerId);

    boolean existsByRunIdAndOwnerId(String runId, String ownerId);

    boolean existsByOwnerIdAndAppId(String ownerId, String appId);

}
