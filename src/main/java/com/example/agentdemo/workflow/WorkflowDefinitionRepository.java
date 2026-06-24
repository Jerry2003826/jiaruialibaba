package com.example.agentdemo.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, Long> {

    Optional<WorkflowDefinitionEntity> findByDefinitionId(String definitionId);

    List<WorkflowDefinitionEntity> findAllByOrderByCreatedAtDesc();

}
