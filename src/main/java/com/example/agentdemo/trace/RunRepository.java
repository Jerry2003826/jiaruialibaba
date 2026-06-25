package com.example.agentdemo.trace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RunRepository extends JpaRepository<RunEntity, String>, JpaSpecificationExecutor<RunEntity> {

    List<RunEntity> findAllByOrderByStartedAtDesc();

    List<RunEntity> findAllByRunIdIn(List<String> runIds);

    Page<RunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);

}
