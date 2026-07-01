package com.example.agentdemo.order;

import com.example.agentdemo.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DatabaseOrderLookupService implements OrderLookupService {

    private final DemoOrderRepository demoOrderRepository;

    public DatabaseOrderLookupService(DemoOrderRepository demoOrderRepository) {
        this.demoOrderRepository = demoOrderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> lookup(String userQuery) {
        String query = userQuery == null ? "" : userQuery.trim();
        String orderId = extractOrderId(query)
                .orElseThrow(() -> new BusinessException("ORDER_ID_REQUIRED",
                        "Order id is required for queryOrderAPI"));
        DemoOrderEntity order = demoOrderRepository.findById(orderId)
                .orElse(null);
        if (order == null) {
            return missingToolOutput(orderId, query);
        }
        return toToolOutput(order, query);
    }

    private Optional<String> extractOrderId(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        return OrderIdExtractor.extractFirst(query);
    }

    private Map<String, Object> toToolOutput(DemoOrderEntity order, String query) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("found", true);
        output.put("orderId", order.getOrderId());
        output.put("matchedBy", "orderId");
        if (order.getCustomerName() != null && !order.getCustomerName().isBlank()) {
            output.put("customerName", order.getCustomerName());
        }
        output.put("status", order.getStatus());
        output.put("paid", order.isPaid());
        output.put("amount", order.getAmount() == null ? null
                : order.getAmount().stripTrailingZeros().toPlainString());
        output.put("currency", order.getCurrency());
        output.put("carrier", order.getCarrier());
        output.put("trackingNumber", order.getTrackingNumber());
        output.put("estimatedDelivery", order.getEstimatedDelivery() == null ? null
                : order.getEstimatedDelivery().toString());
        output.put("latestEvent", order.getLatestEvent());
        output.put("nextAction", order.getNextAction());
        output.put("source", "database:demo_orders");
        if (query != null && !query.isBlank()) {
            output.put("userQuery", query);
        }
        return output;
    }

    private Map<String, Object> missingToolOutput(String orderId, String query) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("found", false);
        output.put("orderId", orderId);
        output.put("matchedBy", "orderId");
        output.put("source", "database:demo_orders");
        if (query != null && !query.isBlank()) {
            output.put("userQuery", query);
        }
        return output;
    }

}
