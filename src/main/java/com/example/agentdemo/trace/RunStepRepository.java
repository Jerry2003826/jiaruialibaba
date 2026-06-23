package com.example.agentdemo.trace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunStepRepository extends JpaRepository<RunStepEntity, String> {

    List<RunStepEntity> findByRunIdOrderByStartedAtAsc(String runId);

}
