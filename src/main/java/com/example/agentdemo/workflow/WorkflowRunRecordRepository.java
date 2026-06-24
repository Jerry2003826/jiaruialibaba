package com.example.agentdemo.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRunRecordRepository extends JpaRepository<WorkflowRunRecordEntity, String> {

    List<WorkflowRunRecordEntity> findAllByDefinitionIdOrderByStartedAtDesc(String definitionId);

    List<WorkflowRunRecordEntity> findAllByDefinitionIdAndDefinitionVersionOrderByStartedAtDesc(
            String definitionId, Integer definitionVersion);

}
