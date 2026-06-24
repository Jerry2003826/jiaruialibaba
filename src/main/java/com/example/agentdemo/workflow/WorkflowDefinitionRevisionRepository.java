package com.example.agentdemo.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRevisionRepository extends JpaRepository<WorkflowDefinitionRevisionEntity, Long> {

    List<WorkflowDefinitionRevisionEntity> findAllByDefinitionIdOrderByVersionDesc(String definitionId);

    Optional<WorkflowDefinitionRevisionEntity> findByDefinitionIdAndVersion(String definitionId, Integer version);

}
