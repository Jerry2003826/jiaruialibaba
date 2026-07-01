package com.example.agentdemo.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface DemoOrderRepository extends JpaRepository<DemoOrderEntity, String> {

    Page<DemoOrderEntity> findAllByOwnerId(String ownerId, Pageable pageable);

    boolean existsByOrderIdAndOwnerId(String orderId, String ownerId);

    Optional<DemoOrderEntity> findByOrderIdAndOwnerId(String orderId, String ownerId);

    void deleteByOrderIdAndOwnerId(String orderId, String ownerId);

}
