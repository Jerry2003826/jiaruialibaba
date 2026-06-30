package com.example.agentdemo.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoOrderRepository extends JpaRepository<DemoOrderEntity, String> {
}
