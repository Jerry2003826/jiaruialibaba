package com.example.agentdemo.order;

import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OrderIdExtractor {

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("(?<![A-Za-z0-9])(\\d{8,})(?![A-Za-z0-9])");

    private OrderIdExtractor() {
    }

    public static Optional<String> extractFirst(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    public static List<String> extractAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(text);
        LinkedHashSet<String> orderIds = new LinkedHashSet<>();
        while (matcher.find()) {
            orderIds.add(matcher.group(1));
        }
        return List.copyOf(orderIds);
    }

}
