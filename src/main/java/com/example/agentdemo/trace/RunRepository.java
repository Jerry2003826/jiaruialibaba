package com.example.agentdemo.trace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunRepository extends JpaRepository<RunEntity, String> {

    List<RunEntity> findAllByOrderByStartedAtDesc();

}
