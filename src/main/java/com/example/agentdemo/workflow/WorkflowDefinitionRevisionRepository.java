package com.example.agentdemo.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRevisionRepository extends JpaRepository<WorkflowDefinitionRevisionEntity, Long> {

    List<WorkflowDefinitionRevisionEntity> findAllByDefinitionIdOrderByVersionDesc(String definitionId);

    List<WorkflowDefinitionRevisionEntity> findAllByDefinitionIdAndOwnerIdOrderByVersionDesc(String definitionId,
            String ownerId);

    Optional<WorkflowDefinitionRevisionEntity> findByDefinitionIdAndVersion(String definitionId, Integer version);

    Optional<WorkflowDefinitionRevisionEntity> findByDefinitionIdAndVersionAndOwnerId(String definitionId,
            Integer version, String ownerId);

    void deleteAllByDefinitionId(String definitionId);

    void deleteAllByDefinitionIdAndOwnerId(String definitionId, String ownerId);

}
