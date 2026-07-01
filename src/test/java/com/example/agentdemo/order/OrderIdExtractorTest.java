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

    @Test
    void doesNotTreatDigitsAdjacentToDecimalPointAsOrderId() {
        assertThat(OrderIdExtractor.extractFirst("订单金额 12345678.90 元")).isEmpty();
        assertThat(OrderIdExtractor.extractAll("应付 12345678.90，实付 87654321.00")).isEmpty();
    }

    @Test
    void stillExtractsStandaloneOrderId() {
        assertThat(OrderIdExtractor.extractFirst("订单号 20260630001 帮我查物流")).contains("20260630001");
    }

    @Test
    void extractsOrderIdImmediatelyFollowedByASentencePeriod() {
        assertThat(OrderIdExtractor.extractFirst("Please check order 20260630001.")).contains("20260630001");
        assertThat(OrderIdExtractor.extractAll("Orders 20260630001. and 20260630002."))
                .containsExactly("20260630001", "20260630002");
    }

}
