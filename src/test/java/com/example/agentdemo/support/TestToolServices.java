package com.example.agentdemo.support;

import com.example.agentdemo.tool.ToolService;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TestToolServices {

    private TestToolServices() {
    }

    public static ToolService toolService() {
        return new ToolService(userQuery -> {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("found", true);
            output.put("orderId", "20260630001");
            output.put("matchedBy", "orderId");
            output.put("customerName", "Alice Chen");
            output.put("status", "SHIPPED");
            output.put("paid", true);
            output.put("amount", "299.00");
            output.put("currency", "CNY");
            output.put("carrier", "SF Express");
            output.put("trackingNumber", "SF20260630001");
            output.put("estimatedDelivery", "2026-07-02");
            output.put("latestEvent", "Package left the Shanghai transit center");
            output.put("nextAction", "Tell the customer the order has shipped and provide the tracking number.");
            output.put("source", "database:demo_orders");
            if (userQuery != null && !userQuery.isBlank()) {
                output.put("userQuery", userQuery);
            }
            return output;
        });
    }

}
