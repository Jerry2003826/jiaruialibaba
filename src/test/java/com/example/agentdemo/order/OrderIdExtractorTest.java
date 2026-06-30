package com.example.agentdemo.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIdExtractorTest {

    @Test
    void extractAllReturnsUniqueOrderIdsInEncounterOrder() {
        assertThat(OrderIdExtractor.extractAll(
                "订单 20260630001 和 20260630002，重复 20260630001，abc123 不算订单号"))
                .containsExactly("20260630001", "20260630002");
    }

}
